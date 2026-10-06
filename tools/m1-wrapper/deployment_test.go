package main

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func TestPreflightAttestsOnlyVerifiedDeployment(t *testing.T) {
	c, p := fixtureConfig(t)
	r := serve(context.Background(), c, Request{Operation: "preflight"})
	if r.Code != "OK" || r.Policy == nil || *r.Policy != p || r.PolicyDigest != policyDigest(p) || r.CleanupComplete || r.Handoff {
		t.Fatal("missing/misleading deployment attestation")
	}
	c.targetCheck = func(Policy) error { return invalid }
	r = serve(context.Background(), c, Request{Operation: "preflight"})
	if r.Code != "TARGET_UNKNOWN" || r.Policy != nil || r.PolicyDigest != "" {
		t.Fatal("attested an unverified target")
	}
}

func TestFixedDeploymentSlots(t *testing.T) {
	for _, d := range []string{"REAL", "FAKE"} {
		p := Policy{Deployment: d, Executable: toolPath, StateDirectory: statePath}
		if d == "FAKE" {
			p.Executable, p.StateDirectory = fakeToolPath, fakeStatePath
		}
		got, err := productionConfig().selectDeployment(p)
		if err != nil || got.tool != p.Executable || got.state != p.StateDirectory {
			t.Fatal(d, err)
		}
		for _, path := range []string{"/tmp/blade", p.Executable + "/../blade", "blade"} {
			bad := p
			bad.Executable = path
			if _, err := productionConfig().selectDeployment(bad); err == nil {
				t.Fatal("arbitrary executable")
			}
		}
		bad := p
		bad.Deployment = "UNKNOWN"
		if _, err := productionConfig().selectDeployment(bad); err == nil {
			t.Fatal("unknown slot")
		}
	}
}

func TestAuthorizationCompleteIdentityAndExpiry(t *testing.T) {
	c, p := fixtureConfig(t)
	q := request("create-cpu")
	good := bindingFor(q, p)
	mutations := map[string]func(*Binding){
		"deployment":    func(b *Binding) { b.Deployment = "REAL" },
		"path":          func(b *Binding) { b.Executable = toolPath },
		"policy":        func(b *Binding) { b.PolicyDigest = "wrong" },
		"tool":          func(b *Binding) { b.ToolSHA = "wrong" },
		"nsexec":        func(b *Binding) { b.NsexecSHA = "wrong" },
		"chaosOs":       func(b *Binding) { b.ChaosOSSHA = "wrong" },
		"yaml":          func(b *Binding) { b.YamlSHA = "wrong" },
		"expired":       func(b *Binding) { b.ExpiresAt = time.Now().Add(-time.Second) },
		"missingExpiry": func(b *Binding) { b.ExpiresAt = time.Time{} },
		"longTTL":       func(b *Binding) { b.ExpiresAt = b.CreatedAt.Add(31 * time.Second) },
		"future":        func(b *Binding) { b.CreatedAt = time.Now().Add(time.Second) },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			b := good
			mutate(&b)
			putJSON(t, filepath.Join(c.state, "authorization.json"), b)
			if c.authorize(q, p) == nil {
				t.Fatal("invalid authorization accepted")
			}
		})
	}
	// Policy limits/state/path changes are covered even if original tool bytes match.
	for _, mutate := range []func(*Policy){
		func(p *Policy) { p.CPUPercent++ }, func(p *Policy) { p.DurationSeconds++ },
		func(p *Policy) { p.StateDirectory += "-other" }, func(p *Policy) { p.NodeID += "-other" },
	} {
		changed := p
		mutate(&changed)
		if good.matches(q, changed) {
			t.Fatal("changed policy matched")
		}
	}
}

func TestCrossDeploymentAuthorizationRejected(t *testing.T) {
	for _, from := range []string{"FAKE", "REAL"} {
		t.Run(from, func(t *testing.T) {
			c, p := fixtureConfig(t)
			p.Deployment = from
			q := request("create-cpu")
			b := bindingFor(q, p)
			if from == "FAKE" {
				p.Deployment = "REAL"
			} else {
				p.Deployment = "FAKE"
			}
			// Same test executable bytes/path: deployment identity alone must still isolate.
			putJSON(t, filepath.Join(c.state, "authorization.json"), b)
			if c.authorize(q, p) == nil {
				t.Fatal("cross-deployment authorization accepted")
			}
		})
	}
}
