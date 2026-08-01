package ug.co.smsone.exchange.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ug.co.smsone.exchange.ExchangeHandler;

/** Every {@link ExchangeHandler} bean, by id. Duplicate ids fail at startup, not at first use. */
@Component
class HandlerRegistry {

    private final Map<String, ExchangeHandler> handlers;

    HandlerRegistry(List<ExchangeHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(ExchangeHandler::id, handler -> handler));
    }

    Optional<ExchangeHandler> find(String id) {
        return Optional.ofNullable(handlers.get(id));
    }

    List<ExchangeHandler> all() {
        return List.copyOf(handlers.values());
    }
}
