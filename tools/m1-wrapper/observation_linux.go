package main

import (
	"context"
	"encoding/json"
	"io"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func boundedRead(path string, limit int) ([]byte, error) {
	f, e := os.Open(path)
	if e != nil {
		return nil, e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, int64(limit)+1))
	if e != nil || len(b) > limit {
		return nil, invalid
	}
	return b, nil
}
func processStart(raw []byte) (string, error) {
	i := strings.LastIndex(string(raw), ") ")
	if i < 0 {
		return "", invalid
	}
	f := strings.Fields(string(raw)[i+2:])
	if len(f) < 20 || f[0] == "Z" || f[0] == "X" {
		return "", invalid
	}
	if _, e := strconv.ParseUint(f[19], 10, 64); e != nil {
		return "", invalid
	}
	return f[19], nil
}
func targetSample(p Policy) (CpuSample, error) {
	var sample CpuSample
	raw, e := readTarget(p)
	if e != nil {
		return sample, e
	}
	var v struct{ State struct{ Pid int } }
	if json.Unmarshal(raw, &v) != nil || v.State.Pid <= 0 {
		return sample, invalid
	}
	pid := v.State.Pid
	proc := filepath.Join("/proc", strconv.Itoa(pid))
	stat, e := boundedRead(filepath.Join(proc, "stat"), 4096)
	if e != nil {
		return sample, invalid
	}
	start, e := processStart(stat)
	if e != nil {
		return sample, invalid
	}
	cmd, e := boundedRead(filepath.Join(proc, "cmdline"), 4096)
	if e != nil || string(cmd) != "sleep\x003600\x00" {
		return sample, invalid
	}
	raw, e = boundedRead(filepath.Join(proc, "cgroup"), 4096)
	if e != nil {
		return sample, invalid
	}
	group, e := targetCgroup(string(raw))
	if e != nil {
		return sample, e
	}
	raw, e = boundedRead(filepath.Join(group, "cpu.stat"), 4096)
	if e != nil {
		return sample, invalid
	}
	usage, e := cpuUsage(string(raw))
	if e != nil {
		return sample, e
	}
	again, e := boundedRead(filepath.Join(proc, "stat"), 4096)
	if e != nil {
		return sample, invalid
	}
	current, e := processStart(again)
	if e != nil || current != start {
		return sample, invalid
	}
	return CpuSample{PID: pid, StartTime: start, Cgroup: group, UsageUsec: usage}, nil
}
func targetCgroup(raw string) (string, error) {
	if !strings.HasPrefix(raw, "0::/") || strings.Count(raw, "\n") != 1 {
		return "", invalid
	}
	rel := strings.TrimSuffix(strings.TrimPrefix(raw, "0::"), "\n")
	if rel == "/" || filepath.Clean(rel) != rel {
		return "", invalid
	}
	group := "/sys/fs/cgroup" + rel
	resolved, e := filepath.EvalSymlinks(group)
	if e != nil || resolved != group {
		return "", invalid
	}
	return group, nil
}
func cpuUsage(raw string) (uint64, error) {
	var usage uint64
	found := false
	for _, line := range strings.Split(strings.TrimSpace(raw), "\n") {
		f := strings.Fields(line)
		if len(f) != 2 {
			return 0, invalid
		}
		if f[0] == "usage_usec" {
			if found {
				return 0, invalid
			}
			var e error
			usage, e = strconv.ParseUint(f[1], 10, 64)
			if e != nil {
				return 0, invalid
			}
			found = true
		}
	}
	if !found {
		return 0, invalid
	}
	return usage, nil
}
func sameProcess(a, b CpuSample) bool {
	return a.PID == b.PID && a.StartTime == b.StartTime && a.Cgroup == b.Cgroup
}
func cpuPercent(a, b CpuSample, elapsed time.Duration) (float64, error) {
	if !sameProcess(a, b) || b.UsageUsec < a.UsageUsec || elapsed < 500*time.Millisecond || elapsed > 3*time.Second {
		return 0, invalid
	}
	p := float64(b.UsageUsec-a.UsageUsec) * 100 / float64(elapsed.Microseconds())
	if math.IsNaN(p) || math.IsInf(p, 0) || p < 0 || p > 1000 {
		return 0, invalid
	}
	return p, nil
}
func collectBaseline(p Policy) (*CpuBaseline, error) {
	a, e := targetSample(p)
	if e != nil {
		return nil, e
	}
	start := time.Now()
	time.Sleep(time.Second)
	b, e := targetSample(p)
	if e != nil {
		return nil, e
	}
	percent, e := cpuPercent(a, b, time.Since(start))
	if e != nil || percent > 1 {
		return nil, invalid
	}
	return &CpuBaseline{Sample: b, Percent: percent, ObservedAt: time.Now().UTC()}, nil
}

// Conservative scope: the target cgroup (including nested groups), pinned tool
// processes anywhere on the dedicated node, and the reviewed timeout shell.
// Any visibility/race failure is UNKNOWN; process-group cleanup is never CLEAR.
func residual(p Policy, s CpuSample) string {
	entries, e := os.ReadDir(s.Cgroup)
	if e != nil {
		return "UNKNOWN"
	}
	for _, entry := range entries {
		if entry.IsDir() {
			return "UNKNOWN"
		}
	}
	raw, e := boundedRead(filepath.Join(s.Cgroup, "cgroup.procs"), 65536)
	if e != nil {
		return "UNKNOWN"
	}
	members := strings.Fields(string(raw))
	if len(members) != 1 || members[0] != strconv.Itoa(s.PID) {
		return "PRESENT"
	}
	entries, e = os.ReadDir("/proc")
	if e != nil || len(entries) > 8192 {
		return "UNKNOWN"
	}
	dir := filepath.Dir(p.Executable)
	for _, entry := range entries {
		if _, e := strconv.Atoi(entry.Name()); e != nil {
			continue
		}
		proc := filepath.Join("/proc", entry.Name())
		exe, e := os.Readlink(filepath.Join(proc, "exe"))
		if os.IsNotExist(e) {
			continue
		}
		if e != nil {
			return "UNKNOWN"
		}
		for _, tool := range []string{p.Executable, filepath.Join(dir, "bin/nsexec"), filepath.Join(dir, "bin/chaos_os")} {
			if strings.TrimSuffix(exe, " (deleted)") == tool {
				return "PRESENT"
			}
		}
		// Conservative orphan timer coverage. Unrelated root sleeps may cause a
		// false PRESENT, but cannot become an invented CLEAR after a shell dies.
		if (exe == "/usr/bin/sleep" || exe == "/bin/sleep") && entry.Name() != strconv.Itoa(s.PID) {
			status, e := boundedRead(filepath.Join(proc, "status"), 16384)
			if os.IsNotExist(e) {
				continue
			}
			if e != nil {
				return "UNKNOWN"
			}
			for _, line := range strings.Split(string(status), "\n") {
				if strings.HasPrefix(line, "Uid:") {
					fields := strings.Fields(line)
					if len(fields) != 5 {
						return "UNKNOWN"
					}
					if fields[1] == "0" {
						return "PRESENT"
					}
				}
			}
		}
		if exe == "/usr/bin/dash" || exe == "/bin/dash" || exe == "/usr/bin/bash" || exe == "/bin/bash" || exe == "/bin/sh" || exe == "/usr/bin/sh" {
			cmd, e := boundedRead(filepath.Join(proc, "cmdline"), 16384)
			if os.IsNotExist(e) {
				continue
			}
			if e != nil {
				return "UNKNOWN"
			}
			args := strings.Split(string(cmd), "\x00")
			marker, tool := false, false
			for _, arg := range args {
				if arg == "chaoslab-recovery" || arg == "chaoslab-launch" {
					marker = true
				}
				if arg == p.Executable {
					tool = true
				}
			}
			if marker && tool {
				return "PRESENT"
			}
		}
	}
	return "CLEAR"
}
func observeTarget(ctx context.Context, p Policy, binding *Binding) *Observation {
	o := &Observation{NodeID: p.NodeID, ContainerID: p.ContainerID, ImageID: p.ImageID, Residual: "UNKNOWN", Health: "UNKNOWN"}
	if binding != nil {
		o.ExecutionID, o.NativeUID = binding.ExecutionID, binding.NativeUID
	}
	defer func() { o.ObservedAt = time.Now().UTC() }()
	a, e := targetSample(p)
	if e != nil {
		return o
	}
	start := time.Now()
	select {
	case <-ctx.Done():
		return o
	case <-time.After(time.Second):
	}
	b, e := targetSample(p)
	if e != nil {
		return o
	}
	percent, e := cpuPercent(a, b, time.Since(start))
	if e != nil {
		return o
	}
	o.CPUPercent, o.Sample = &percent, &b
	o.Residual = residual(p, b)
	o.ProbeReady = o.Residual != "UNKNOWN"
	if binding == nil {
		return o
	} // Readiness/baseline, never RecoveryVerified.
	base := binding.Baseline
	if base == nil || base.ObservedAt.IsZero() || base.ObservedAt.After(binding.CreatedAt) || base.Percent < 0 || base.Percent > 1 || !sameProcess(base.Sample, b) {
		return o
	}
	o.BaselinePercent = &base.Percent
	if percent <= base.Percent+1 {
		o.Health = "HEALTHY"
	} else {
		o.Health = "UNHEALTHY"
	}
	return o
}
