package main

import "time"

// A single fixed M1 probe, not an execution/recovery framework. No caller path,
// PID or namespace is accepted. Baseline lives with the pre-dispatch root binding.
type CpuSample struct {
	PID       int    `json:"pid"`
	StartTime string `json:"startTime"`
	Cgroup    string `json:"cgroup"`
	UsageUsec uint64 `json:"usageUsec"`
}
type CpuBaseline struct {
	Sample     CpuSample `json:"sample"`
	Percent    float64   `json:"percent"`
	ObservedAt time.Time `json:"observedAt"`
}
type Observation struct {
	ExecutionID     string     `json:"executionId,omitempty"`
	NativeUID       string     `json:"nativeUid,omitempty"`
	NodeID          string     `json:"nodeId"`
	ContainerID     string     `json:"containerId"`
	ImageID         string     `json:"imageId"`
	ObservedAt      time.Time  `json:"observedAt"`
	CPUPercent      *float64   `json:"cpuPercent,omitempty"`
	BaselinePercent *float64   `json:"baselinePercent,omitempty"`
	Sample          *CpuSample `json:"sample,omitempty"`
	Residual        string     `json:"residual"`
	Health          string     `json:"health"`
	ProbeReady      bool       `json:"probeReady"`
}
