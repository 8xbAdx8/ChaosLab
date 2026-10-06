package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

func main() {
	out := result("DENIED")
	if len(os.Args) == 1 && productionAllowed() {
		prepareProcess()
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		// A pipe whose writer never sends EOF must not retain a privileged process.
		type input struct {
			q Request
			e error
		}
		ch := make(chan input, 1)
		go func() { q, e := readRequest(os.Stdin); ch <- input{q, e} }()
		select {
		case in := <-ch:
			if in.e == nil {
				out = serve(ctx, productionConfig(), in.q)
			} else {
				out = result("INVALID_REQUEST")
			}
		case <-time.After(2 * time.Second):
			out = result("INPUT_TIMEOUT")
		case <-ctx.Done():
			out = result("INTERRUPTED")
		}
	}
	// Errors never print raw OS errors, child stderr or request bytes.
	if !emitResult(os.Stdout, out, 2*time.Second) || out.Code != "OK" {
		os.Exit(1)
	}
}

// Do not retain a privileged wrapper forever if the caller stops reading its
// pipe. Process exit ends a blocked writer; the durable binding is unchanged.
func emitResult(w io.Writer, out Result, deadline time.Duration) bool {
	done := make(chan error, 1)
	go func() { done <- json.NewEncoder(w).Encode(out) }()
	select {
	case e := <-done:
		return e == nil
	case <-time.After(deadline):
		return false
	}
}

func serve(ctx context.Context, c config, q Request) Result {
	// Internal callers receive the same request validation as the executable.
	data, _ := json.Marshal(q)
	if _, e := readRequest(bytes.NewReader(data)); e != nil {
		return result("INVALID_REQUEST")
	}
	p, e := c.loadPolicy()
	if e != nil {
		return result("IDENTITY_REJECTED")
	}
	c, e = c.selectDeployment(p)
	if e != nil || c.verify(p) != nil {
		return result("IDENTITY_REJECTED")
	}
	if q.Operation == "preflight" {
		if c.targetCheck(p) != nil {
			return result("TARGET_UNKNOWN")
		}
		out := result("OK")
		out.Policy, out.PolicyDigest = &p, policyDigest(p)
		if c.trustRoot == "" {
			out.Observation = observeTarget(ctx, p, nil)
		}
		return out
	}
	unlock, e := c.lock()
	if e != nil {
		return result("BUSY_OR_STALE_LOCK")
	}
	defer unlock()
	if q.Operation == "create-cpu" {
		if c.targetCheck(p) != nil {
			return result("TARGET_UNKNOWN")
		}
		if c.authorize(q, p) != nil {
			return result("CREATE_DISABLED")
		}
	} else {
		b, e := c.binding("binding.json")
		if e != nil || !b.matches(q, p) {
			return result("BINDING_REJECTED")
		}
		if c.targetCheck(p) != nil {
			return result("TARGET_UNKNOWN")
		}
	}
	if q.Operation == "observe" {
		b, e := c.binding("binding.json")
		if e != nil {
			return result("BINDING_REJECTED")
		}
		out := result("OK")
		out.Observation = observeTarget(ctx, p, &b)
		return out
	}
	args := []string{q.Operation, q.NativeUID}
	if q.Operation == "status" {
		args = append(args, "--type", "create")
	}
	if q.Operation == "create-cpu" {
		args = []string{"create", "cri", "cpu", "fullload", "--container-runtime", "docker", "--container-id", p.ContainerID, "--cpu-percent", strconv.Itoa(p.CPUPercent), "--cpu-count", "1", "--timeout", strconv.Itoa(p.DurationSeconds), "--uid", q.NativeUID}
	}
	if c.beforeExec != nil {
		c.beforeExec()
	}
	latest, loadErr := c.loadPolicy()
	if loadErr != nil || policyDigest(latest) != policyDigest(p) || c.verify(p) != nil {
		return result("IDENTITY_REJECTED")
	}
	return runChild(ctx, c, args, q.Operation == "create-cpu", q.NativeUID)
}
