package com.chaoslab.experiment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataExperimentJpaRepository
        extends JpaRepository<ExperimentJpaEntity, String> {
}
