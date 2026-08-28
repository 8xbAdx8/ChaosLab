package com.chaoslab.safety.interfaces.rest;

import com.chaoslab.safety.application.AuditedEmergencyStopApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/emergency-stop")
public class EmergencyStopController {

    private final AuditedEmergencyStopApplicationService emergencyStopService;
    private final EmergencyStopResponseMapper responseMapper;

    public EmergencyStopController(
            AuditedEmergencyStopApplicationService emergencyStopService,
            EmergencyStopResponseMapper responseMapper
    ) {
        this.emergencyStopService = emergencyStopService;
        this.responseMapper = responseMapper;
    }

    @PostMapping
    public EmergencyStopResponse activate() {
        return responseMapper.from(emergencyStopService.activate());
    }
}