package com.chaoslab.target.interfaces.rest;

import com.chaoslab.target.application.TargetApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/targets")
public class TargetController {

    private final TargetApplicationService targetApplicationService;

    public TargetController(TargetApplicationService targetApplicationService) {
        this.targetApplicationService = targetApplicationService;
    }

    @PostMapping
    public ResponseEntity<TargetResponse> register(
            @Valid @RequestBody RegisterTargetRequest request
    ) {
        TargetResponse response = TargetResponse.from(
                targetApplicationService.register(request.toCommand())
        );
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{targetId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{targetId}")
    public TargetResponse findById(@PathVariable UUID targetId) {
        return TargetResponse.from(targetApplicationService.findById(targetId));
    }

    @GetMapping
    public List<TargetResponse> findAll() {
        return targetApplicationService.findAll().stream()
                .map(TargetResponse::from)
                .toList();
    }
}
