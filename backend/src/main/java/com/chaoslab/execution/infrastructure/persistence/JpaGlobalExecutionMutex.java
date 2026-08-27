package com.chaoslab.execution.infrastructure.persistence;

import com.chaoslab.execution.application.port.GlobalExecutionMutex;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Objects;

@Repository
public class JpaGlobalExecutionMutex implements GlobalExecutionMutex {

    private static final String LOCK_SQL = """
            SELECT id
            FROM execution_admission_control
            WHERE id = 1
            FOR UPDATE
            """;

    private final EntityManager entityManager;

    public JpaGlobalExecutionMutex(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(
                entityManager,
                "entityManager must not be null"
        );
    }

    @Override
    public void lock() {
        entityManager.createNativeQuery(LOCK_SQL).getSingleResult();
    }
}
