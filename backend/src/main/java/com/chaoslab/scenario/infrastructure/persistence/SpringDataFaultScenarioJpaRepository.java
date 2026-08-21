package com.chaoslab.scenario.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataFaultScenarioJpaRepository
        extends JpaRepository<FaultScenarioJpaEntity, String> {
}
