package com.chaoslab.report.infrastructure.prometheus;

import com.chaoslab.report.domain.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ReportObservationVersionTests {
    @Test void oldJsonReadsAsUnknownAndNewVersionRoundTripsWithoutMigration() {
        var mapper = JsonMapper.builder().build();
        var legacy = new ReportWindow("before", Instant.EPOCH, Instant.EPOCH.plusSeconds(30), ReportMetricsStatus.OBSERVED,
                6.0, 12.0, 0.0, 0.0, 0.1, null);
        String oldJson = mapper.writeValueAsString(legacy).replace(",\"collectionVersion\":null", "");
        assertThat(mapper.readValue(oldJson, ReportWindow.class)).isEqualTo(legacy);
        assertThat(mapper.readValue(oldJson, ReportWindow.class).collectionVersion()).isNull();
        var v2 = new ReportWindow("during", Instant.EPOCH, Instant.EPOCH.plusSeconds(30), ReportMetricsStatus.OBSERVED,
                6.0, 120.0, 0.0, 0.0, 0.1, null, "PROMETHEUS_HTTP_V2");
        assertThat(mapper.readValue(mapper.writeValueAsString(v2), ReportWindow.class)).isEqualTo(v2);
    }
}
