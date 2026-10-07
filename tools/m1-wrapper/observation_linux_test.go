package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
	"time"
)

// Opt-in isolated root container only: no Docker socket, target, real tool or VM policy.
// Uses the existing FAKE dispatcher and the unchanged production residual probe.
func TestRootFakeDestroyedBeforeHelperSettles(t *testing.T) {
	if os.Getenv("M1_ROOT_FIXTURE") != "1" {
		t.Skip("isolated root fixture only")
	}
	if os.Geteuid() != 0 {
		t.Fatal("root fixture must actually run as root")
	}
	c, p := fixtureConfig(t)
	put(t, filepath.Join(c.state, "scenario"), []byte("settling"))
	putJSON(t, filepath.Join(c.state, "authorization.json"), bindingFor(request("create-cpu"), p))
	created := serve(context.Background(), c, request("create-cpu"))
	if created.Code != "OK" || !created.Handoff || created.CleanupComplete {
		t.Fatal(created)
	}
	h := helper(t, c)
	defer signalKnown(h, syscall.SIGKILL)
	if !alive(h) {
		t.Fatal("helper did not survive handoff")
	}
	for _, op := range []string{"destroy", "status"} {
		r := serve(context.Background(), c, request(op))
		if r.Code != "OK" || r.Handoff || !r.CleanupComplete {
			t.Fatal(r)
		}
		if op == "status" {
			var v struct{ Result struct{ Uid, Status string } }
			if json.Unmarshal(r.Response, &v) != nil || v.Result.Uid != testUID || v.Result.Status != "Destroyed" {
				t.Fatal("same UID Destroyed not confirmed")
			}
		}
	}
	group := filepath.Join(c.trustRoot, "synthetic-cgroup")
	if e := os.Mkdir(group, 0700); e != nil {
		t.Fatal(e)
	}
	put(t, filepath.Join(group, "cgroup.procs"), []byte(strconv.Itoa(os.Getpid())))
	sample := CpuSample{PID: os.Getpid(), Cgroup: group}
	if got := residual(p, sample); got != "PRESENT" {
		t.Fatal("live root helper must remain PRESENT", got)
	}
	deadline := time.Now().Add(3 * time.Second)
	for alive(h) && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if alive(h) {
		t.Fatal("harmless helper did not exit")
	}
	if got := residual(p, sample); got != "CLEAR" {
		t.Fatal("settled probe was not CLEAR", got)
	}
	t.Log("root FAKE: destroy/status Destroyed -> unchanged residual PRESENT -> natural helper exit -> CLEAR; no real fault")
}

func TestCpuProbeFailClosed(t *testing.T) {
	for _, raw := range []string{"", "user_usec 1\n", "usage_usec 1\nusage_usec 2\n", "usage_usec -1\n", "usage_usec x\n"} {
		if _, e := cpuUsage(raw); e == nil {
			t.Fatal("accepted invalid counter")
		}
	}
	if n, e := cpuUsage("usage_usec 2000\nuser_usec 1000\n"); e != nil || n != 2000 {
		t.Fatal(n, e)
	}
	a := CpuSample{PID: 123, StartTime: "22", Cgroup: "/trusted/group", UsageUsec: 10}
	b := a
	b.UsageUsec = 10010
	if p, e := cpuPercent(a, b, time.Second); e != nil || p != 1 {
		t.Fatal(p, e)
	}
	for _, change := range []func(*CpuSample){func(b *CpuSample) { b.PID++ }, func(b *CpuSample) { b.StartTime = "23" }, func(b *CpuSample) { b.Cgroup += "other" }, func(b *CpuSample) { b.UsageUsec = 9 }} {
		wrong := b
		change(&wrong)
		if _, e := cpuPercent(a, wrong, time.Second); e == nil {
			t.Fatal("wrong process/counter accepted")
		}
	}
	for _, elapsed := range []time.Duration{0, time.Millisecond, 4 * time.Second} {
		if _, e := cpuPercent(a, b, elapsed); e == nil {
			t.Fatal("unbounded/invalid sample window")
		}
	}
}
