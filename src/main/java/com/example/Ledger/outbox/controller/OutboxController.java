package com.example.Ledger.outbox.controller;

import com.example.Ledger.outbox.dto.OutboxEventPageResponse;
import com.example.Ledger.outbox.dto.OutboxEventResponse;
import com.example.Ledger.outbox.model.OutboxStatus;
import com.example.Ledger.outbox.service.OutboxEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/outbox")
public class OutboxController {

    private final OutboxEventService outboxEventService;

    public OutboxController(OutboxEventService outboxEventService) {
        this.outboxEventService = outboxEventService;
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OutboxEventPageResponse> listEvents(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(outboxEventService.listEvents(status, page, size));
    }

    @PostMapping("/events/{eventId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OutboxEventResponse> retryEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(outboxEventService.retryEvent(eventId));
    }
}
