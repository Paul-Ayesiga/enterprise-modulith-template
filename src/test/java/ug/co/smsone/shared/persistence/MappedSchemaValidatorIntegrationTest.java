package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.NoTenantAxis;

/**
 * The safety net that replaced {@code ddl-auto: validate} (ADR 0010 §4.4). The point of these
 * assertions is that the old net was <em>moved</em>, not dropped: Hibernate must still refuse to run
 * against a database that does not match the mapping, and it must still never create or alter
 * anything.
 */
@NoTenantAxis("the subject is MappedSchemaValidator's own runAsPlatform — under a harness pin that "
        + "wrapper could be deleted with these tests still green, and the check would go back to "
        + "running against whatever schema the borrow happened to land on")
class MappedSchemaValidatorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MappedSchemaValidator validator;

    @Autowired
    private Environment environment;

    @Test
    void hibernateNeitherCreatesNorValidatesAtBoot() {
        // `validate` here would borrow with no tenant pinned and check all 55 tables against the empty
        // no_tenant schema; anything that generates DDL would be worse still once schemas are in play.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
    }

    @Test
    void theMappingIsValidatedAgainstTheLiveSchemaOnThePlatformAxis() {
        // The premise, asserted rather than assumed. ApplicationReadyEvent fires on a thread nobody
        // pinned, so the validator has to declare its own axis; this class opts out of the harness pin
        // to reproduce that, and without this line a future harness change could quietly re-pin the
        // thread and turn the assertion below into a statement about nothing.
        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);

        assertThatCode(validator::validateMappedTablesAreReachable).doesNotThrowAnyException();
    }

    @Test
    void theValidationIsWiredToApplicationReadyAndNotLeftAsAMethodNobodyCalls() throws NoSuchMethodException {
        // Losing the annotation would leave the class compiling, the suite green, and the check dead —
        // exactly the silent removal this replacement exists to avoid.
        Method entryPoint = MappedSchemaValidator.class.getDeclaredMethod("validateMappedTablesAreReachable");
        EventListener wiring = entryPoint.getAnnotation(EventListener.class);

        assertThat(wiring).isNotNull();
        assertThat(wiring.value()).containsExactly(ApplicationReadyEvent.class);
    }
}
