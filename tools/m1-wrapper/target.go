package main

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"time"
)

// Fixed Unix socket, fixed read-only endpoint, no proxy or caller URL. Only
// fields required by the allowlist are decoded; unrelated Docker fields are
// deliberately ignored. No raw inspect data is sent to stdout/stderr.
func inspectTarget(p Policy) error {
	tr := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{Timeout: time.Second}).DialContext(ctx, "unix", "/var/run/docker.sock")
	}}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: 2 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return invalid }}
	resp, e := client.Get("http://docker/containers/" + p.ContainerID + "/json")
	if e != nil {
		return invalid
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return invalid
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, 65537))
	if e != nil || len(b) > 65536 {
		return invalid
	}
	return validateInspect(b, p)
}
func validateInspect(b []byte, p Policy) error {
	// Validate duplicate keys and UTF-8 even though Docker adds unrelated fields.
	var generic any
	if strictJSON(b, &generic) != nil {
		return invalid
	}
	obj, ok := generic.(map[string]any)
	if !ok {
		return invalid
	}
	if !hasKeys(obj, "Id", "Image", "Name", "State", "Config", "Mounts", "HostConfig") || !hasKeys(obj["State"], "Running", "Paused", "Restarting", "Dead") || !hasKeys(obj["Config"], "User") || !hasKeys(obj["HostConfig"], "NetworkMode", "Privileged", "ReadonlyRootfs", "CapAdd", "CapDrop", "Binds", "Devices", "PidMode", "IpcMode", "CgroupnsMode", "NanoCPUs", "Memory", "PidsLimit", "RestartPolicy") {
		return invalid
	}
	var v struct {
		ID    string `json:"Id"`
		Image string
		Name  string
		State struct {
			Running    bool
			Paused     bool
			Restarting bool
			Dead       bool
		}
		Config     struct{ User string }
		Mounts     []json.RawMessage
		HostConfig struct {
			NetworkMode    string
			Privileged     bool
			ReadonlyRootfs bool
			CapAdd         []string
			CapDrop        []string
			Binds          []string
			Devices        []json.RawMessage
			PidMode        string
			IpcMode        string
			CgroupnsMode   string
			NanoCPUs       int64
			Memory         int64
			PidsLimit      int64
			RestartPolicy  struct{ Name string }
		}
	}
	if json.Unmarshal(b, &v) != nil {
		return invalid
	}
	h := v.HostConfig
	if v.ID != p.ContainerID || v.Image != p.ImageID || v.Name != "/"+p.ContainerName || !v.State.Running || v.State.Paused || v.State.Restarting || v.State.Dead || v.Config.User != p.User || len(v.Mounts) != 0 || len(h.Binds) != 0 || len(h.Devices) != 0 || h.NetworkMode != "none" || h.Privileged || !h.ReadonlyRootfs || len(h.CapAdd) != 0 || len(h.CapDrop) != 1 || h.CapDrop[0] != "ALL" || h.PidMode != "" || h.IpcMode == "host" || h.CgroupnsMode != "private" || h.NanoCPUs != p.NanoCPUs || h.Memory != p.Memory || h.PidsLimit != p.Pids || h.RestartPolicy.Name != "no" {
		return invalid
	}
	return nil
}

func hasKeys(v any, keys ...string) bool {
	obj, ok := v.(map[string]any)
	if !ok {
		return false
	}
	for _, k := range keys {
		if _, ok := obj[k]; !ok {
			return false
		}
	}
	return true
}
