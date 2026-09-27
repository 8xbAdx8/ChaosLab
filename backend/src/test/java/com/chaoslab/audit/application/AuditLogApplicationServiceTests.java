package com.chaoslab.audit.application;

import com.chaoslab.audit.application.port.AuditLogRepository;
import com.chaoslab.audit.domain.AuditLog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogApplicationServiceTests {

    @Test
    void refusesToReturnTruncatedExecutionEvidence() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        UUID experimentId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecution(
                experimentId,
                executionId,
                AuditLogApplicationService.MAX_QUERY_LIMIT + 1
        )).thenReturn(Collections.nCopies(
                AuditLogApplicationService.MAX_QUERY_LIMIT + 1,
                mock(AuditLog.class)
        ));

        AuditLogApplicationService service = new AuditLogApplicationService(
                repository,
                Clock.systemUTC()
        );
        assertThatThrownBy(() -> service.findByExecution(experimentId, executionId))
                .isInstanceOf(InvalidAuditQueryException.class)
                .hasMessageContaining("more than 200");
        verify(repository).findByExecution(
                experimentId,
                executionId,
                AuditLogApplicationService.MAX_QUERY_LIMIT + 1
        );
    }
}
