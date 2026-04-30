package com.cognalytix.source.domain.journal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GrowthInsightRepository extends JpaRepository<GrowthInsight, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM GrowthInsight g WHERE g.triggerEntry.id = :entryId AND g.insightType = :type")
    void deleteByTriggerEntryIdAndInsightType(
            @Param("entryId") UUID entryId, @Param("type") GrowthInsightType type);

    Optional<GrowthInsight> findFirstByUser_IdAndTriggerEntry_IdAndInsightTypeOrderByCreatedAtDesc(
            UUID userId, UUID triggerEntryId, GrowthInsightType insightType);
}
