package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestAuthorizationAndBindings(t *testing.T) {
	c, p := fixtureConfig(t)
	q := request("create-cpu")
	if r := serve(context.Background(), c, q); r.Code != "CREATE_DISABLED" {
		t.Fatal(r)
	}
	if _, e := os.Stat(filepath.Join(c.state, "invocation.json")); !os.IsNotExist(e) {
		t.Fatal("unexpected dispatch")
	}
	for _, op := range []string{"status", "destroy", "observe"} {
		if r := serve(context.Background(), c, request(op)); r.Code != "BINDING_REJECTED" {
			t.Fatal(r)
		}
	}
	b := bindingFor(q, p)
	putJSON(t, filepath.Join(c.state, "authorization.json"), b)
	wrong := q
	wrong.NativeUID = "fedcba9876543210"
	if e := c.authorize(wrong, p); e == nil {
		t.Fatal("wrong UID authorized")
	}
	wrong = q
	wrong.ExecutionID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	if e := c.authorize(wrong, p); e == nil {
		t.Fatal("wrong execution authorized")
	}
	if e := c.authorize(q, p); e != nil {
		t.Fatal(e)
	}
	got, e := c.binding("binding.json")
	if e != nil || !got.matches(q, p) {
		t.Fatal(got, e)
	}
	putJSON(t, filepath.Join(c.state, "authorization.json"), b)
	if e := c.authorize(q, p); e == nil {
		t.Fatal("replayed")
	}
	wrong.Operation = "destroy"
	if r := serve(context.Background(), c, wrong); r.Code != "BINDING_REJECTED" {
		t.Fatal(r)
	}
	if r := serve(context.Background(), c, request("observe")); r.Code != "OBSERVATION_UNKNOWN" {
		t.Fatal(r)
	}
	for _, op := range []string{"status", "destroy"} {
		bad := request(op)
		bad.NativeUID = "fedcba9876543210"
		if r := serve(context.Background(), c, bad); r.Code != "BINDING_REJECTED" {
			t.Fatal("different UID accepted", r)
		}
	}
}
func TestExpiredAuthorizationAndChangedPolicy(t *testing.T) {
	c, p := fixtureConfig(t)
	q := request("create-cpu")
	b := bindingFor(q, p)
	b.CreatedAt = time.Now().Add(-10 * time.Minute)
	putJSON(t, filepath.Join(c.state, "authorization.json"), b)
	if c.authorize(q, p) == nil {
		t.Fatal("expired")
	}
	putJSON(t, filepath.Join(c.state, "binding.json"), bindingFor(q, p))
	p.ImageID = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	putJSON(t, c.policy, p)
	if r := serve(context.Background(), c, request("destroy")); r.Code != "BINDING_REJECTED" {
		t.Fatal(r)
	}
}
func TestReplacementImmediatelyBeforeExec(t *testing.T) {
	c, p := fixtureConfig(t)
	putJSON(t, filepath.Join(c.state, "binding.json"), bindingFor(request("status"), p))
	c.beforeExec = func() { put(t, c.tool, []byte("changed")) }
	if r := serve(context.Background(), c, request("status")); r.Code != "IDENTITY_REJECTED" {
		t.Fatal(r)
	}
}

func TestExistingNativeStateAndStaleLockRefuseCreate(t *testing.T) {
	c, p := fixtureConfig(t)
	q := request("create-cpu")
	putJSON(t, filepath.Join(c.state, "authorization.json"), bindingFor(q, p))
	put(t, filepath.Join(c.state, "chaosblade.dat"), []byte("existing"))
	if r := serve(context.Background(), c, q); r.Code != "CREATE_DISABLED" {
		t.Fatal(r)
	}
	put(t, filepath.Join(c.state, "operation.lock"), nil)
	if r := serve(context.Background(), c, q); r.Code != "BUSY_OR_STALE_LOCK" {
		t.Fatal(r)
	}
}
