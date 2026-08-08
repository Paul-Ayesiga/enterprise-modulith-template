package ug.co.smsone.shared.persistence;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Declares the PLATFORM axis for everything that touches the database during application startup, and
 * gives it back once the application is serving.
 *
 * <p><b>Why this exists.</b> {@code TenantRoutingDataSource} sets {@code search_path} from
 * {@code TenantContext} on every borrow, and the default state — no axis — resolves to the empty
 * {@code no_tenant} schema (ADR 0010 §3.3). That is right for a request: a caller whose tenant was never
 * established must not read anyone's rows. It is wrong for BOOTSTRAP, which has no request and no tenant
 * and yet legitimately reads the database — and the failure it produces is a puzzling one, because
 * Hibernate resolves its schema metadata while the {@code EntityManagerFactory} is being built. Borrow a
 * connection with no axis at that moment and Hibernate records the poison schema as the default it will
 * later validate against, so the mapping check fails with {@code missing table [api_key]} against a
 * database where {@code api_key} is plainly present.
 *
 * <p>So bootstrap declares itself, rather than the DataSource guessing on its behalf. The alternative —
 * having "no axis" fall back to the platform schema — is the fail-open §3.3 forbids: it would make every
 * unpinned REQUEST read platform data instead of failing, and it would do so silently.
 *
 * <p>It is an {@code ApplicationContextInitializer} and not a listener because of WHEN the damage is
 * done. {@code ContextRefreshedEvent} fires after the singletons are instantiated — the EntityManager
 * factory has already probed the database by then, and the wrong default is already recorded. This runs
 * before refresh begins, so every borrow of the whole startup, on this thread, has an axis.
 *
 * <p>The pin is RELEASED on {@link ApplicationReadyEvent}. Leaving it would be a slow leak with an
 * unpleasant shape: the boot thread is returned to the platform pool, and a scheduled job or {@code
 * @Async} listener later running on it would inherit an axis it never declared — passing its tests and
 * silently reading the wrong schema the day the tables move. Every job declares its own axis
 * (ADR 0010 §3.4); none may inherit this one.
 */
public class BootstrapTenantAxis implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TenantContext.setPlatform();
        // Released once the application is serving. Registered here rather than as a @Component so the
        // pin and its release are one object: a reader who finds the set can see the clear.
        context.addApplicationListener(event -> {
            if (event instanceof org.springframework.boot.context.event.ApplicationReadyEvent) {
                TenantContext.clear();
            }
        });
    }
}
