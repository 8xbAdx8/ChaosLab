package com.chaoslab.experiment.application;

import com.chaoslab.experiment.application.dto.CreateExperimentCommand;
import com.chaoslab.experiment.application.dto.ExperimentDetails;
import com.chaoslab.experiment.application.port.ExperimentParameterValidator;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.application.validation.ParameterViolation;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.application.TargetNotFoundException;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ExperimentApplicationService {

    private final ExperimentRepository experimentRepository;
    private final TargetRepository targetRepository;
    private final FaultScenarioRepository faultScenarioRepository;
    private final ExperimentParameterValidator parameterValidator;

    public ExperimentApplicationService(
            ExperimentRepository experimentRepository,
            TargetRepository targetRepository,
            FaultScenarioRepository faultScenarioRepository,
            ExperimentParameterValidator parameterValidator
    ) {
        this.experimentRepository = Objects.requireNonNull(
                experimentRepository,
                "experimentRepository must not be null"
        );
        this.targetRepository = Objects.requireNonNull(
                targetRepository,
                "targetRepository must not be null"
        );
        this.faultScenarioRepository = Objects.requireNonNull(
                faultScenarioRepository,
                "faultScenarioRepository must not be null"
        );
        this.parameterValidator = Objects.requireNonNull(
                parameterValidator,
                "parameterValidator must not be null"
        );
    }

    @Transactional
    public ExperimentDetails create(CreateExperimentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Target target = targetRepository.findById(command.targetId())
                .orElseThrow(() -> new TargetNotFoundException(command.targetId()));
        if (!target.isEnabled()) {
            throw new ExperimentCreationRejectedException(
                    "TARGET_DISABLED",
                    "target is disabled: " + target.getId()
            );
        }

        FaultScenario scenario = faultScenarioRepository.findById(command.scenarioId())
                .orElseThrow(() -> new FaultScenarioNotFoundException(command.scenarioId()));
        if (!scenario.isEnabled()) {
            throw new ExperimentCreationRejectedException(
                    "SCENARIO_DISABLED",
                    "fault scenario is disabled: " + scenario.getId()
            );
        }

        Experiment experiment = Experiment.create(
                UUID.randomUUID(),
                command.name(),
                command.hypothesis(),
                target.getId(),
                scenario.getId(),
                command.durationSeconds(),
                command.parameters()
        );
        return ExperimentDetails.from(experimentRepository.insert(experiment));
    }

    public ExperimentDetails findById(UUID experimentId) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        return experimentRepository.findById(experimentId)
                .map(ExperimentDetails::from)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId));
    }

    @Transactional
    public ExperimentDetails validate(UUID experimentId) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Experiment experiment = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        if (experiment.getStatus() == ExperimentStatus.VALIDATED) {
            return ExperimentDetails.from(experiment);
        }

        Target target = targetRepository.findById(experiment.getTargetId())
                .orElseThrow(() -> new TargetNotFoundException(experiment.getTargetId()));
        if (!target.isEnabled()) {
            throw new ExperimentValidationRejectedException(
                    "TARGET_DISABLED",
                    "target is disabled: " + target.getId()
            );
        }

        FaultScenario scenario = faultScenarioRepository.findById(experiment.getScenarioId())
                .orElseThrow(() -> new FaultScenarioNotFoundException(
                        experiment.getScenarioId()
                ));
        if (!scenario.isEnabled()) {
            throw new ExperimentValidationRejectedException(
                    "SCENARIO_DISABLED",
                    "fault scenario is disabled: " + scenario.getId()
            );
        }

        List<ParameterViolation> violations = parameterValidator.validate(
                scenario.getParameterSchema(),
                experiment.getParameters()
        );
        if (!violations.isEmpty()) {
            throw new ExperimentParametersInvalidException(violations);
        }

        return ExperimentDetails.from(experimentRepository.update(experiment.validate()));
    }

    public List<ExperimentDetails> findAll() {
        return experimentRepository.findAll().stream()
                .map(ExperimentDetails::from)
                .toList();
    }
}
