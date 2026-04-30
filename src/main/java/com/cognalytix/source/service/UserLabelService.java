package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserEmotionLabelRepository;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import com.cognalytix.source.domain.journal.UserTopicLabelRepository;
import com.cognalytix.source.domain.user.User;
import com.cognalytix.source.domain.user.UserRepository;
import com.cognalytix.source.dto.VocabularyItemResponse;
import com.cognalytix.source.util.LabelNormalizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class UserLabelService {

    private final UserTopicLabelRepository userTopicLabelRepository;
    private final UserEmotionLabelRepository userEmotionLabelRepository;
    private final UserRepository userRepository;

    public UserLabelService(
            UserTopicLabelRepository userTopicLabelRepository,
            UserEmotionLabelRepository userEmotionLabelRepository,
            UserRepository userRepository) {
        this.userTopicLabelRepository = userTopicLabelRepository;
        this.userEmotionLabelRepository = userEmotionLabelRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Page<VocabularyItemResponse> listTopicLabels(UUID userId, Pageable pageable) {
        return userTopicLabelRepository
                .findByUserIdOrderByCreatedAtDesc(Objects.requireNonNull(userId), pageable)
                .map(this::toTopicDto);
    }

    @Transactional(readOnly = true)
    public Page<VocabularyItemResponse> listEmotionLabels(UUID userId, Pageable pageable) {
        return userEmotionLabelRepository
                .findByUserIdOrderByCreatedAtDesc(Objects.requireNonNull(userId), pageable)
                .map(this::toEmotionDto);
    }

    @Transactional(readOnly = true)
    public List<String> listTopicLabelTextsForPrompt(UUID userId) {
        return userTopicLabelRepository.findAllByUser_IdOrderByLabelAsc(Objects.requireNonNull(userId)).stream()
                .map(UserTopicLabel::getLabel)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> listEmotionLabelTextsForPrompt(UUID userId) {
        return userEmotionLabelRepository.findAllByUser_IdOrderByLabelAsc(Objects.requireNonNull(userId)).stream()
                .map(UserEmotionLabel::getLabel)
                .toList();
    }

    /**
     * Maps LLM output to an existing row when {@link LabelNormalizer} key matches or stored label equals
     * ignoring case; otherwise creates one row (same as {@link #findOrCreateEmotionLabel(UUID, String)}).
     */
    @Transactional
    public UserEmotionLabel resolveEmotionFromModel(UUID userId, String phrase) {
        Objects.requireNonNull(userId);
        String key = validateAndNormalizeKey(phrase);
        return userEmotionLabelRepository
                .findByUserIdAndNormalizedKey(userId, key)
                .orElseGet(
                        () -> {
                            String trimmed = phrase.trim();
                            for (UserEmotionLabel l :
                                    userEmotionLabelRepository.findAllByUser_IdOrderByLabelAsc(userId)) {
                                if (l.getLabel().equalsIgnoreCase(trimmed)) {
                                    return l;
                                }
                            }
                            return findOrCreateEmotionLabel(userId, phrase);
                        });
    }

    /** Same resolution strategy as emotions. */
    @Transactional
    public UserTopicLabel resolveTopicFromModel(UUID userId, String phrase) {
        Objects.requireNonNull(userId);
        String key = validateAndNormalizeKey(phrase);
        return userTopicLabelRepository
                .findByUserIdAndNormalizedKey(userId, key)
                .orElseGet(
                        () -> {
                            String trimmed = phrase.trim();
                            for (UserTopicLabel l :
                                    userTopicLabelRepository.findAllByUser_IdOrderByLabelAsc(userId)) {
                                if (l.getLabel().equalsIgnoreCase(trimmed)) {
                                    return l;
                                }
                            }
                            return findOrCreateTopicLabel(userId, phrase);
                        });
    }

    /**
     * Deduplicate by {@link LabelNormalizer#normalizeKey(String)}; the display label is the
     * first phrasing we stored for that key, unless you later add a rename flow.
     */
    @Transactional
    public UserTopicLabel findOrCreateTopicLabel(UUID userId, String rawPhrasing) {
        User user = userRepository.getReferenceById(Objects.requireNonNull(userId));
        String key = validateAndNormalizeKey(rawPhrasing);
        return userTopicLabelRepository
                .findByUserIdAndNormalizedKey(user.getId(), key)
                .orElseGet(() -> {
                    UserTopicLabel l = new UserTopicLabel();
                    l.setUser(user);
                    l.setNormalizedKey(key);
                    l.setLabel(rawPhrasing.trim());
                    return userTopicLabelRepository.save(l);
                });
    }

    @Transactional
    public UserEmotionLabel findOrCreateEmotionLabel(UUID userId, String rawPhrasing) {
        User user = userRepository.getReferenceById(Objects.requireNonNull(userId));
        String key = validateAndNormalizeKey(rawPhrasing);
        return userEmotionLabelRepository
                .findByUserIdAndNormalizedKey(user.getId(), key)
                .orElseGet(() -> {
                    UserEmotionLabel l = new UserEmotionLabel();
                    l.setUser(user);
                    l.setNormalizedKey(key);
                    l.setLabel(rawPhrasing.trim());
                    return userEmotionLabelRepository.save(l);
                });
    }

    private static String validateAndNormalizeKey(String rawPhrasing) {
        if (rawPhrasing == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label text is required");
        }
        String key = LabelNormalizer.normalizeKey(rawPhrasing);
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label text is empty or unusable");
        }
        if (key.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label text is too long");
        }
        return key;
    }

    private VocabularyItemResponse toTopicDto(UserTopicLabel t) {
        return new VocabularyItemResponse(t.getId(), t.getLabel(), t.getNormalizedKey(), t.getCreatedAt());
    }

    private VocabularyItemResponse toEmotionDto(UserEmotionLabel e) {
        return new VocabularyItemResponse(e.getId(), e.getLabel(), e.getNormalizedKey(), e.getCreatedAt());
    }
}
