package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestMain(m *testing.M) {
	if len(os.Args) == 3 && os.Args[1] == "fixture-wrapper" {
		root := os.Args[2]
		c := config{policy: filepath.Join(root, "config/policy.json"), tool: filepath.Join(root, "tools/fake-blade"), state: filepath.Join(root, "state"), trustRoot: root, owner: uint32(os.Getuid()), deadline: 3 * time.Second, targetCheck: func(Policy) error { return nil }}
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		q, e := readRequest(os.Stdin)
		if e != nil {
			os.Exit(80)
		}
		json.NewEncoder(os.Stdout).Encode(serve(ctx, c, q))
		return
	}
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "create", "status", "destroy", "fixture-helper", "fixture-timer", "fixture-settling-helper":
			fixture()
			return
		}
	}
	os.Exit(m.Run())
}
func fixture() {
	state := os.Getenv("CHAOSBLADE_DATAFILE_PATH")
	if os.Args[1] == "fixture-settling-helper" {
		time.Sleep(1500 * time.Millisecond) // harmless independently exiting helper
		return
	}
	if os.Args[1] == "fixture-timer" {
		time.Sleep(600 * time.Millisecond)
		exe, _ := os.Executable()
		raw, _ := json.Marshal(map[string]any{"tool": exe, "state": state, "uid": os.Args[2], "euid": os.Geteuid()})
		os.WriteFile(filepath.Join(state, "timer.json"), raw, 0600)
		cmd := exec.Command(exe, "destroy", os.Args[2])
		cmd.Env = os.Environ()
		cmd.Run()
		return
	}
	if os.Args[1] == "fixture-helper" {
		signal.Ignore(syscall.SIGTERM) // cleanup must escalate for a stuck child
		time.Sleep(30 * time.Second)
		return
	}
	modeBytes, _ := os.ReadFile(filepath.Join(state, "scenario"))
	mode := string(modeBytes)
	info := map[string]any{"args": os.Args[1:], "env": os.Environ()}
	bind, e := os.ReadFile(filepath.Join(state, "binding.json"))
	if e == nil {
		info["binding"] = json.RawMessage(bind)
	}
	if os.Args[1] == "create" && e != nil {
		os.Exit(78)
	}
	raw, _ := json.Marshal(info)
	os.WriteFile(filepath.Join(state, "invocation.json"), raw, 0600)
	if mode == "settling" && os.Args[1] == "create" {
		exe, _ := os.Executable()
		cmd := exec.Command(exe, "fixture-settling-helper")
		cmd.Env = os.Environ()
		if e := cmd.Start(); e != nil {
			os.Exit(79)
		}
		os.WriteFile(filepath.Join(state, "helper.pid"), []byte(strconv.Itoa(cmd.Process.Pid)), 0600)
		cmd.Process.Release()
		time.Sleep(100 * time.Millisecond)
	}
	if (strings.Contains(mode, "helper") || mode == "timeout" || mode == "overflow" || mode == "nonzero" || mode == "malformed" || mode == "different-uid" || mode == "signal") && !(mode == "timeout-helper" && os.Args[1] == "destroy") {
		exe, _ := os.Executable()
		cmd := exec.Command(exe, "fixture-helper")
		if mode == "timeout-helper" {
			uid := os.Args[len(os.Args)-1]
			cmd = exec.Command(exe, "fixture-timer", uid)
		}
		cmd.Env = os.Environ()
		if mode == "inherited-helper" {
			cmd.Stdout = os.Stdout
			cmd.Stderr = os.Stderr
		}
		if e := cmd.Start(); e != nil {
			os.Exit(79)
		}
		os.WriteFile(filepath.Join(state, "helper.pid"), []byte(strconv.Itoa(cmd.Process.Pid)), 0600)
		cmd.Process.Release()
		time.Sleep(100 * time.Millisecond) // permits deliberate observation, not a race assertion
	}
	switch mode {
	case "timeout", "signal":
		time.Sleep(30 * time.Second)
	case "overflow":
		fmt.Print(strings.Repeat("x", outputLimit+4096))
		return
	case "nonzero":
		fmt.Fprint(os.Stderr, "SECRET_SHOULD_NOT_LEAK")
		os.Exit(7)
	case "malformed":
		fmt.Print("not JSON SECRET_SHOULD_NOT_LEAK")
		return
	}
	uid := testUID
	if os.Args[1] == "create" {
		for i, a := range os.Args {
			if a == "--uid" && i+1 < len(os.Args) {
				uid = os.Args[i+1]
			}
		}
	} else if len(os.Args) > 2 {
		uid = os.Args[2]
	}
	if mode == "different-uid" {
		uid = "fedcba9876543210"
	}
	if mode == "settling" && os.Args[1] == "destroy" {
		os.WriteFile(filepath.Join(state, "destroyed"), []byte(uid), 0600)
		json.NewEncoder(os.Stdout).Encode(map[string]any{"code": 200, "success": true, "result": "command: cri cpu fullload --cpu-count=1, destroy time: fixture"})
		return
	}
	if mode == "settling" && os.Args[1] == "status" {
		if _, e := os.Stat(filepath.Join(state, "destroyed")); e != nil {
			os.Exit(77)
		}
		json.NewEncoder(os.Stdout).Encode(map[string]any{"code": 200, "success": true, "result": map[string]string{"Uid": uid, "Command": "cri", "SubCommand": "cpu fullload", "Flag": "", "Status": "Destroyed", "Error": "", "CreateTime": "", "UpdateTime": ""}})
		return
	}
	json.NewEncoder(os.Stdout).Encode(map[string]any{"code": 200, "success": true, "result": uid})
}

func testPolicy() Policy {
	return Policy{ContainerID: strings.Repeat("a", 64), ImageID: "sha256:" + strings.Repeat("b", 64), ContainerName: "chaoslab-cpu-sandbox", User: "65534:65534", NanoCPUs: 500000000, Memory: 134217728, Pids: 32, CPUPercent: 10, DurationSeconds: 10, NodeID: "m1-node", StateID: "m1-state"}
}
func put(t *testing.T, path string, b []byte) {
	t.Helper()
	if e := os.WriteFile(path, b, 0600); e != nil {
		t.Fatal(e)
	}
}
func putJSON(t *testing.T, path string, v any) {
	t.Helper()
	b, e := json.Marshal(v)
	if e != nil {
		t.Fatal(e)
	}
	put(t, path, b)
}
func fixtureConfig(t *testing.T) (config, Policy) {
	t.Helper()
	root := t.TempDir()
	state := filepath.Join(root, "state")
	tools := filepath.Join(root, "tools")
	conf := filepath.Join(root, "config")
	for _, p := range []string{state, tools, conf, filepath.Join(tools, "bin"), filepath.Join(tools, "yaml")} {
		if e := os.Mkdir(p, 0700); e != nil {
			t.Fatal(e)
		}
	}
	name := "fake-blade"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	c := config{policy: filepath.Join(conf, "policy.json"), tool: filepath.Join(tools, name), state: state, trustRoot: root, owner: uint32(os.Getuid()), deadline: 800 * time.Millisecond, targetCheck: func(Policy) error { return nil }}
	exe, _ := os.Executable()
	b, e := os.ReadFile(exe)
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(c.tool, b, 0700); e != nil {
		t.Fatal(e)
	}
	sum := sha256.Sum256(b)
	small := sha256.Sum256([]byte("fixture"))
	p := testPolicy()
	p.Deployment, p.Executable, p.StateDirectory = "FAKE", c.tool, c.state
	p.ToolSHA = hex.EncodeToString(sum[:])
	p.NsexecSHA = hex.EncodeToString(small[:])
	p.ChaosOSSHA = p.NsexecSHA
	p.YamlSHA = p.NsexecSHA
	for _, name := range []string{"bin/nsexec", "bin/chaos_os", "yaml/chaosblade-cri-spec-1.8.1.yaml"} {
		put(t, filepath.Join(tools, name), []byte("fixture"))
		if !strings.HasSuffix(name, ".yaml") {
			if e := os.Chmod(filepath.Join(tools, name), 0700); e != nil {
				t.Fatal(e)
			}
		}
	}
	putJSON(t, c.policy, p)
	put(t, filepath.Join(conf, "node-id"), []byte(p.NodeID+"\n"))
	put(t, filepath.Join(state, ".chaoslab-state-id"), []byte(p.StateID+"\n"))
	return c, p
}
