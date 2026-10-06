package main

import "os"

func checkExecutable(os.FileInfo) error { return nil }

func prepareProcess() {}

// Windows is a portable protocol-test host only, never a privileged backend.
func productionAllowed() bool                                { return false }
func checkOwnerMode(_ string, _ os.FileInfo, _ uint32) error { return nil }
func syncDirectory(_ string) error                           { return nil } // test fixture persistence only
