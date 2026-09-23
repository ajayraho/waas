package com.waas.gateway.fanout;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The gateway's only call into Core: read models that need Postgres (names for the dashboard
 * snapshot, terminal states). Short timeouts, and failures degrade to "no update this tick"
 * rather than blocking fan-out.
 *
 * <p>Responses are relayed as raw JSON strings: the gateway never needs to understand a snapshot,
 * only forward it, so Core can evolve the shape without redeploying the gateway.
 */
@Component
public class CoreClient {

    private static final Logger log = LoggerFactory.getLogger(CoreClient.class);

    private final RestClient http;

    public CoreClient(@Value("${gateway.core-url:http://localhost:8080}") String coreUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(1_000);
        timeouts.setReadTimeout(2_000);
        this.http = RestClient.builder().baseUrl(coreUrl).requestFactory(timeouts).build();
    }

    public Optional<String> snapshot(UUID waitlistId) {
        return get("/api/waitlists/{w}/queue?limit=50", waitlistId);
    }

    public Optional<String> position(UUID waitlistId, UUID entryId) {
        return get("/api/waitlists/{w}/entries/{e}", waitlistId, entryId);
    }

    private Optional<String> get(String uri, Object... vars) {
        try {
            return Optional.ofNullable(http.get().uri(uri, vars).retrieve().body(String.class));
        } catch (RestClientException e) {
            log.debug("Core read {} failed: {}", uri, e.getMessage());
            return Optional.empty();
        }
    }
}
