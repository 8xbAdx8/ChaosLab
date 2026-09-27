package com.chaoslab.report.application;

public class ReportCreationRejectedException extends RuntimeException {

    private final String code;

    public ReportCreationRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
