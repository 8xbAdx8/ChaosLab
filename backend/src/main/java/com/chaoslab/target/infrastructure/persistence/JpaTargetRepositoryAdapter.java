package com.chaoslab.target.infrastructure.persistence;

import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaTargetRepositoryAdapter implements TargetRepository {

    private static final Sort TARGET_ORDER = Sort.by(
            Sort.Order.asc("name"),
            Sort.Order.asc("id")
    );

    private final SpringDataTargetJpaRepository repository;

    public JpaTargetRepositoryAdapter(SpringDataTargetJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Target save(Target target) {
        Objects.requireNonNull(target, "target must not be null");
        return repository.save(TargetJpaEntity.from(target)).toDomain();
    }

    @Override
    public Optional<Target> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return repository.findById(id.toString()).map(TargetJpaEntity::toDomain);
    }

    @Override
    public List<Target> findAll() {
        return repository.findAll(TARGET_ORDER).stream()
                .map(TargetJpaEntity::toDomain)
                .toList();
    }
}
