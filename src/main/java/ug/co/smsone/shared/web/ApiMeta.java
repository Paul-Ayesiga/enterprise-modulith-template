package ug.co.smsone.shared.web;

import java.time.Instant;

public record ApiMeta(String requestId, Instant timestamp, String apiVersion, PageMeta page) {

    public ApiMeta withPage(PageMeta page) {
        return new ApiMeta(requestId, timestamp, apiVersion, page);
    }
}
