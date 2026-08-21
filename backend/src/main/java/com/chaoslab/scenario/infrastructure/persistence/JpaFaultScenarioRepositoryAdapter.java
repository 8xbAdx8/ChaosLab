package com.chaoslab.scenario.infrastructure.persistence;

import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaFaultScenarioRepositoryAdapter implements FaultScenarioRepository {

    private static final Sort SCENARIO_ORDER = Sort.by(Sort.Order.asc("code"));

    private final SpringDataFaultScenarioJpaRepository repository;

    public JpaFaultScenarioRepositoryAdapter(
            SpringDataFaultScenarioJpaRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public Optional<FaultScenario> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return repository.findById(id.toString()).map(FaultScenarioJpaEntity::toDomain);
    }

    @Override
    public List<FaultScenario> findAll() {
        return repository.findAll(SCENARIO_ORDER).stream()
                .map(FaultScenarioJpaEntity::toDomain)
                .toList();
    }
}
