package ug.co.smsone.search.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.identity.PersonProvisioned;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.shared.events.EventInbox;

/**
 * Feeds the projection from events other modules already publish (their API packages — the legal
 * dependency direction). Deliberately only the events that carry enough to index: organizations at
 * registration (alias, org-scoped) and people at provisioning (email, platform-wide — null org, so
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

    /**
     * {@code entity_id} now carries {@code person.id} on {@code user} rows. The entity_type keeps the
     * name {@code user} — it is the API's word for a human with an account, and a search hit resolves
     * to {@code /api/v1/admin/users/{id}}, whose id is the person id too — but the KEY changed, so the
     * existing rows are stale. V22 states the fix and it is not an UPDATE: a projection is rebuilt
     * from its sources (drop the {@code user} rows, re-emit), because only dropping first makes the
     * re-key collision-free under {@code uq_search_entity}.
     */
    @ApplicationModuleListener
    void on(PersonProvisioned event) {
        if (!inbox.recordIfNew(LISTENER_ID, "person:" + event.personId() + "@" + event.occurredAt())) {
            return;
        }
        store.upsert(new SearchDoc(null, "user", event.personId().toString(), event.email(), event.email()));
    }
}
