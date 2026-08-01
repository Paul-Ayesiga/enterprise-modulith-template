package ug.co.smsone.gateway.traffic;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The circuit-breaker fallback: when a backend is failing or its circuit is open, the request forwards
 * here and the client gets a consistent 503 in the gateway envelope instead of the upstream's failure.
 */
@RestController
class FallbackController {

    @RequestMapping("/__fallback")
    ResponseEntity<String> fallback() {
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"code\":\"SERVICE_UNAVAILABLE\","
                        + "\"detail\":\"The upstream service is unavailable.\"}]}");
    }
}
