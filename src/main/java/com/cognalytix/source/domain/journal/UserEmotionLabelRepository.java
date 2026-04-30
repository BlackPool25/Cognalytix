package com.cognalytix.source.domain.journal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserEmotionLabelRepository extends JpaRepository<UserEmotionLabel, UUID> {

    Optional<UserEmotionLabel> findByUserIdAndNormalizedKey(UUID userId, String normalizedKey);

    Page<UserEmotionLabel> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<UserEmotionLabel> findAllByUser_IdOrderByLabelAsc(UUID userId);
}
