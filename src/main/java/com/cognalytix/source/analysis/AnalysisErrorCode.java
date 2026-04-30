package com.cognalytix.source.analysis;

/**
 * Short machine-readable codes stored in {@code journal_entries.last_analysis_error} (never stack traces).
 */
public enum AnalysisErrorCode {
    LLM_UNREACHABLE("llm_unreachable"),
    LLM_INVALID_RESPONSE("llm_invalid_response"),
    VALIDATION_FAILED("validation_failed"),
    PERSIST_FAILED("persist_failed"),
    /** Catch-all; prefer specific codes. */
    INTERNAL("internal_error");

    private final String code;

    AnalysisErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
