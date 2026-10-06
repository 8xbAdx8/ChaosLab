package main

import "context"

func collectBaseline(Policy) (*CpuBaseline, error) { return nil, invalid }
func observeTarget(_ context.Context, p Policy, b *Binding) *Observation {
	return &Observation{NodeID: p.NodeID, ContainerID: p.ContainerID, ImageID: p.ImageID, Residual: "UNKNOWN", Health: "UNKNOWN"}
}
