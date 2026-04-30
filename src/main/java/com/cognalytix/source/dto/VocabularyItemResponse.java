package com.cognalytix.source.dto;

import java.time.Instant;
import java.util.UUID;

public record VocabularyItemResponse(UUID id, String label, String normalizedKey, Instant createdAt) {}
