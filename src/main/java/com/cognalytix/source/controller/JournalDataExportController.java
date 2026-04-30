package com.cognalytix.source.controller;

import com.cognalytix.source.dto.journal.JournalSectionExportRow;
import com.cognalytix.source.security.AuthUserPrincipal;
import com.cognalytix.source.service.JournalSectionExportService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Tabular section data for external analytics tools. {@code to} is an <strong>exclusive</strong> upper
 * bound on {@code journal entry createdAt}.
 */
@RestController
@RequestMapping("/api/exports")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class JournalDataExportController {

    private final JournalSectionExportService journalSectionExportService;

    public JournalDataExportController(JournalSectionExportService journalSectionExportService) {
        this.journalSectionExportService = journalSectionExportService;
    }

    @GetMapping("/journal-sections")
    public Page<JournalSectionExportRow> listJournalSections(
            Authentication auth,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 100) Pageable pageable) {
        AuthUserPrincipal p = (AuthUserPrincipal) auth.getPrincipal();
        return journalSectionExportService.exportSections(p.getId(), from, to, pageable);
    }
}
