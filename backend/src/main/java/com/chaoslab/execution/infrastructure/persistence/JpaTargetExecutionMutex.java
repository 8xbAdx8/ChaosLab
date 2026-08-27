package com.chaoslab.execution.infrastructure.persistence;

import com.chaoslab.execution.application.port.TargetExecutionMutex;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

@Repository
public class JpaTargetExecutionMutex implements TargetExecutionMutex {

    private static final String LOCK_TARGET_SQL = """
            SELECT t.id
            FROM targets t
            INNER JOIN experiments e ON e.target_id = t.id
            WHERE e.id = :experimentId
            FOR UPDATE
            """;

    private final EntityManager entityManager;

    public JpaTargetExecutionMutex(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(
                entityManager,
                "entityManager must not be null"
        );
    }

    @Override
    public void lockForExperiment(UUID experimentId) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        entityManager.createNativeQuery(LOCK_TARGET_SQL, String.class)
                .setParameter("experimentId", experimentId.toString())
                .getResultList();
    }
}