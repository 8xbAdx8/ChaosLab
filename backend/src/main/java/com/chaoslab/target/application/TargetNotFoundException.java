package com.chaoslab.target.application;

import java.util.UUID;

public class TargetNotFoundException extends RuntimeException {

    public TargetNotFoundException(UUID targetId) {
        super("target not found: " + targetId);
    }
}
