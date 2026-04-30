package com.cognalytix.source.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pool for journal LLM analysis. Tuned via {@code app.async.*} in application.yml.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AsyncTaskProperties.class)
public class AsyncConfig {

    @Bean(name = "analysisExecutor")
    public TaskExecutor analysisExecutor(AsyncTaskProperties p) {
        var ex = new ThreadPoolTaskExecutor() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = super.newThread(r);
                t.setDaemon(true);
                return t;
            }
        };
        ex.setCorePoolSize(p.corePoolSize());
        ex.setMaxPoolSize(p.maxPoolSize());
        ex.setQueueCapacity(p.queueCapacity());
        ex.setThreadNamePrefix("analysis-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
