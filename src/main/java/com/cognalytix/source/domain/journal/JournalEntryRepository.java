package com.cognalytix.source.domain.journal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    long countByUser_IdAndDeletedAtIsNull(UUID userId);

    Page<JournalEntry> findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<JournalEntry> findByIdAndUserId(UUID id, UUID userId);

    Optional<JournalEntry> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    /**
     * Find entries that are stuck in-progress (updatedAt older than the stale threshold).
     * These are candidates for stale-reset on startup or admin action.
     */
    @Query("SELECT e FROM JournalEntry e WHERE e.analysisInProgress = true AND e.updatedAt < :before")
    List<JournalEntry> findStaleInProgressEntries(@Param("before") Instant before);

    /**
     * Bulk-mark stale in-progress entries as FAILED without loading them into memory.
     * Returns the count of rows updated.
     */
    @Modifying
    @Query("""
            UPDATE JournalEntry e
            SET e.analysisInProgress = false,
                e.analysisStatus     = com.cognalytix.source.domain.journal.AnalysisStatus.FAILED,
                e.lastAnalysisError  = :errorCode,
                e.updatedAt          = :now
            WHERE e.analysisInProgress = true
              AND e.updatedAt < :before
            """)
    int resetStaleInProgressEntries(
            @Param("errorCode") String errorCode,
            @Param("before") Instant before,
            @Param("now") Instant now);
}
