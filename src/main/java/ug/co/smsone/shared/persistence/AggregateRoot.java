package ug.co.smsone.shared.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * Base for aggregate roots: registered domain events are published by Spring Data on
 * {@code repository.save(..)} and routed through the Spring Modulith event infrastructure.
 */
@MappedSuperclass
public abstract class AggregateRoot extends BaseEntity {

    @Transient
    private final transient List<Object> domainEvents = new ArrayList<>();

    protected void registerEvent(Object event) {
        domainEvents.add(event);
    }

    @DomainEvents
    protected List<Object> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    @AfterDomainEventPublication
    protected void clearDomainEvents() {
        domainEvents.clear();
    }
}
