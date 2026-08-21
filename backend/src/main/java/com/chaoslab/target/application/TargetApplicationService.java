package com.chaoslab.target.application;

import com.chaoslab.target.application.dto.RegisterTargetCommand;
import com.chaoslab.target.application.dto.TargetDetails;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class TargetApplicationService {

    private final TargetRepository targetRepository;

    public TargetApplicationService(TargetRepository targetRepository) {
        this.targetRepository = Objects.requireNonNull(
                targetRepository,
                "targetRepository must not be null"
        );
    }

    public TargetDetails register(RegisterTargetCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Target target = Target.register(
                UUID.randomUUID(),
                command.name(),
                command.type(),
                command.environment()
        );
        return TargetDetails.from(targetRepository.save(target));
    }

    public TargetDetails findById(UUID targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        return targetRepository.findById(targetId)
                .map(TargetDetails::from)
                .orElseThrow(() -> new TargetNotFoundException(targetId));
    }

    public List<TargetDetails> findAll() {
        return targetRepository.findAll().stream()
                .map(TargetDetails::from)
                .toList();
    }
}
