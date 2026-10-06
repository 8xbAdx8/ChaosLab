package main

import (
	"bytes"
	"context"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

type procIdentity struct {
	pid, ppid, group int
	start            string
	state            string
}

func proc(pid int) (procIdentity, error) {
	b, e := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if e != nil {
		return procIdentity{}, e
	}
	end := strings.LastIndex(string(b), ") ")
	if end < 0 {
		return procIdentity{}, invalid
	}
	f := strings.Fields(string(b)[end+2:])
	if len(f) < 20 {
		return procIdentity{}, invalid
	}
	parent, e := strconv.Atoi(f[1])
	if e != nil {
		return procIdentity{}, e
	}
	group, e := strconv.Atoi(f[2])
	if e != nil {
		return procIdentity{}, e
	}
	return procIdentity{pid, parent, group, f[19], f[0]}, nil
}
func alive(p procIdentity) bool {
	n, e := proc(p.pid)
	return e == nil && n.start == p.start && n.state != "Z" && n.state != "X"
}
func signalKnown(p procIdentity, s syscall.Signal) {
	// Linux >=5.3 pidfd: avoid signalling a recycled PID between stat and kill.
	fd, _, e := syscall.Syscall(434, uintptr(p.pid), 0, 0)
	if e != 0 {
		return
	}
	defer syscall.Close(int(fd))
	if alive(p) {
		syscall.Syscall6(424, fd, uintptr(s), 0, 0, 0, 0)
	}
}
func discover(root int, known map[int]procIdentity) bool {
	entries, e := os.ReadDir("/proc")
	if e != nil {
		return false
	}
	all := make([]procIdentity, 0)
	for _, d := range entries {
		pid, e := strconv.Atoi(d.Name())
		if e != nil {
			continue
		}
		if len(all) >= 16384 {
			return false
		}
		p, e := proc(pid)
		if e == nil {
			all = append(all, p)
		}
	}
	for pass := 0; pass < 16; pass++ {
		added := false
		for _, p := range all {
			if _, ok := known[p.pid]; ok {
				continue
			}
			parent, parentKnown := known[p.ppid]
			parentLive := parentKnown && alive(parent)
			// The process group discovers non-setsid orphans even after root CLI exit.
			if p.group == root || parentLive {
				if len(known) >= 512 {
					return false
				}
				known[p.pid] = p
				added = true
			}
		}
		if !added {
			return true
		}
	}
	return false
}
func cleanup(root int, known map[int]procIdentity) bool {
	observable := discover(root, known)
	for _, p := range known {
		signalKnown(p, syscall.SIGTERM)
	}
	end := time.Now().Add(700 * time.Millisecond)
	for time.Now().Before(end) {
		time.Sleep(10 * time.Millisecond)
		observable = discover(root, known) && observable
		live := false
		for _, p := range known {
			if alive(p) {
				live = true
				signalKnown(p, syscall.SIGKILL)
			}
		}
		if !live {
			return observable
		}
	}
	return false
}

type pipeResult struct {
	data []byte
	err  error
}

func collect(f *os.File, total *atomic.Int64, overflow *atomic.Bool, ch chan<- pipeResult) {
	defer f.Close()
	var b bytes.Buffer
	buf := make([]byte, 4096)
	for {
		n, e := f.Read(buf)
		if n > 0 {
			if total.Add(int64(n)) > outputLimit {
				overflow.Store(true)
				ch <- pipeResult{err: invalid}
				return
			}
			b.Write(buf[:n])
		}
		if e != nil {
			if e == io.EOF {
				e = nil
			}
			ch <- pipeResult{b.Bytes(), e}
			return
		}
	}
}
func runChild(ctx context.Context, c config, args []string, handoff bool, uid string) Result {
	r := result("PROCESS_FAILED")
	if handoff {
		r.Code = "CREATE_UNCERTAIN"
	}
	if ctx.Err() != nil {
		r.Outcome = "INTERRUPTED"
		r.CleanupComplete = true
		return r
	}
	stdout, wout, e := os.Pipe()
	if e != nil {
		r.Outcome = "IO_FAILURE"
		return r
	}
	defer stdout.Close()
	stderr, werr, e := os.Pipe()
	if e != nil {
		wout.Close()
		r.Outcome = "IO_FAILURE"
		return r
	}
	defer stderr.Close()
	cmd := exec.Command(c.tool, args...)
	cmd.Dir = c.state
	cmd.Env = controlledEnv(c.state)
	cmd.Stdin = nil
	cmd.Stdout = wout
	cmd.Stderr = werr
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if e = cmd.Start(); e != nil {
		wout.Close()
		werr.Close()
		r.Outcome = "START_FAILED"
		r.CleanupComplete = true
		return r
	}
	wout.Close()
	werr.Close()
	pid := cmd.Process.Pid
	known := map[int]procIdentity{}
	if p, e := proc(pid); e == nil {
		known[pid] = p
	}
	var total atomic.Int64
	var overflow atomic.Bool
	outCh := make(chan pipeResult, 1)
	errCh := make(chan pipeResult, 1)
	go collect(stdout, &total, &overflow, outCh)
	go collect(stderr, &total, &overflow, errCh)
	wait := make(chan error, 1)
	go func() { wait <- cmd.Wait() }()
	timer := time.NewTimer(c.deadline)
	defer timer.Stop()
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	var out, errout pipeResult
	outDone, errDone, exited := false, false, false
	finish := func(reason string) Result {
		r.Outcome = reason
		r.CleanupComplete = cleanup(pid, known)
		stdout.Close()
		stderr.Close()
		if !exited {
			select {
			case <-wait:
			case <-time.After(100 * time.Millisecond):
				r.CleanupComplete = false
			}
		}
		return r
	}
	for {
		if ctx.Err() != nil {
			return finish("INTERRUPTED")
		}
		if overflow.Load() {
			return finish("OUTPUT_LIMIT")
		}
		if exited && outDone && errDone {
			if out.err != nil || errout.err != nil {
				return finish("IO_FAILURE")
			}
			if *r.ExitCode != 0 {
				return finish("NONZERO_EXIT")
			}
			if len(errout.data) != 0 || !validResponse(out.data, handoff, uid) {
				return finish("INVALID_RESPONSE")
			}
			if !discover(pid, known) {
				return finish("OBSERVATION_INCOMPLETE")
			}
			if handoff {
				r.Code = "OK"
				r.Outcome = "HANDOFF"
				r.Handoff = true
				r.NativeUID = uid
				r.Response = out.data
				return r
			}
			for _, p := range known {
				if alive(p) {
					return finish("DESCENDANTS_REMAINED")
				}
			}
			r.Code = "OK"
			r.Outcome = "EXITED"
			r.CleanupComplete = true
			r.Response = out.data
			return r
		}
		select {
		case <-ctx.Done():
			return finish("INTERRUPTED")
		case <-timer.C:
			return finish("TIMEOUT")
		case <-ticker.C:
			if !discover(pid, known) {
				return finish("OBSERVATION_INCOMPLETE")
			}
		case <-wait:
			exited = true
			code := cmd.ProcessState.ExitCode()
			r.ExitCode = &code
			wait = nil
		case out = <-outCh:
			outDone = true
			outCh = nil
		case errout = <-errCh:
			errDone = true
			errCh = nil
		}
	}
}
