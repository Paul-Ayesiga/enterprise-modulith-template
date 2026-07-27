package ug.co.smsone.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

class EventInboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventInbox inbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void exactlyOncePerListenerAndMessage() {
        assertThat(inbox.recordIfNew("settings-audit", "setting:branding.title:1")).isTrue();
        assertThat(inbox.recordIfNew("settings-audit", "setting:branding.title:1")).isFalse();

        // a different listener processes the same message independently
        assertThat(inbox.recordIfNew("search-indexer", "setting:branding.title:1")).isTrue();
        // and the same listener processes the next version
        assertThat(inbox.recordIfNew("settings-audit", "setting:branding.title:2")).isTrue();
    }

    @Test
    void recordJoinsTheCallersTransaction() {
        // a listener whose transaction rolls back must NOT keep the inbox record — otherwise the
        // redelivered event would be skipped forever with its side effects never applied
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            assertThat(inbox.recordIfNew("rollback-listener", "msg-1")).isTrue();
            status.setRollbackOnly();
        });

        assertThat(inbox.recordIfNew("rollback-listener", "msg-1"))
                .as("rolled-back processing must leave the message claimable")
                .isTrue();
    }
}
