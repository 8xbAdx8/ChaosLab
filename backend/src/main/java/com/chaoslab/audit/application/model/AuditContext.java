package com.chaoslab.audit.application.model;

import java.util.Objects;

public record AuditContext(String actor, String sourceIp) {

    public AuditContext {
        Objects.requireNonNull(actor, "actor must not be null");
    }
}
