package com.cognalytix.source.domain.journal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmotionLabelEmbeddingRepository extends JpaRepository<EmotionLabelEmbedding, UUID> {

    Optional<EmotionLabelEmbedding> findByLabelId(UUID labelId);

    @Query("SELECT e.label.id FROM EmotionLabelEmbedding e WHERE e.user.id = :userId")
    List<UUID> findLabelIdsByUserId(@Param("userId") UUID userId);

    @Query("SELECT e.label.id, e.embedding FROM EmotionLabelEmbedding e WHERE e.user.id = :userId")
    List<Object[]> findLabelIdAndEmbeddingByUserId(@Param("userId") UUID userId);

    boolean existsByLabelId(UUID labelId);

    void deleteByLabelId(UUID labelId);
}