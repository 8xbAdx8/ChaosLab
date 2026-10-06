package main

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const policyPath = "/etc/chaoslab-m1/policy.json"
const statePath = "/var/lib/chaoslab-m1/state"
const toolPath = "/opt/chaoslab/m1/api3-identified/blade-chaoslab-api3-identified"
const fakeToolPath = "/opt/chaoslab/m1-fixture/v1/fake-blade"
const fakeStatePath = "/var/lib/chaoslab-m1/fixture-state"
const maxToolBytes = 128 * 1024 * 1024

type Policy struct {
	Deployment      string `json:"deployment"`
	Executable      string `json:"executable"`
	StateDirectory  string `json:"stateDirectory"`
	ContainerID     string `json:"containerId"`
	ImageID         string `json:"imageId"`
	ContainerName   string `json:"containerName"`
	User            string `json:"user"`
	NanoCPUs        int64  `json:"nanoCpus"`
	Memory          int64  `json:"memory"`
	Pids            int64  `json:"pids"`
	CPUPercent      int    `json:"cpuPercent"`
	DurationSeconds int    `json:"durationSeconds"`
	NodeID          string `json:"nodeId"`
	StateID         string `json:"stateId"`
	ToolSHA         string `json:"toolSha256"`
	NsexecSHA       string `json:"nsexecSha256"`
	ChaosOSSHA      string `json:"chaosOsSha256"`
	YamlSHA         string `json:"yamlSha256"`
}
type config struct {
	policy, tool, state string
	// Test-only construction seam, never loaded from flags/environment/JSON.
	trustRoot   string
	owner       uint32
	deadline    time.Duration
	targetCheck func(Policy) error
	beforeExec  func()
}

func productionConfig() config {
	return config{policy: policyPath, tool: toolPath, state: statePath, owner: 0, deadline: 5 * time.Second, targetCheck: inspectTarget}
}

// Only root-owned policy selects a slot. Requests and environment cannot select paths.
func (c config) selectDeployment(p Policy) (config, error) {
	if p.Deployment != "REAL" && p.Deployment != "FAKE" {
		return c, invalid
	}
	if c.trustRoot == "" {
		c.tool, c.state = toolPath, statePath
		if p.Deployment == "FAKE" {
			c.tool, c.state = fakeToolPath, fakeStatePath
		}
	}
	if p.Executable != c.tool || p.StateDirectory != c.state {
		return c, invalid
	}
	return c, nil
}

func (c config) trusted(path string, directory bool) error {
	abs, e := filepath.Abs(path)
	if e != nil || abs != filepath.Clean(path) {
		return invalid
	}
	root := filepath.VolumeName(abs) + string(os.PathSeparator)
	if c.trustRoot != "" {
		root = c.trustRoot
		rel, e := filepath.Rel(root, abs)
		if e != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
			return invalid
		}
	}
	for p := abs; ; p = filepath.Dir(p) {
		st, e := os.Lstat(p)
		if e != nil || st.Mode()&os.ModeSymlink != 0 {
			return invalid
		}
		if p == abs {
			if directory != st.IsDir() || (!directory && !st.Mode().IsRegular()) {
				return invalid
			}
		} else if !st.IsDir() {
			return invalid
		}
		if checkOwnerMode(p, st, c.owner) != nil {
			return invalid
		}
		if p == root {
			break
		}
		if filepath.Dir(p) == p {
			return invalid
		}
	}
	return nil
}
func (c config) read(path string, limit int) ([]byte, error) {
	if c.trusted(path, false) != nil {
		return nil, invalid
	}
	f, e := os.Open(path)
	if e != nil {
		return nil, invalid
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, int64(limit)+1))
	if e != nil || len(b) > limit {
		return nil, invalid
	}
	return b, nil
}
func (c config) loadPolicy() (Policy, error) {
	var p Policy
	b, e := c.read(c.policy, 16384)
	if e != nil || strictJSON(b, &p) != nil {
		return p, invalid
	}
	if !hashPattern.MatchString(p.ContainerID) || !strings.HasPrefix(p.ImageID, "sha256:") || !hashPattern.MatchString(strings.TrimPrefix(p.ImageID, "sha256:")) || p.ContainerName != "chaoslab-cpu-sandbox" || p.User != "65534:65534" || p.NanoCPUs != 500000000 || p.Memory != 134217728 || p.Pids != 32 || p.CPUPercent < 10 || p.CPUPercent > 40 || p.DurationSeconds < 1 || p.DurationSeconds > 30 || !label(p.NodeID) || !label(p.StateID) {
		return p, invalid
	}
	for _, s := range []string{p.ToolSHA, p.NsexecSHA, p.ChaosOSSHA, p.YamlSHA} {
		if !hashPattern.MatchString(s) {
			return p, invalid
		}
	}
	return p, nil
}
func label(s string) bool {
	if len(s) < 1 || len(s) > 64 {
		return false
	}
	for _, r := range s {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-') {
			return false
		}
	}
	return true
}
func (c config) verify(p Policy) error {
	if c.trusted(c.state, true) != nil {
		return invalid
	}
	marker, e := c.read(filepath.Join(c.state, ".chaoslab-state-id"), 128)
	if e != nil || string(marker) != p.StateID+"\n" {
		return invalid
	}
	node, e := c.read(filepath.Join(filepath.Dir(c.policy), "node-id"), 128)
	if e != nil || string(node) != p.NodeID+"\n" {
		return invalid
	}
	dir := filepath.Dir(c.tool)
	for path, hash := range map[string]string{c.tool: p.ToolSHA, filepath.Join(dir, "bin/nsexec"): p.NsexecSHA, filepath.Join(dir, "bin/chaos_os"): p.ChaosOSSHA, filepath.Join(dir, "yaml/chaosblade-cri-spec-1.8.1.yaml"): p.YamlSHA} {
		if c.trusted(path, false) != nil {
			return invalid
		}
		if !strings.HasSuffix(path, ".yaml") {
			st, e := os.Stat(path)
			if e != nil || checkExecutable(st) != nil {
				return invalid
			}
		}
		f, e := os.Open(path)
		if e != nil {
			return invalid
		}
		h := sha256.New()
		n, e := io.Copy(h, io.LimitReader(f, maxToolBytes+1))
		f.Close()
		if e != nil || n > maxToolBytes || hex.EncodeToString(h.Sum(nil)) != hash {
			return invalid
		}
	}
	return nil
}
