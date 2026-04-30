package com.cognalytix.source.domain.journal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JournalEntrySectionRepository extends JpaRepository<JournalEntrySection, UUID> {

    List<JournalEntrySection> findByJournalEntryIdOrderBySortOrderAsc(UUID entryId);

    void deleteByJournalEntryId(UUID entryId);

    @Query(
            """
            SELECT s FROM JournalEntrySection s
            JOIN s.journalEntry e
            WHERE s.user.id = :userId
            AND e.deletedAt IS NULL
            AND e.createdAt >= :from
            AND e.createdAt < :toExclusive
            ORDER BY e.createdAt DESC, s.sortOrder ASC
            """)
    Page<JournalEntrySection> findByUserIdAndEntryCreatedAtRange(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            Pageable pageable);
}
