package com.cognalytix.source.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pool for {@code @Async("analysisExecutor")} used by {@link com.cognalytix.source.analysis.JournalAnalysisService}.
 */
@ConfigurationProperties(prefix = "app.async")
public record AsyncTaskProperties(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity) {
    public AsyncTaskProperties {
        if (corePoolSize < 1) {
            corePoolSize = 1;
        }
        if (maxPoolSize < corePoolSize) {
            maxPoolSize = corePoolSize;
        }
        if (queueCapacity < 0) {
            queueCapacity = 0;
        }
    }
}
