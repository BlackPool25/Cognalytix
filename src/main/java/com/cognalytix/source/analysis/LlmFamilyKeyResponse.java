package com.cognalytix.source.analysis;

/** LLM assigns a short stable slug used as {@code family_key} for semantic clustering. */
public record LlmFamilyKeyResponse(String familyKey) {}
