package com.chaoslab.target.infrastructure.persistence;

import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryTargetRepository implements TargetRepository {

    private static final Comparator<Target> TARGET_ORDER = Comparator
            .comparing(Target::getName)
            .thenComparing(Target::getId);

    private final ConcurrentMap<UUID, Target> targets = new ConcurrentHashMap<>();

    @Override
    public Target save(Target target) {
        Objects.requireNonNull(target, "target must not be null");
        targets.put(target.getId(), target);
        return target;
    }

    @Override
    public Optional<Target> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(targets.get(id));
    }

    @Override
    public List<Target> findAll() {
        return targets.values().stream()
                .sorted(TARGET_ORDER)
                .toList();
    }
}
