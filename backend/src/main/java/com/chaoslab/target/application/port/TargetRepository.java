package com.chaoslab.target.application.port;

import com.chaoslab.target.domain.Target;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TargetRepository {

    Target save(Target target);

    Optional<Target> findById(UUID id);

    List<Target> findAll();
}
