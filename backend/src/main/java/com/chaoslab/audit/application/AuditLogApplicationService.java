package com.chaoslab.audit.application;

import com.chaoslab.audit.application.dto.AuditLogDetails;
import com.chaoslab.audit.application.model.AuditIntent;
import com.chaoslab.audit.application.port.AuditLogRepository;
import com.chaoslab.audit.domain.AuditLog;
import com.chaoslab.audit.domain.AuditResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuditLogApplicationService {

    public static final int MAX_QUERY_LIMIT = 200;

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditLogApplicationService(
            AuditLogRepository auditLogRepository,
            Clock clock
    ) {
        this.auditLogRepository = Objects.requireNonNull(
                auditLogRepository,
                "auditLogRepository must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogDetails append(
            AuditIntent intent,
            AuditResult result,
            String failureCode
    ) {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(result, "result must not be null");
        AuditLog auditLog = new AuditLog(
                UUID.randomUUID(),
                intent.context().actor(),
                intent.operation(),
                intent.subject().experimentId(),
                intent.subject().executionId(),
                intent.subject().targetId(),
                intent.subject().scenarioCode(),
                intent.subject().parameters(),
                intent.context().sourceIp(),
                result,
                failureCode,
                clock.instant()
        );
        return AuditLogDetails.from(auditLogRepository.append(auditLog));
    }

    public List<AuditLogDetails> findRecent(UUID experimentId, int limit) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new InvalidAuditQueryException(
                    "limit must be between 1 and " + MAX_QUERY_LIMIT
            );
        }
        return auditLogRepository.findRecent(experimentId, limit).stream()
                .map(AuditLogDetails::from)
                .toList();
    }
}
