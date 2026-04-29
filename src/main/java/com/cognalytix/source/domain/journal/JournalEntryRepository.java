package com.cognalytix.source.domain.journal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Page<JournalEntry> findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<JournalEntry> findByIdAndUserId(UUID id, UUID userId);

    Optional<JournalEntry> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
}
