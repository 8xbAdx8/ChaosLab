package com.chaoslab.console;

import com.chaoslab.execution.interfaces.rest.ExperimentExecutionResponse;
import com.chaoslab.experiment.interfaces.rest.ExperimentResponse;
import com.chaoslab.report.interfaces.rest.ExperimentReportResponse;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

/** Bounded database projections only. No ChaosEngine, process channel, or mutation dependency. */
@RestController
@RequestMapping("/api/v1/console")
public class ConsoleReadController {
    private final ConsoleReadService reads;
    public ConsoleReadController(ConsoleReadService reads) { this.reads = reads; }
    @GetMapping("/overview") public ConsoleReadService.Overview overview() { return reads.overview(); }
    @GetMapping("/experiments") public ConsoleReadService.Page<ExperimentResponse> experiments(
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="") String search, @RequestParam(required=false) String status) {
        return reads.experiments(page,size,search,status);
    }
    @GetMapping("/executions") public ConsoleReadService.Page<ExperimentExecutionResponse> executions(
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) UUID experimentId, @RequestParam(required=false) String status) {
        return reads.executions(page,size,experimentId,status);
    }
    @GetMapping("/executions/{id}") public ExperimentExecutionResponse execution(@PathVariable UUID id) { return reads.execution(id); }
    @GetMapping("/experiments/{id}") public ExperimentResponse experiment(@PathVariable UUID id) { return reads.experiment(id); }
    @GetMapping("/reports/{id}") public ExperimentReportResponse report(@PathVariable UUID id) { return reads.report(id); }
    @GetMapping("/executions/{id}/evidence") public ConsoleReadService.Evidence evidence(@PathVariable UUID id) { return reads.evidence(id); }
    @GetMapping("/audit-logs") public ConsoleReadService.Page<ConsoleReadService.PublicAudit> audits(
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) UUID experimentId, @RequestParam(required=false) String operation,
            @RequestParam(required=false) String result, @RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to) { return reads.audits(page,size,experimentId,operation,result,from,to); }
    @GetMapping("/reports") public ConsoleReadService.Page<ExperimentReportResponse> reports(
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) UUID executionId) { return reads.reports(page,size,executionId); }
}
