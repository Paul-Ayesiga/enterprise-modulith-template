package ug.co.smsone.gateway.platform;

import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.usage.UsageSink;

/**
 * Ships per-consumer request counts to the platform's usage-report seam, authenticated by the
 * shared gateway secret — the audit sink's exact shape. The caller (the flusher) owns retry
 * semantics; this stays a thin post.
 */
class ModulithUsageSink implements UsageSink {

    private final WebClient webClient;
    private final String secret;

    ModulithUsageSink(String uri, String secret) {
        this.webClient = WebClient.create(uri);
        this.secret = secret;
    }

    @Override
    public Mono<Void> publish(Map<String, Long> countsByConsumer) {
        return webClient.post()
                .header("X-Gateway-Secret", secret)
                .bodyValue(Map.of("counts", countsByConsumer))
                .retrieve()
                .toBodilessEntity()
                .then();
    }
}
