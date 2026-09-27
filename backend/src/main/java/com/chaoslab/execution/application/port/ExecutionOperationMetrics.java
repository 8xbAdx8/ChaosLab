package com.chaoslab.execution.application.port;

import com.chaoslab.audit.domain.AuditOperation;

public interface ExecutionOperationMetrics {

    void record(AuditOperation operation, Result result);

    enum Result {
        SUCCESS,
        REPLAYED,
        REJECTED,
        FAILED
    }
}
