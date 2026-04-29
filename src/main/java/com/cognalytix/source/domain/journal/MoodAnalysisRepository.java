package com.cognalytix.source.domain.journal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MoodAnalysisRepository extends JpaRepository<MoodAnalysis, UUID> {

    Optional<MoodAnalysis> findByEntryId(UUID entryId);
}
