package com.chaoslab.console.history;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed archive only: no HTTP paths, imports, engine calls or database dependency. */
@RestController
public class HistoryEvidenceController {

    private final HistoryEvidenceReader reader;

    public HistoryEvidenceController(HistoryEvidenceReader reader) {
        this.reader = reader;
    }

    @GetMapping("/api/v1/console/history/m1-core")
    public HistoryEvidenceReader.History read() {
        return reader.read();
    }
}
