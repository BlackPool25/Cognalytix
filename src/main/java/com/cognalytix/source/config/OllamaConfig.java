package com.cognalytix.source.config;

import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.CompletableFuture;

@Configuration
public class OllamaConfig {

    private static final Logger log = LoggerFactory.getLogger(OllamaConfig.class);

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${app.ollama.embedding-model:nomic-embed-text}")
    private String embeddingModel;

    /**
     * Primary chat model — narration (mirror card) and insight/coping generation.
     * Bound to {@code app.ollama.chat-model}.
     */
    @Value("${app.ollama.chat-model:qwen3.5:4b}")
    private String chatModel;

    /**
     * Secondary chat model — reserved for future opt-in personalisation.
     * Not called in the hot analysis path after sidecar-trust fixes.
     * Bound to {@code app.ollama.label-model}.
     */
    @Value("${app.ollama.label-model:qwen3.5:0.8b}")
    private String labelModel;

    @Value("${app.analysis.enabled:true}")
    private boolean analysisEnabled;

    /**
     * Single pooled OllamaApi shared by all chat and embedding beans.
     *
     * <p>Uses {@link HttpComponentsClientHttpRequestFactory} backed by a
     * {@link PoolingHttpClientConnectionManager} (20 total connections).
     * Replaces {@code SimpleClientHttpRequestFactory} which opened a new TCP
     * socket on every request — with 12–35 requests per journal entry on the
     * old code, this was significant overhead.
     *
     * <p>The Ollama server runs on the host machine at {@code OLLAMA_BASE_URL}
     * (default {@code http://localhost:11434}). Inside Docker it is reached via
     * {@code host.docker.internal:11434} as set in the root {@code docker-compose.yml}.
     * No Ollama container is started inside the Compose stack.
     */
    @Bean
    public OllamaApi ollamaApi() {
        // Apache HttpClient 5 — connect timeout belongs in ConnectionConfig,
        // not the factory (setConnectTimeout was removed in Spring 7).
        var connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(60))
                .build();

        // Pool acquire timeout + response timeout live in RequestConfig.
        var requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(60))
                .setResponseTimeout(Timeout.ofMinutes(5))
                .build();

        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(20)
                .build();

        var httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        return OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)))
                .build();
    }

    /**
     * Primary {@link ChatClient.Builder} — wired into {@link com.cognalytix.source.analysis.JournalAnalysisService},
     * {@link com.cognalytix.source.analysis.MirrorNarrationService}, and
     * {@link com.cognalytix.source.service.LabelBackfillService} via plain
     * {@code ChatClient.Builder} injection ({@code @Primary} handles disambiguation).
     *
     * <p>Model: {@code app.ollama.chat-model} (default {@code qwen2.5:3b}).
     */
    @Primary
    @Bean
    public ChatClient.Builder chatClientBuilder(OllamaApi api) {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(chatModel)
                .temperature(0.3)
                .numPredict(8192)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .observationRegistry(ObservationRegistry.NOOP)
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
        return ChatClient.builder(model);
    }

    /**
     * Secondary {@link ChatClient.Builder} for future opt-in personalisation features.
     * Reserved — not injected into the hot analysis path.
     *
     * <p>Model: {@code app.ollama.label-model} (default {@code qwen2.5:0.5b}).
     */
    @Bean
    @Qualifier("labelChatClientBuilder")
    public ChatClient.Builder labelChatClientBuilder(OllamaApi api) {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(labelModel)
                .temperature(0.1)
                .numPredict(32)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .observationRegistry(ObservationRegistry.NOOP)
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
        return ChatClient.builder(model);
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

    /**
     * Fires a lightweight prompt to both chat models and one embed call on a background thread
     * immediately after the application context starts. Eliminates the first-call cold-start penalty
     * (2+ minutes on CPU) that would otherwise hit the first real journal entry.
     *
     * <p>Guarded by {@code app.analysis.enabled}: tests and CI runs with
     * {@code ANALYSIS_ENABLED=false} are not affected.
     *
     * <p>Ollama is running on the host machine. Warmup calls go through
     * {@code OLLAMA_BASE_URL} (host-to-Ollama path, or host.docker.internal inside Docker).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmModels(ApplicationReadyEvent event) {
        if (!analysisEnabled) {
            log.debug("Analysis disabled — skipping model warmup.");
            return;
        }
        ChatClient.Builder primary = event.getApplicationContext().getBean(ChatClient.Builder.class);
        ChatClient.Builder label = (ChatClient.Builder) event.getApplicationContext()
                .getBean("labelChatClientBuilder");
        OllamaEmbeddingModel embedding = event.getApplicationContext()
                .getBean(OllamaEmbeddingModel.class);

        CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming primary chat model ({})...", chatModel);
                primary.build().prompt("hi").call().content();
                log.info("Warming label chat model ({})...", labelModel);
                label.build().prompt("hi").call().content();
                log.info("Warming embedding model ({})...", embeddingModel);
                embedding.embed("warmup");
                log.info("Ollama model warmup complete.");
            } catch (Exception e) {
                log.warn("Model warmup failed (non-fatal, Ollama may not be ready yet): {}", e.getMessage());
            }
        });
    }
}