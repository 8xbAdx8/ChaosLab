package com.chaoslab.experiment.infrastructure.persistence;

import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaExperimentRepositoryAdapter implements ExperimentRepository {

    private static final Sort EXPERIMENT_ORDER = Sort.by(
            Sort.Order.asc("name"),
            Sort.Order.asc("id")
    );

    private final SpringDataExperimentJpaRepository repository;
    private final EntityManager entityManager;

    public JpaExperimentRepositoryAdapter(
            SpringDataExperimentJpaRepository repository,
            EntityManager entityManager
    ) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public Experiment insert(Experiment experiment) {
        Objects.requireNonNull(experiment, "experiment must not be null");
        ExperimentJpaEntity entity = ExperimentJpaEntity.from(experiment);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public Experiment update(Experiment experiment) {
        Objects.requireNonNull(experiment, "experiment must not be null");
        ExperimentJpaEntity entity = repository.saveAndFlush(
                ExperimentJpaEntity.from(experiment)
        );
        return entity.toDomain();
    }

    @Override
    public Optional<Experiment> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return repository.findById(id.toString()).map(ExperimentJpaEntity::toDomain);
    }

    @Override
    public List<Experiment> findAll() {
        return repository.findAll(EXPERIMENT_ORDER).stream()
                .map(ExperimentJpaEntity::toDomain)
                .toList();
    }
}
