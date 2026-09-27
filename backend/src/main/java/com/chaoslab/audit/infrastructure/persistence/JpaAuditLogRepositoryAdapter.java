package com.chaoslab.audit.infrastructure.persistence;

import com.chaoslab.audit.application.port.AuditLogRepository;
import com.chaoslab.audit.domain.AuditLog;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaAuditLogRepositoryAdapter implements AuditLogRepository {

    private final SpringDataAuditLogJpaRepository repository;
    private final EntityManager entityManager;

    public JpaAuditLogRepositoryAdapter(
            SpringDataAuditLogJpaRepository repository,
            EntityManager entityManager
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository must not be null"
        );
        this.entityManager = Objects.requireNonNull(
                entityManager,
                "entityManager must not be null"
        );
    }

    @Override
    @Transactional
    public AuditLog append(AuditLog auditLog) {
        Objects.requireNonNull(auditLog, "auditLog must not be null");
        AuditLogJpaEntity entity = AuditLogJpaEntity.from(auditLog);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    public List<AuditLog> findRecent(UUID experimentId, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        List<AuditLogJpaEntity> entities = experimentId == null
                ? repository.findAllByOrderByOccurredAtDescIdDesc(page)
                : repository.findAllByExperimentIdOrderByOccurredAtDescIdDesc(
                        experimentId.toString(),
                        page
                );
        return entities.stream()
                .map(AuditLogJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<AuditLog> findByExecution(
            UUID experimentId,
            UUID executionId,
            int limit
    ) {
        return repository.findAllByExperimentIdAndExecutionIdOrderByOccurredAtDescIdDesc(
                        experimentId.toString(),
                        executionId.toString(),
                        PageRequest.of(0, limit)
                ).stream()
                .map(AuditLogJpaEntity::toDomain)
                .toList();
    }
}
