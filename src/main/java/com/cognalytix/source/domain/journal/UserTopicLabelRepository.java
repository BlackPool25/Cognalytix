package com.cognalytix.source.domain.journal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserTopicLabelRepository extends JpaRepository<UserTopicLabel, UUID> {

    Optional<UserTopicLabel> findByUserIdAndNormalizedKey(UUID userId, String normalizedKey);

    Page<UserTopicLabel> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<UserTopicLabel> findAllByUser_IdOrderByLabelAsc(UUID userId);

    @Query("SELECT DISTINCT t.familyKey FROM UserTopicLabel t WHERE t.user.id = :userId AND t.familyKey IS NOT NULL")
    List<String> findDistinctFamilyKeysByUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM user_topic_labels WHERE user_id = :userId AND label_data->>'needs_llm_backfill' = 'true'", nativeQuery = true)
    List<UserTopicLabel> findLabelsNeedingBackfill(@Param("userId") UUID userId);
}