package main

import (
	"testing"
	"time"
)

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
