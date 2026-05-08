package com.cognalytix.source.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OllamaConfig {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${app.ollama.embedding-model:nomic-embed-text}")
    private String embeddingModel;

    @Bean
    public OllamaApi ollamaApi() {
        return OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
    }

    @Bean
    public OllamaEmbeddingModel ollamaEmbeddingModel(OllamaApi api) {
        OllamaEmbeddingOptions options = OllamaEmbeddingOptions.builder()
                .model(embeddingModel)
                .build();

        return new OllamaEmbeddingModel(
                api,
                options,
                ObservationRegistry.NOOP,
                ModelManagementOptions.defaults()
        );
    }
}