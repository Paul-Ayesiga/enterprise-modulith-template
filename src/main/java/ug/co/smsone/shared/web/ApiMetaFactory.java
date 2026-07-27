package ug.co.smsone.shared.web;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class ApiMetaFactory {

    public static final String API_VERSION = "1";

    private final Clock clock;

    public ApiMetaFactory(Clock clock) {
        this.clock = clock;
    }

    public ApiMeta create() {
        return new ApiMeta(currentRequestId(), Instant.now(clock), API_VERSION, null);
    }

    public String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId != null ? requestId : "unknown";
    }
}
