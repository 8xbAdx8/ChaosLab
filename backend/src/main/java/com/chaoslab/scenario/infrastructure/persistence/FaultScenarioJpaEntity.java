package com.chaoslab.scenario.infrastructure.persistence;

import com.chaoslab.scenario.domain.FaultScenario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "fault_scenarios")
class FaultScenarioJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String id;

    @Column(name = "code", nullable = false, length = FaultScenario.MAX_CODE_LENGTH)
    private String code;

    @Column(name = "name", nullable = false, length = FaultScenario.MAX_NAME_LENGTH)
    private String name;

    @Column(
            name = "description",
            nullable = false,
            length = FaultScenario.MAX_DESCRIPTION_LENGTH
    )
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameter_schema", nullable = false, columnDefinition = "json")
    private String parameterSchema;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected FaultScenarioJpaEntity() {
    }

    FaultScenario toDomain() {
        return FaultScenario.rehydrate(
                UUID.fromString(id),
                code,
                name,
                description,
                parameterSchema,
                enabled
        );
    }
}
