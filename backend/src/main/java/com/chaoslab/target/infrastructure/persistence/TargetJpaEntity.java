package com.chaoslab.target.infrastructure.persistence;

import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "targets")
class TargetJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String id;

    @Column(name = "name", nullable = false, length = Target.MAX_NAME_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private TargetType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 32)
    private TargetEnvironment environment;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected TargetJpaEntity() {
    }

    private TargetJpaEntity(
            String id,
            String name,
            TargetType type,
            TargetEnvironment environment,
            boolean enabled
    ) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.environment = environment;
        this.enabled = enabled;
    }

    static TargetJpaEntity from(Target target) {
        return new TargetJpaEntity(
                target.getId().toString(),
                target.getName(),
                target.getType(),
                target.getEnvironment(),
                target.isEnabled()
        );
    }

    Target toDomain() {
        return Target.rehydrate(
                UUID.fromString(id),
                name,
                type,
                environment,
                enabled
        );
    }
}
