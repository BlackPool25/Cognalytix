package com.cognalytix.source.controller;

import com.cognalytix.source.dto.VocabularyItemResponse;
import com.cognalytix.source.security.AuthUserPrincipal;
import com.cognalytix.source.service.UserLabelService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vocabulary")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class UserVocabularyController {

    private final UserLabelService userLabelService;

    public UserVocabularyController(UserLabelService userLabelService) {
        this.userLabelService = userLabelService;
    }

    /** Per-user topic labels (for deduplication / BI joins). */
    @GetMapping("/topics")
    public Page<VocabularyItemResponse> listTopics(Authentication auth, @PageableDefault(size = 50) Pageable pageable) {
        AuthUserPrincipal p = (AuthUserPrincipal) auth.getPrincipal();
        return userLabelService.listTopicLabels(p.getId(), pageable);
    }

    /** Per-user emotion labels (growing vocabulary, deduplicated by normalized key). */
    @GetMapping("/emotions")
    public Page<VocabularyItemResponse> listEmotions(Authentication auth, @PageableDefault(size = 50) Pageable pageable) {
        AuthUserPrincipal p = (AuthUserPrincipal) auth.getPrincipal();
        return userLabelService.listEmotionLabels(p.getId(), pageable);
    }
}
