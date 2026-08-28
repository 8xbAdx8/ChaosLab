package com.chaoslab.audit.application.model;

import com.chaoslab.audit.domain.AuditOperation;

import java.util.Objects;

public record AuditIntent(
        AuditContext context,
        AuditOperation operation,
        AuditSubject subject
) {

    public AuditIntent {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
    }
}
