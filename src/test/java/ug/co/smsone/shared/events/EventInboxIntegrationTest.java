package ug.co.smsone.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

class EventInboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventInbox inbox;

    @Test
    void exactlyOncePerListenerAndMessage() {
        assertThat(inbox.recordIfNew("settings-audit", "setting:branding.title:1")).isTrue();
        assertThat(inbox.recordIfNew("settings-audit", "setting:branding.title:1")).isFalse();

        // a different listener processes the same message independently
        assertThat(inbox.recordIfNew("search-indexer", "setting:branding.title:1")).isTrue();
        // and the same listener processes the next version
        assertThat(inbox.recordIfNew("settings-audit", "setting:branding.title:2")).isTrue();
    }
}
