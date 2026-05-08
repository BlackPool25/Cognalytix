package com.cognalytix.source.dto.insights;

import java.util.UUID;

public record GrowthMirrorResponse(
        UUID entryId,
        String analysisStatus,
        boolean mirrorReady,
        boolean hasTrajectory,
        GrowthDayMirrorPayload day,
        GrowthTrajectoryMirrorPayload trajectory,
        String patternType) {}
