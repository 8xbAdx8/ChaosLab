package com.chaoslab.shared.interfaces.rest;

import com.chaoslab.audit.application.InvalidAuditQueryException;

import com.chaoslab.execution.application.ExperimentExecutionDestroyRejectedException;
import com.chaoslab.execution.application.ExperimentExecutionNotFoundException;
import com.chaoslab.execution.application.ExperimentExecutionStartRejectedException;
import com.chaoslab.execution.application.InvalidIdempotencyKeyException;
import com.chaoslab.experiment.application.ExperimentCreationRejectedException;
import com.chaoslab.experiment.application.ExperimentNotFoundException;
import com.chaoslab.experiment.application.ExperimentParametersInvalidException;
import com.chaoslab.experiment.application.ExperimentValidationRejectedException;
import com.chaoslab.experiment.application.InvalidFaultScenarioSchemaException;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.target.application.TargetNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidAuditQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAuditQuery(
            InvalidAuditQueryException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_AUDIT_QUERY",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(TargetNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTargetNotFound(
            TargetNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "TARGET_NOT_FOUND",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ExperimentExecutionDestroyRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleExecutionDestroyRejected(
            ExperimentExecutionDestroyRejectedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                exception.getCode(),
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ExperimentExecutionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleExecutionNotFound(
            ExperimentExecutionNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "EXPERIMENT_EXECUTION_NOT_FOUND",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ExperimentExecutionStartRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleExecutionStartRejected(
            ExperimentExecutionStartRejectedException exception,
            HttpServletRequest request
    ) {
        List<ApiViolationResponse> violations = exception.getFailedChecks().stream()
                .map(check -> new ApiViolationResponse(
                        "/safety",
                        check.code(),
                        check.message()
                ))
                .toList();
        return error(
                HttpStatus.CONFLICT,
                exception.getCode(),
                exception.getMessage(),
                request,
                Map.of(),
                violations
        );
    }

    @ExceptionHandler(ExperimentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleExperimentNotFound(
            ExperimentNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "EXPERIMENT_NOT_FOUND",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ExperimentCreationRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleExperimentCreationRejected(
            ExperimentCreationRejectedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                exception.getCode(),
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ExperimentValidationRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleExperimentValidationRejected(
            ExperimentValidationRejectedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                exception.getCode(),
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ExperimentParametersInvalidException.class)
    public ResponseEntity<ApiErrorResponse> handleExperimentParametersInvalid(
            ExperimentParametersInvalidException exception,
            HttpServletRequest request
    ) {
        List<ApiViolationResponse> violations = exception.getViolations().stream()
                .map(violation -> new ApiViolationResponse(
                        violation.path(),
                        violation.keyword(),
                        violation.message()
                ))
                .toList();
        return error(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "EXPERIMENT_PARAMETERS_INVALID",
                exception.getMessage(),
                request,
                Map.of(),
                violations
        );
    }

    @ExceptionHandler(InvalidFaultScenarioSchemaException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidFaultScenarioSchema(
            InvalidFaultScenarioSchemaException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SCENARIO_SCHEMA_INVALID",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLockingFailure(
            OptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "RESOURCE_VERSION_CONFLICT",
                "resource was modified by another request; reload and retry",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(FaultScenarioNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFaultScenarioNotFound(
            FaultScenarioNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "SCENARIO_NOT_FOUND",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "request validation failed",
                request,
                fieldErrors
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "request body or parameter is malformed",
                request,
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        return error(status, code, message, request, fieldErrors, List.of());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors,
            List<ApiViolationResponse> violations
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                code,
                message,
                request.getRequestURI(),
                Instant.now(),
                fieldErrors,
                violations
        );
        return ResponseEntity.status(status).body(response);
    }
}
