package com.cognalytix.source.analysis;

/**
 * Structured mirror copy: observation + trajectory + how today sits in it — not generic chat prose.
 * Serialized to {@code growth_insights.pattern_data} under {@code mirrorCard}.
 */
public record LlmMirrorCardStructured(
        String headline,
        String trajectoryLine,
        String dayAnchorLine,
        String integratedBody,
        String direction) {}
