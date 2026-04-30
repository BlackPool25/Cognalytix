package com.cognalytix.source.dto.insights;

import java.util.Map;

public record GrowthTrajectoryMirrorPayload(
        String kind, GrowthMirrorCardPayload mirrorCard, Map<String, Object> trajectoryFacts) {}
