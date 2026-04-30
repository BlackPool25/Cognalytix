package com.cognalytix.source.dto.journal;

import com.cognalytix.source.dto.user.UserLabelRef;

import java.util.UUID;

/**
 * One topic-bounded block with the user's emotional label for that block; suitable for later BI
 * tools.
 */
public record JournalSectionPayload(
        UUID id, int sortOrder, UserLabelRef topic, UserLabelRef emotion, String content, int intensity) {}
