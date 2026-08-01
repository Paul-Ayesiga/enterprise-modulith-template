package ug.co.smsone.exchange.internal;

import java.time.Instant;
import java.util.UUID;

/** Read model of one job row — the store maps it; nothing mutates it. */
record ExchangeJob(UUID id, UUID orgId, String requester, String jobType, String handler,
        int handlerVersion, String format, String status, String sourceKey, String resultKey,
        String errorReportKey, long processed, long failed, long nextOffset, int attempts,
        boolean cancelRequested, String lastError, Instant createdAt) {

    static final String IMPORT = "IMPORT";
    static final String EXPORT = "EXPORT";

    static final String PENDING = "PENDING";
    static final String VALIDATING = "VALIDATING";
    static final String PROCESSING = "PROCESSING";
    static final String COMPLETED = "COMPLETED";
    static final String COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS";
    static final String FAILED = "FAILED";
    static final String CANCELLED = "CANCELLED";
}
