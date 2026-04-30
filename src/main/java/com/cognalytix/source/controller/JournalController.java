package com.cognalytix.source.controller;

import com.cognalytix.source.dto.journal.JournalResponse;
import com.cognalytix.source.dto.journal.JournalWriteRequest;
import com.cognalytix.source.security.AuthUserPrincipal;
import com.cognalytix.source.service.JournalService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/journals")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class JournalController {

    private final JournalService journalService;

    public JournalController(JournalService journalService) {
        this.journalService = journalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JournalResponse create(@Valid @RequestBody JournalWriteRequest request, Authentication authentication) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        return journalService.create(request, p.getId());
    }

    @GetMapping
    public Page<JournalResponse> list(
            Authentication authentication,
            @PageableDefault(size = 10) Pageable pageable) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        return journalService.list(p.getId(), pageable);
    }

    @GetMapping("/{id}")
    public JournalResponse get(@PathVariable("id") UUID id, Authentication authentication) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        return journalService.get(id, p.getId());
    }

    @PutMapping("/{id}")
    public JournalResponse update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody JournalWriteRequest request,
            Authentication authentication) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        return journalService.update(id, request, p.getId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDelete(@PathVariable("id") UUID id, Authentication authentication) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        journalService.softDelete(id, p.getId());
    }

    @PostMapping("/{id}/reanalyze")
    public JournalResponse reanalyze(@PathVariable("id") UUID id, Authentication authentication) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        return journalService.reanalyze(id, p.getId());
    }

    @DeleteMapping("/{id}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permanentDelete(@PathVariable("id") UUID id, Authentication authentication) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        journalService.permanentDelete(id, p.getId());
    }
}
