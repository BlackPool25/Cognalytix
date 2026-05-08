package com.cognalytix.source.domain.journal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabelBackfillStatusRepository extends JpaRepository<LabelBackfillStatus, UUID> {

    Optional<LabelBackfillStatus> findByLabelType(String labelType);
}