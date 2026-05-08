package com.cognalytix.source.domain.journal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TopicLabelEmbeddingRepository extends JpaRepository<TopicLabelEmbedding, UUID> {

    Optional<TopicLabelEmbedding> findByLabelId(UUID labelId);

    @Query("SELECT t.label.id FROM TopicLabelEmbedding t WHERE t.user.id = :userId")
    List<UUID> findLabelIdsByUserId(@Param("userId") UUID userId);

    @Query("SELECT t.label.id, t.embedding FROM TopicLabelEmbedding t WHERE t.user.id = :userId")
    List<Object[]> findLabelIdAndEmbeddingByUserId(@Param("userId") UUID userId);

    boolean existsByLabelId(UUID labelId);

    void deleteByLabelId(UUID labelId);
}