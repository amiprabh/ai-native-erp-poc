package com.aierp.controller;

import com.aierp.dto.JournalEntrySummary;
import com.aierp.service.JournalEntryApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/journal-entries")
public class JournalEntryApprovalController {

    private final JournalEntryApprovalService approvalService;

    public JournalEntryApprovalController(JournalEntryApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/pending")
    public List<JournalEntrySummary> listPending() {
        return approvalService.listPending();
    }

    @GetMapping("/by-invoice/{invoiceId}")
    public List<JournalEntrySummary> byInvoice(@PathVariable String invoiceId) {
        return approvalService.findByInvoiceId(invoiceId);
    }

    @PostMapping("/{entryId}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID entryId) {
        try {
            return ResponseEntity.ok(approvalService.approve(entryId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{entryId}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID entryId) {
        try {
            return ResponseEntity.ok(approvalService.reject(entryId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }
}