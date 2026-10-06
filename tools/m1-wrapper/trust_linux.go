package main

import (
	"os"
	"runtime"
	"strconv"
	"strings"
	"syscall"
)

func checkOwnerMode(path string, st os.FileInfo, owner uint32) error {
	s, ok := st.Sys().(*syscall.Stat_t)
	if !ok || s.Uid != owner || st.Mode().Perm()&0022 != 0 || st.Mode()&(os.ModeSetuid|os.ModeSetgid) != 0 {
		return invalid
	}
	// Reject extended access ACLs rather than guessing whether they grant a
	// service identity write permission. Default ACLs also fail closed.
	for _, name := range []string{"system.posix_acl_access", "system.posix_acl_default"} {
		n, e := syscall.Getxattr(path, name, nil)
		if e == nil && n > 0 {
			return invalid
		}
		if e != nil && e != syscall.ENODATA && e != syscall.ENOTSUP {
			return invalid
		}
	}
	return nil
}
func productionAllowed() bool { return runtime.GOARCH == "amd64" && os.Geteuid() == 0 }
func checkExecutable(st os.FileInfo) error {
	if st.Mode().Perm()&0100 == 0 {
		return invalid
	}
	return nil
}
func prepareProcess() {
	os.Clearenv()
	for _, entry := range controlledEnv(statePath) {
		pair := strings.SplitN(entry, "=", 2)
		os.Setenv(pair[0], pair[1])
	}
	syscall.Umask(0077)
	entries, _ := os.ReadDir("/proc/self/fd")
	for _, entry := range entries {
		fd, e := strconv.Atoi(entry.Name())
		if e == nil && fd > 2 {
			syscall.CloseOnExec(fd)
		}
	}
}
func syncDirectory(path string) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
