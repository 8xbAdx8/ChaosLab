package com.chaoslab.experiment.infrastructure.validation;

import com.chaoslab.experiment.application.InvalidFaultScenarioSchemaException;
import com.chaoslab.experiment.application.validation.ParameterViolation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaExperimentParameterValidatorTests {

    private static final String CPU_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "additionalProperties": false,
              "required": ["percent"],
              "properties": {
                "percent": {
                  "type": "integer",
                  "minimum": 10,
                  "maximum": 80
                }
              }
            }
            """;

    private final JsonSchemaExperimentParameterValidator validator =
            new JsonSchemaExperimentParameterValidator();

    @Test
    void shouldAcceptParametersMatchingDraft202012Schema() {
        assertThat(validator.validate(CPU_SCHEMA, "{\"percent\":40}")).isEmpty();
    }

    @Test
    void shouldReturnStructuredViolations() {
        List<ParameterViolation> violations = validator.validate(
                CPU_SCHEMA,
                "{\"percent\":81,\"unexpected\":true}"
        );

        assertThat(violations)
                .extracting(ParameterViolation::keyword)
                .containsExactlyInAnyOrder("additionalProperties", "maximum");
        assertThat(violations)
                .extracting(ParameterViolation::path)
                .contains("/percent");
    }

    @Test
    void shouldDistinguishInvalidScenarioSchemaFromInvalidParameters() {
        assertThatThrownBy(() -> validator.validate(
                "{\"$schema\":\"unknown-dialect\",\"type\":\"object\"}",
                "{}"
        )).isInstanceOf(InvalidFaultScenarioSchemaException.class)
                .hasMessage("fault scenario parameter schema cannot be evaluated");
    }

    @Test
    void shouldRejectExternalSchemaReferencesWithoutLoadingTheNetwork() {
        String schema = """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$ref": "https://example.com/untrusted-schema"
                }
                """;

        assertThatThrownBy(() -> validator.validate(schema, "{}"))
                .isInstanceOf(InvalidFaultScenarioSchemaException.class)
                .hasMessage("fault scenario parameter schema cannot be evaluated");
    }
}
