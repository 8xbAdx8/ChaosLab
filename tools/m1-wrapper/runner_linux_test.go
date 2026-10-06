package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func helper(t *testing.T, c config) procIdentity {
	t.Helper()
	b, e := os.ReadFile(filepath.Join(c.state, "helper.pid"))
	if e != nil {
		t.Fatal(e)
	}
	pid, e := strconv.Atoi(string(b))
	if e != nil {
		t.Fatal(e)
	}
	p, e := proc(pid)
	if e != nil {
		return procIdentity{pid: pid}
	}
	return p
}
func TestLifecycleFixtures(t *testing.T) {
	t.Setenv("PATH", "/evil-path")
	t.Setenv("LD_PRELOAD", "/evil-library")
	cases := []struct {
		mode, operation, outcome string
		live                     bool
	}{
		{"normal", "status", "EXITED", false}, {"detached-helper", "status", "DESCENDANTS_REMAINED", false},
		{"detached-helper", "destroy", "DESCENDANTS_REMAINED", false},
		{"detached-helper", "create-cpu", "HANDOFF", true}, {"timeout-helper", "create-cpu", "HANDOFF", true},
		{"inherited-helper", "create-cpu", "TIMEOUT", false}, {"timeout", "create-cpu", "TIMEOUT", false},
		{"overflow", "create-cpu", "OUTPUT_LIMIT", false}, {"nonzero", "create-cpu", "NONZERO_EXIT", false},
		{"malformed", "create-cpu", "INVALID_RESPONSE", false}, {"different-uid", "create-cpu", "INVALID_RESPONSE", false},
	}
	for _, tc := range cases {
		t.Run(tc.operation+"/"+tc.mode, func(t *testing.T) {
			c, p := fixtureConfig(t)
			q := request(tc.operation)
			put(t, filepath.Join(c.state, "scenario"), []byte(tc.mode))
			if tc.operation == "create-cpu" {
				putJSON(t, filepath.Join(c.state, "authorization.json"), bindingFor(q, p))
			} else {
				putJSON(t, filepath.Join(c.state, "binding.json"), bindingFor(q, p))
			}
			start := time.Now()
			r := serve(context.Background(), c, q)
			if r.Outcome != tc.outcome {
				t.Fatalf("%+v", r)
			}
			if time.Since(start) > 3*time.Second {
				t.Fatal("unbounded")
			}
			if tc.mode != "normal" {
				h := helper(t, c)
				defer signalKnown(h, syscall.SIGKILL)
				if alive(h) != tc.live {
					t.Fatalf("helper alive=%v expected=%v", alive(h), tc.live)
				}
			}
			if r.Handoff != tc.live || r.CleanupComplete == tc.live {
				t.Fatal("wrong lifecycle semantics", r)
			}
			serialized, _ := json.Marshal(r)
			if strings.Contains(string(serialized), "SECRET_SHOULD_NOT_LEAK") {
				t.Fatal("leak")
			}
			invocation, _ := os.ReadFile(filepath.Join(c.state, "invocation.json"))
			if strings.Contains(string(invocation), "evil-") || strings.Contains(string(invocation), "LD_PRELOAD") {
				t.Fatal("child inherited hostile environment")
			}
			if tc.operation == "create-cpu" {
				if r2 := serve(context.Background(), c, q); r2.Code != "CREATE_DISABLED" {
					t.Fatal("create replay", r2)
				}
				raw, _ := os.ReadFile(filepath.Join(c.state, "invocation.json"))
				if !strings.Contains(string(raw), `"binding"`) || !strings.Contains(string(raw), `"--uid","`+testUID+`"`) {
					t.Fatal("binding not durable before fake entry", string(raw))
				}
			}
		})
	}
}

func TestCancellationCleansKnownHelper(t *testing.T) {
	c, p := fixtureConfig(t)
	c.deadline = 3 * time.Second
	q := request("create-cpu")
	putJSON(t, filepath.Join(c.state, "authorization.json"), bindingFor(q, p))
	put(t, filepath.Join(c.state, "scenario"), []byte("signal"))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { time.Sleep(300 * time.Millisecond); cancel() }()
	r := serve(ctx, c, q)
	if r.Outcome != "INTERRUPTED" || !r.CleanupComplete {
		t.Fatal(r)
	}
	h := helper(t, c)
	defer signalKnown(h, syscall.SIGKILL)
	if alive(h) {
		t.Fatal("helper survived")
	}
}
func TestStartFailed(t *testing.T) {
	c, _ := fixtureConfig(t)
	c.tool = filepath.Join(c.trustRoot, "missing")
	r := runChild(context.Background(), c, []string{"status", testUID}, false, testUID)
	if r.Outcome != "START_FAILED" || !r.CleanupComplete {
		t.Fatal(r)
	}
}

func TestFilesystemAttacks(t *testing.T) {
	for _, attack := range []string{"policy symlink", "tool symlink", "writable parent", "wrong owner"} {
		t.Run(attack, func(t *testing.T) {
			c, p := fixtureConfig(t)
			switch attack {
			case "policy symlink":
				old := c.policy
				c.policy = filepath.Join(filepath.Dir(old), "link")
				if e := os.Symlink(old, c.policy); e != nil {
					t.Fatal(e)
				}
			case "tool symlink":
				old := c.tool
				c.tool = filepath.Join(filepath.Dir(old), "link")
				if e := os.Symlink(old, c.tool); e != nil {
					t.Fatal(e)
				}
			case "writable parent":
				if e := os.Chmod(filepath.Dir(c.tool), 0777); e != nil {
					t.Fatal(e)
				}
			case "wrong owner":
				c.owner = uint32(os.Getuid() + 1)
			}
			if attack == "policy symlink" {
				if _, e := c.loadPolicy(); e == nil {
					t.Fatal("accepted")
				}
			} else if c.verify(p) == nil {
				t.Fatal("accepted")
			}
		})
	}
}

func TestNonRootProductionEntry(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Fatal("Tests MUST NOT run as root")
	}
	if productionAllowed() {
		t.Fatal("production bypass")
	}
}

func TestActualSIGTERMCleansRootSideFixture(t *testing.T) {
	c, p := fixtureConfig(t)
	q := request("create-cpu")
	putJSON(t, filepath.Join(c.state, "authorization.json"), bindingFor(q, p))
	put(t, filepath.Join(c.state, "scenario"), []byte("signal"))
	exe, _ := os.Executable()
	cmd := exec.Command(exe, "fixture-wrapper", c.trustRoot)
	b, _ := json.Marshal(q)
	cmd.Stdin = bytes.NewReader(b)
	var output bytes.Buffer
	cmd.Stdout = &output
	if e := cmd.Start(); e != nil {
		t.Fatal(e)
	}
	defer cmd.Process.Kill()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, e := os.Stat(filepath.Join(c.state, "helper.pid")); e == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	h := helper(t, c)
	defer signalKnown(h, syscall.SIGKILL)
	time.Sleep(30 * time.Millisecond)
	cmd.Process.Signal(syscall.SIGTERM)
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case e := <-done:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("unbounded signal handling")
	}
	var r Result
	if json.Unmarshal(output.Bytes(), &r) != nil || r.Outcome != "INTERRUPTED" || !r.CleanupComplete || alive(h) {
		t.Fatal(output.String())
	}
}

func TestRandomUIDPersistedBeforeDispatchAndTimer(t *testing.T) {
	c, p := fixtureConfig(t)
	q := request("create-cpu")
	u, e := newNativeUID()
	if e != nil {
		t.Fatal(e)
	}
	q.NativeUID = u
	// Simulates platform commit before invoking wrapper. No Java claim is made.
	if e = writeExclusive(filepath.Join(c.state, "platform-intent.json"), q); e != nil {
		t.Fatal(e)
	}
	putJSON(t, filepath.Join(c.state, "authorization.json"), bindingFor(q, p))
	put(t, filepath.Join(c.state, "scenario"), []byte("timeout-helper"))
	r := serve(context.Background(), c, q)
	if r.Outcome != "HANDOFF" || r.NativeUID != u {
		t.Fatal(r)
	}
	h := helper(t, c)
	defer signalKnown(h, syscall.SIGKILL)
	deadline := time.Now().Add(2 * time.Second)
	var timer []byte
	for time.Now().Before(deadline) {
		timer, e = os.ReadFile(filepath.Join(c.state, "timer.json"))
		if e == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	var got struct {
		Tool  string
		State string
		UID   string
		EUID  int
	}
	if json.Unmarshal(timer, &got) != nil || got.Tool != c.tool || got.State != c.state || got.UID != u || got.EUID != os.Geteuid() {
		t.Fatal(string(timer))
	}
}
