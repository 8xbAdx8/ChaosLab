package com.chaoslab.experiment.infrastructure.validation;

import com.chaoslab.experiment.application.InvalidFaultScenarioSchemaException;
import com.chaoslab.experiment.application.port.ExperimentParameterValidator;
import com.chaoslab.experiment.application.validation.ParameterViolation;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.resource.ClasspathResourceLoader;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class JsonSchemaExperimentParameterValidator
        implements ExperimentParameterValidator {

    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDialect(
            Dialects.getDraft202012(),
            builder -> builder.resourceLoaders(resourceLoaders -> resourceLoaders
                    .values(List::clear)
                    .add(ClasspathResourceLoader.getInstance()))
    );

    @Override
    public List<ParameterViolation> validate(
            String parameterSchema,
            String parameters
    ) {
        Objects.requireNonNull(parameterSchema, "parameterSchema must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        try {
            Schema schema = schemaRegistry.getSchema(parameterSchema, InputFormat.JSON);
            return schema.validate(parameters, InputFormat.JSON).stream()
                    .sorted(Comparator
                            .comparing(JsonSchemaExperimentParameterValidator::pathOf)
                            .thenComparing(Error::getKeyword))
                    .map(error -> new ParameterViolation(
                            pathOf(error),
                            error.getKeyword(),
                            error.getMessage()
                    ))
                    .toList();
        } catch (RuntimeException exception) {
            throw new InvalidFaultScenarioSchemaException(exception);
        }
    }

    private static String pathOf(Error error) {
        String path = error.getInstanceLocation().toString();
        return path.isBlank() ? "/" : path;
    }
}
