package com.chaoslab.audit.domain;

public enum AuditOperation {
    START_EXPERIMENT,
    DESTROY_EXPERIMENT,
    AUTOMATIC_RECOVERY,
    EMERGENCY_RECOVERY,
    EMERGENCY_STOP,
    M1_CPU_OBSERVATION,
    M1_PHYSICAL_RECOVERY
}
