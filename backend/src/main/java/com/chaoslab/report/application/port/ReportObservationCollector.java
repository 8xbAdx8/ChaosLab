package com.chaoslab.report.application.port;

import com.chaoslab.report.domain.ReportWindow;
import java.util.List;

public interface ReportObservationCollector {
    List<ReportWindow> collect(List<ReportWindow> windows);
}
