package ug.co.smsone.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.subscription.Subscriptions;

/**
 * The two money rules in isolation: VAT-inclusive breakdown on initiation (vat = amount×r/(100+r)),
 * and a COMPLETED refresh lifting the org's standing through the Subscriptions port — the
 * pay-to-recover path a paused org uses.
 */
class PaymentServiceMoneyTest {

    private final PaymentRepository repo = mock(PaymentRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final Subscriptions standings = mock(Subscriptions.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private PaymentService service(BigDecimal vatRate) {
        given(gateway.provider()).willReturn("pesapal");
        given(repo.save(any())).willAnswer(inv -> inv.getArgument(0));
        return new PaymentService(repo, List.of(gateway), provider(null), provider(standings),
                new PaymentsProperties(null, null, new PaymentsProperties.Tax(vatRate)),
                mock(AuditLog.class), Clock.systemUTC(), meters);
    }

    @Test
    void vatBreakdownIsInclusiveAndRounded() {
        PaymentService service = service(new BigDecimal("18"));
        given(gateway.mode(any())).willReturn("sandbox");
        given(gateway.initiate(any(), any())).willReturn(
                new PaymentGateway.Initiation("ref", null, PaymentStatus.PENDING, "d"));

        Payment payment = service.initiate(UUID.randomUUID(), "pesapal", new BigDecimal("11800"),
                "UGX", "PRO invoice", null, "p@a.test");

        assertThat(payment.getVatAmount()).isEqualByComparingTo("1800.00");
        assertThat(payment.getNetAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void zeroRateLeavesNoTaxLines() {
        PaymentService service = service(BigDecimal.ZERO);
        given(gateway.mode(any())).willReturn("sandbox");
        given(gateway.initiate(any(), any())).willReturn(
                new PaymentGateway.Initiation("ref", null, PaymentStatus.PENDING, "d"));

        Payment payment = service.initiate(UUID.randomUUID(), "pesapal", new BigDecimal("5000"),
                "UGX", "Topup", "256772123456", null);

        assertThat(payment.getVatAmount()).isNull();
        assertThat(payment.getNetAmount()).isNull();
    }

    @Test
    void aCompletedRefreshLiftsTheStandingThroughThePort() {
        PaymentService service = service(BigDecimal.ZERO);
        UUID orgId = UUID.randomUUID();
        Payment pending = Payment.initiate(orgId, "pesapal", "sandbox", new BigDecimal("5000"),
                "UGX", "Recover", null, "p@a.test", java.time.Instant.now());
        pending.initiated("track-1", null, PaymentStatus.PENDING, "d", java.time.Instant.now());
        given(repo.findByIdAndOrgId(pending.getId(), orgId)).willReturn(Optional.of(pending));
        given(gateway.status(any(), any())).willReturn(
                new PaymentGateway.StatusResult(PaymentStatus.COMPLETED, "done", "CONF"));

        service.get(orgId, pending.getId());

        verify(standings).markStatus(orgId, "ACTIVE"); // PAUSED/PAST_DUE lift; same-status no-ops
        // The terminal transition was counted for the payment-failures alert family.
        org.assertj.core.api.Assertions.assertThat(meters.counter("smsone.payments.outcomes",
                "provider", "pesapal", "status", "COMPLETED").count()).isEqualTo(1.0);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                if (value == null) {
                    throw new IllegalStateException("absent");
                }
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }
}
