package com.cognalytix.source.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Component
public class SidecarClient {

    private static final Logger log = LoggerFactory.getLogger(SidecarClient.class);

    private final RestClient restClient;

    public SidecarClient(
            @Value("${app.sidecar.url:http://localhost:8001}") String sidecarUrl) {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        rf.setReadTimeout((int) Duration.ofSeconds(60).toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(sidecarUrl)
                .requestFactory(rf)
                .build();
    }

    public SidecarAnalysisResponse analyze(String title, String content) {
        log.info("Sending journal text to sidecar for classification...");
        return restClient.post()
                .uri("/analyze")
                .body(new SidecarAnalysisRequest(title, content))
                .retrieve()
                .body(SidecarAnalysisResponse.class);
    }

    public record SidecarAnalysisRequest(String title, String content) {}

    public record SidecarSectionResult(
            String content,
            String rawTopic,
            String rawEmotion,
            int intensity
    ) {}

    public record SidecarSummaryResult(
            String dominantMood,
            int intensity,
            List<String> themeHints
    ) {}

    public record SidecarAnalysisResponse(
            List<SidecarSectionResult> sections,
            SidecarSummaryResult summary
    ) {}
}
