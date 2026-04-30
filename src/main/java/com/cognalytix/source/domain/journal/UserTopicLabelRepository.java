package com.cognalytix.source.domain.journal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserTopicLabelRepository extends JpaRepository<UserTopicLabel, UUID> {

    Optional<UserTopicLabel> findByUserIdAndNormalizedKey(UUID userId, String normalizedKey);

    Page<UserTopicLabel> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<UserTopicLabel> findAllByUser_IdOrderByLabelAsc(UUID userId);
}
