package com.chaoslab.audit.application.port;

import com.chaoslab.audit.domain.AuditLog;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository {

    AuditLog append(AuditLog auditLog);

    List<AuditLog> findRecent(UUID experimentId, int limit);

    List<AuditLog> findByExecution(UUID experimentId, UUID executionId, int limit);
}
