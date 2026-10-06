package main

import (
	"encoding/json"
	"os"
	"runtime"
)

const outputLimit = 65536

func controlledEnv(state string) []string {
	env := []string{"PATH=/usr/bin:/bin", "LANG=C", "LC_ALL=C", "HOME=" + state, "CHAOSBLADE_DATAFILE_PATH=" + state}
	// Only used by portable fixture code; production is Linux-only.
	if runtime.GOOS == "windows" {
		env = append(env, "SystemRoot="+os.Getenv("SystemRoot"))
	}
	return env
}
func validResponse(raw []byte, create bool, uid string) bool {
	var v struct {
		Code    int             `json:"code"`
		Success bool            `json:"success"`
		Result  json.RawMessage `json:"result"`
		Error   string          `json:"error,omitempty"`
	}
	if strictJSON(raw, &v) != nil || v.Code != 200 || !v.Success || v.Error != "" || len(v.Result) == 0 {
		return false
	}
	if create {
		var got string
		return json.Unmarshal(v.Result, &got) == nil && got == uid
	}
	return true // Java's existing operation decoder still owns business schemas.
}
