package com.chaoslab.audit.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataAuditLogJpaRepository
        extends JpaRepository<AuditLogJpaEntity, String> {

    List<AuditLogJpaEntity> findAllByOrderByOccurredAtDescIdDesc(
            Pageable pageable
    );

    List<AuditLogJpaEntity> findAllByExperimentIdOrderByOccurredAtDescIdDesc(
            String experimentId,
            Pageable pageable
    );
}
