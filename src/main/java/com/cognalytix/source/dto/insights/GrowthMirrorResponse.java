package com.cognalytix.source.dto.insights;

import java.util.UUID;

/** API response for post-entry mirror: structured day snapshot + optional trajectory card (never raw chat). */
public record GrowthMirrorResponse(
        UUID entryId,
        String analysisStatus,
        boolean mirrorReady,
        boolean hasTrajectory,
        GrowthDayMirrorPayload day,
        GrowthTrajectoryMirrorPayload trajectory) {}
