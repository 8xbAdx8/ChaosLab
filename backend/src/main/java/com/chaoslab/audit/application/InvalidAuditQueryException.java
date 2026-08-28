package com.chaoslab.audit.application;

public class InvalidAuditQueryException extends RuntimeException {

    public InvalidAuditQueryException(String message) {
        super(message);
    }
}
