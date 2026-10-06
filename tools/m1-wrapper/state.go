package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

type Binding struct {
	Deployment    string    `json:"deployment"`
	Executable    string    `json:"executable"`
	PolicyDigest  string    `json:"policyDigest"`
	ToolSHA       string    `json:"toolSha256"`
	NsexecSHA     string    `json:"nsexecSha256"`
	ChaosOSSHA    string    `json:"chaosOsSha256"`
	YamlSHA       string    `json:"yamlSha256"`
	ExpiresAt     time.Time `json:"expiresAt"`
	ExecutionID   string    `json:"executionId"`
	NativeUID     string    `json:"nativeUid"`
	ContainerID   string    `json:"containerId"`
	ImageID       string    `json:"imageId"`
	ToolIdentity  string    `json:"toolIdentity"`
	StateIdentity string    `json:"stateIdentity"`
	NodeID        string    `json:"nodeId"`
	CreatedAt     time.Time `json:"createdAt"`
}

func bindingFor(q Request, p Policy) Binding {
	now := time.Now().UTC()
	return Binding{ExecutionID: q.ExecutionID, NativeUID: q.NativeUID, ContainerID: p.ContainerID,
		ImageID: p.ImageID, ToolIdentity: toolIdentity(p), StateIdentity: p.StateID, NodeID: p.NodeID,
		CreatedAt: now, ExpiresAt: now.Add(30 * time.Second), Deployment: p.Deployment,
		Executable: p.Executable, PolicyDigest: policyDigest(p), ToolSHA: p.ToolSHA,
		NsexecSHA: p.NsexecSHA, ChaosOSSHA: p.ChaosOSSHA, YamlSHA: p.YamlSHA}
}

// Digest covers every typed policy field, in Go JSON field order, without whitespace.
// Missing/unknown deployment fields never receive legacy fallback semantics.
func policyDigest(p Policy) string {
	raw, _ := json.Marshal(p)
	return fmt.Sprintf("%x", sha256.Sum256(raw))
}
func toolIdentity(p Policy) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(p.ToolSHA+":"+p.NsexecSHA+":"+p.ChaosOSSHA+":"+p.YamlSHA)))
}
func (b Binding) matches(q Request, p Policy) bool {
	if b.Deployment != p.Deployment || b.Executable != p.Executable || b.PolicyDigest != policyDigest(p) ||
		b.ToolSHA != p.ToolSHA || b.NsexecSHA != p.NsexecSHA || b.ChaosOSSHA != p.ChaosOSSHA || b.YamlSHA != p.YamlSHA {
		return false
	}
	return b.ExecutionID == q.ExecutionID && b.NativeUID == q.NativeUID && b.ContainerID == p.ContainerID && b.ImageID == p.ImageID && b.ToolIdentity == toolIdentity(p) && b.StateIdentity == p.StateID && b.NodeID == p.NodeID && !b.CreatedAt.IsZero() && !b.CreatedAt.After(time.Now().UTC())
}
func (c config) binding(name string) (Binding, error) {
	var b Binding
	raw, e := c.read(filepath.Join(c.state, name), 4096)
	if e != nil || strictJSON(raw, &b) != nil {
		return b, invalid
	}
	return b, nil
}

// Exclusive creation: no overwrite or replay. Any partially persisted file
// blocks subsequent dispatch and requires administrator review.
func writeExclusive(path string, v any) error {
	raw, e := json.Marshal(v)
	if e != nil {
		return e
	}
	f, e := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return e
	}
	_, e = f.Write(raw)
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e != nil {
		return e
	}
	if closeErr != nil {
		return closeErr
	}
	return syncDirectory(filepath.Dir(path))
}
func (c config) lock() (func(), error) {
	path := filepath.Join(c.state, "operation.lock")
	f, e := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return nil, invalid
	}
	f.Close()
	return func() { os.Remove(path) }, nil
}

func (c config) authorize(q Request, p Policy) error {
	if _, e := c.selectDeployment(p); e != nil {
		return invalid
	}
	// Single-shot fresh state prevents adopting a coincidentally equal native
	// UID from a previous experiment. Root must not run tools out of band.
	for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
		if _, e := os.Lstat(filepath.Join(c.state, "chaosblade.dat"+suffix)); !os.IsNotExist(e) {
			return invalid
		}
	}
	a, e := c.binding("authorization.json")
	if e != nil || !a.matches(q, p) {
		return invalid
	}
	// FAKE expires within 30 seconds; REAL keeps the prior five-minute upper bound.
	// Recovery binding matching does not depend on expiry: expiry gates create only.
	maxTTL := 5 * time.Minute
	if p.Deployment == "FAKE" {
		maxTTL = 30 * time.Second
	}
	now := time.Now().UTC()
	if a.ExpiresAt.IsZero() || !a.ExpiresAt.After(now) || !a.ExpiresAt.After(a.CreatedAt) || a.ExpiresAt.Sub(a.CreatedAt) > maxTTL || a.CreatedAt.After(now) {
		return invalid
	}
	b := bindingFor(q, p)
	if writeExclusive(filepath.Join(c.state, "binding.json"), b) != nil {
		return invalid
	}
	// Binding already persisted: even failure before removal cannot authorize a
	// retry because O_EXCL fails. This is a pre-dispatch binding, not a receipt.
	if os.Remove(filepath.Join(c.state, "authorization.json")) != nil {
		return invalid
	}
	return syncDirectory(c.state)
}
