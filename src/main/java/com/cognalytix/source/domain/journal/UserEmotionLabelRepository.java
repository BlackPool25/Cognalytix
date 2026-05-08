package com.cognalytix.source.domain.journal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserEmotionLabelRepository extends JpaRepository<UserEmotionLabel, UUID> {

    Optional<UserEmotionLabel> findByUserIdAndNormalizedKey(UUID userId, String normalizedKey);

    Page<UserEmotionLabel> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<UserEmotionLabel> findAllByUser_IdOrderByLabelAsc(UUID userId);

    @Query("SELECT DISTINCT e.familyKey FROM UserEmotionLabel e WHERE e.user.id = :userId AND e.familyKey IS NOT NULL")
    List<String> findDistinctFamilyKeysByUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM user_emotion_labels WHERE user_id = :userId AND label_data->>'needs_llm_backfill' = 'true'", nativeQuery = true)
    List<UserEmotionLabel> findLabelsNeedingBackfill(@Param("userId") UUID userId);
}