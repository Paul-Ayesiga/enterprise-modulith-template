package ug.co.smsone.search;

import java.util.UUID;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Org-scoped search port for other protocol surfaces (the MCP module today) — the same ranked
 * full-text-then-trigram query the search REST endpoint runs, always tenant-scoped (the
 * platform-wide admin search deliberately has no port).
 */
public interface SearchQueries {

    WindowedResult<HitView> search(UUID orgId, String query, String entityType, CursorPageRequest page);

    record HitView(String entityType, String entityId, String title, String snippet, double score) {
    }
}
