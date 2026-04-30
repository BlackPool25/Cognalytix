package com.cognalytix.source.controller;

import com.cognalytix.source.dto.insights.GrowthMirrorResponse;
import com.cognalytix.source.security.AuthUserPrincipal;
import com.cognalytix.source.service.GrowthInsightService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/insights/growth")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class GrowthInsightController {

    private final GrowthInsightService growthInsightService;

    public GrowthInsightController(GrowthInsightService growthInsightService) {
        this.growthInsightService = growthInsightService;
    }

    @GetMapping("/latest")
    public GrowthMirrorResponse latestForEntry(
            @RequestParam("entryId") UUID entryId, Authentication authentication) {
        AuthUserPrincipal p = (AuthUserPrincipal) authentication.getPrincipal();
        return growthInsightService.getPostEntryMirror(p.getId(), entryId);
    }
}
