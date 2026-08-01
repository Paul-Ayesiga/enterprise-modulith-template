package ug.co.smsone.search.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.identity.UserProvisioned;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.shared.events.EventInbox;

/**
 * Feeds the projection from events other modules already publish (their API packages — the legal
 * dependency direction). Deliberately only the events that carry enough to index: organizations at
 * registration (alias, org-scoped) and users at provisioning (email, platform-wide — null org, so
 * only the admin search sees people). Everything richer arrives through the {@code SearchIndex}
 * port at its producer's hand — the document module is the reference producer. Idempotent via
 * {@link EventInbox}; the upsert's conflict target makes redelivery a no-op regardless.
 */
@Component
class SearchEventListeners {

    private static final String LISTENER_ID = "search";

    private final SearchIndexStore store;
    private final EventInbox inbox;

    SearchEventListeners(SearchIndexStore store, EventInbox inbox) {
        this.store = store;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    void on(OrganizationRegistered event) {
        if (!inbox.recordIfNew(LISTENER_ID, "org:" + event.orgId() + "@" + event.occurredAt())) {
            return;
        }
        store.upsert(new SearchDoc(event.orgId(), "organization", event.orgId().toString(),
                event.alias(), event.alias()));
    }

    @ApplicationModuleListener
    void on(UserProvisioned event) {
        if (!inbox.recordIfNew(LISTENER_ID, "user:" + event.subject() + "@" + event.occurredAt())) {
            return;
        }
        store.upsert(new SearchDoc(null, "user", event.subject(), event.email(), event.email()));
    }
}
