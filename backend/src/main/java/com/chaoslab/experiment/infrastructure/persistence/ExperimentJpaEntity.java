package com.chaoslab.experiment.infrastructure.persistence;

import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "experiments")
class ExperimentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String id;

    @Column(name = "name", nullable = false, length = Experiment.MAX_NAME_LENGTH)
    private String name;

    @Column(
            name = "hypothesis",
            nullable = false,
            length = Experiment.MAX_HYPOTHESIS_LENGTH
    )
    private String hypothesis;

    @Column(name = "target_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String targetId;

    @Column(name = "scenario_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String scenarioId;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false, columnDefinition = "json")
    private String parameters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExperimentStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ExperimentJpaEntity() {
    }

    private ExperimentJpaEntity(
            String id,
            String name,
            String hypothesis,
            String targetId,
            String scenarioId,
            int durationSeconds,
            String parameters,
            ExperimentStatus status,
            long version
    ) {
        this.id = id;
        this.name = name;
        this.hypothesis = hypothesis;
        this.targetId = targetId;
        this.scenarioId = scenarioId;
        this.durationSeconds = durationSeconds;
        this.parameters = parameters;
        this.status = status;
        this.version = version;
    }

    static ExperimentJpaEntity from(Experiment experiment) {
        return new ExperimentJpaEntity(
                experiment.getId().toString(),
                experiment.getName(),
                experiment.getHypothesis(),
                experiment.getTargetId().toString(),
                experiment.getScenarioId().toString(),
                experiment.getDurationSeconds(),
                experiment.getParameters(),
                experiment.getStatus(),
                experiment.getVersion()
        );
    }

    Experiment toDomain() {
        return Experiment.rehydrate(
                UUID.fromString(id),
                name,
                hypothesis,
                UUID.fromString(targetId),
                UUID.fromString(scenarioId),
                durationSeconds,
                parameters,
                status,
                version
        );
    }
}
