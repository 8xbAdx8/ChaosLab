// Harmless Linux fixture. No Docker access, namespace operations or CPU workload.
package main

import (
	"encoding/json"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "helper" {
		signal.Ignore(syscall.SIGTERM)
		time.Sleep(45 * time.Second)
		return
	}
	state := os.Getenv("CHAOSBLADE_DATAFILE_PATH")
	if os.Geteuid() != 0 || state != "/var/lib/chaoslab-m1/fixture-state" {
		os.Exit(90)
	}
	raw, e := os.ReadFile(filepath.Join(state, "scenario"))
	if e != nil {
		os.Exit(91)
	}
	mode := string(raw)
	uid := ""
	for i, a := range os.Args {
		if a == "--uid" && i+1 < len(os.Args) {
			uid = os.Args[i+1]
		}
	}
	if uid == "" && len(os.Args) > 2 {
		uid = os.Args[2]
	}
	info := map[string]any{"euid": os.Geteuid(), "pid": os.Getpid(), "args": os.Args[1:], "env": os.Environ()}
	if mode != "normal" {
		exe, _ := os.Executable()
		child := exec.Command(exe, "helper")
		if mode == "detached" {
			child.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
		}
		if e := child.Start(); e != nil {
			os.Exit(92)
		}
		info["helperPid"] = child.Process.Pid
		stat, _ := os.ReadFile("/proc/" + strconv.Itoa(child.Process.Pid) + "/stat")
		info["helperStat"] = string(stat)
		child.Process.Release()
	}
	data, _ := json.Marshal(info)
	os.WriteFile(filepath.Join(state, "invocation.json"), data, 0600)
	time.Sleep(150 * time.Millisecond)
	switch mode {
	case "timeout", "signal":
		time.Sleep(20 * time.Second)
	case "nonzero":
		os.Exit(7)
	case "malformed":
		os.Stdout.WriteString("invalid JSON")
		return
	case "overflow":
		os.Stdout.WriteString(strings.Repeat("x", 70000))
		return
	}
	json.NewEncoder(os.Stdout).Encode(map[string]any{"code": 200, "success": true, "result": uid})
}
