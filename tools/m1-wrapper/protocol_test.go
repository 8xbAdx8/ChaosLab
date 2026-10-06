package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const testExecution = "12345678-1234-4234-8234-123456789abc"
const testUID = "0123456789abcdef"

func request(op string) Request { return Request{op, testExecution, testUID} }

func TestParserAttacks(t *testing.T) {
	valid := `{"operation":"status","executionId":"` + testExecution + `","nativeUid":"` + testUID + `"}`
	attacks := map[string]string{
		"unknown operation": strings.Replace(valid, "status", "exec", 1),
		"unknown field":     strings.TrimSuffix(valid, "}") + `,"flags":[]}`,
		"duplicate":         strings.TrimSuffix(valid, "}") + `,"operation":"destroy"}`,
		"escaped duplicate": strings.TrimSuffix(valid, "}") + `,"oper\u0061tion":"destroy"}`,
		"oversized":         strings.Repeat(" ", requestLimit) + valid,
		"trailing JSON":     valid + `{}`, "trailing garbage": valid + "x",
		"malformed UTF8":    strings.Replace(valid, "status", string([]byte{255}), 1),
		"invalid UID":       strings.Replace(valid, testUID, "abcdef", 1),
		"command injection": strings.Replace(valid, testUID, "0123456789abcdef;id", 1),
		"newline":           strings.Replace(valid, testUID, `0123456789abcdef\n`, 1),
		"nul":               strings.Replace(valid, testUID, `0123456789abcdef\u0000`, 1),
		"environment":       strings.TrimSuffix(valid, "}") + `,"environment":{"LD_PRELOAD":"/tmp/evil"}}`,
		"container":         strings.TrimSuffix(valid, "}") + `,"containerId":"` + strings.Repeat("a", 64) + `"}`,
		"path":              strings.TrimSuffix(valid, "}") + `,"executable":"/bin/sh"}`,
		"null":              `null`, "array": `[]`, "uppercase key": strings.Replace(valid, "operation", "Operation", 1),
	}
	for name, input := range attacks {
		t.Run(name, func(t *testing.T) {
			if _, e := readRequest(strings.NewReader(input)); e == nil {
				t.Fatal("accepted attack")
			}
		})
	}
	for _, op := range []string{"create-cpu", "status", "destroy", "observe"} {
		q := request(op)
		b, _ := json.Marshal(q)
		if _, e := readRequest(bytes.NewReader(b)); e != nil {
			t.Fatal(op, e)
		}
	}
	if _, e := readRequest(strings.NewReader(`{"operation":"preflight"}`)); e != nil {
		t.Fatal(e)
	}
}

func TestCSPRNGAndEnvironment(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ {
		u, e := newNativeUID()
		if e != nil || !uidPattern.MatchString(u) || seen[u] {
			t.Fatal("UID generator")
		}
		seen[u] = true
	}
	t.Setenv("PATH", "evil")
	t.Setenv("LD_PRELOAD", "/tmp/evil")
	t.Setenv("CGROUP_ROOT", "/tmp/evil")
	t.Setenv("DOCKER_HOST", "tcp://evil")
	t.Setenv("CHAOSBLADE_DATAFILE_PATH", "evil")
	env := strings.Join(controlledEnv("/trusted/state"), "\n")
	if strings.Contains(env, "evil") || strings.Contains(env, "LD_PRELOAD") || !strings.Contains(env, "CHAOSBLADE_DATAFILE_PATH=/trusted/state") {
		t.Fatal(env)
	}
}

func TestInspectAllowlist(t *testing.T) {
	p := testPolicy()
	v := map[string]any{"Id": p.ContainerID, "Image": p.ImageID, "Name": "/chaoslab-cpu-sandbox", "Path": "sleep", "Args": []string{"3600"}, "State": map[string]any{"Running": true}, "Config": map[string]any{"User": p.User, "Entrypoint": nil, "Cmd": []string{"sleep", "3600"}}, "Mounts": []any{}, "HostConfig": map[string]any{"NetworkMode": "none", "ReadonlyRootfs": true, "CapDrop": []string{"ALL"}, "SecurityOpt": []string{"no-new-privileges=true"}, "CgroupnsMode": "private", "NanoCpus": p.NanoCPUs, "Memory": p.Memory, "PidsLimit": p.Pids, "RestartPolicy": map[string]any{"Name": "no"}}}
	b, _ := json.Marshal(v)
	v["State"].(map[string]any)["Paused"] = false
	v["State"].(map[string]any)["Restarting"] = false
	v["State"].(map[string]any)["Dead"] = false
	h := v["HostConfig"].(map[string]any)
	h["Privileged"] = false
	h["CapAdd"] = nil
	h["Binds"] = nil
	h["Devices"] = nil
	h["PidMode"] = ""
	h["IpcMode"] = "private"
	b, _ = json.Marshal(v)
	if validateInspect(b, p) != nil {
		t.Fatal("safe fixture rejected")
	}
	for _, key := range []string{"Id", "Image", "Name"} {
		t.Run(key, func(t *testing.T) {
			var copy map[string]any
			json.Unmarshal(b, &copy)
			copy[key] = "changed"
			bad, _ := json.Marshal(copy)
			if validateInspect(bad, p) == nil {
				t.Fatal("accepted changed identity")
			}
		})
	}
	for _, key := range []string{"Privileged", "NetworkMode", "NanoCpus", "Memory", "PidsLimit", "CapDrop", "SecurityOpt", "Binds", "Devices", "CgroupnsMode", "ReadonlyRootfs"} {
		t.Run(key, func(t *testing.T) {
			var copy map[string]any
			json.Unmarshal(b, &copy)
			copy["HostConfig"].(map[string]any)[key] = true
			if key == "ReadonlyRootfs" {
				copy["HostConfig"].(map[string]any)[key] = false
			}
			bad, _ := json.Marshal(copy)
			if validateInspect(bad, p) == nil {
				t.Fatal("accepted unsafe config")
			}
		})
	}
	for _, mutate := range []func(map[string]any){
		func(v map[string]any) {
			h := v["HostConfig"].(map[string]any)
			h["NanoCPUs"] = h["NanoCpus"]
			delete(h, "NanoCpus")
		},
		func(v map[string]any) { v["Path"] = "sh" },
		func(v map[string]any) { v["Args"] = []string{"-c", "stress"} },
		func(v map[string]any) { v["Config"].(map[string]any)["Cmd"] = []string{"stress"} },
		func(v map[string]any) { v["Config"].(map[string]any)["Entrypoint"] = []string{"sh"} },
		func(v map[string]any) { delete(v["HostConfig"].(map[string]any), "SecurityOpt") },
	} {
		var copy map[string]any
		json.Unmarshal(b, &copy)
		mutate(copy)
		bad, _ := json.Marshal(copy)
		if validateInspect(bad, p) == nil {
			t.Fatal("accepted wrong spelling/workload/privilege boundary")
		}
	}
}

func TestResponseValidation(t *testing.T) {
	for _, b := range []string{`{}`, `{"code":200,"success":true,"result":"different"}`, `{"code":200,"success":true,"result":"` + testUID + `","result":"` + testUID + `"}`, `{"code":200,"success":true,"result":"` + testUID + `"} {}`} {
		if validResponse([]byte(b), true, testUID) {
			t.Fatal(b)
		}
	}
}

func TestWriteExclusiveNeverReplaces(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "binding.json")
	if e := writeExclusive(path, map[string]string{"uid": testUID}); e != nil {
		t.Fatal(e)
	}
	if e := writeExclusive(path, map[string]string{"uid": "different"}); e == nil {
		t.Fatal("overwrote")
	}
	b, _ := os.ReadFile(path)
	if !strings.Contains(string(b), testUID) {
		t.Fatal(string(b))
	}
}

type blockedOutput struct{ release chan struct{} }

func (w blockedOutput) Write(b []byte) (int, error) { <-w.release; return len(b), nil }
func TestOutputBackpressureBounded(t *testing.T) {
	w := blockedOutput{make(chan struct{})}
	defer close(w.release)
	if emitResult(w, result("OK"), 20*time.Millisecond) {
		t.Fatal("blocked output returned success")
	}
}
