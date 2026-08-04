/**
 * Payment collection through external gateways — Pesapal (hosted redirect: card + mobile money) and
 * Yo! Uganda (direct mobile-money push to the payer's handset). Each gateway runs in a switchable
 * {@code sandbox} / {@code live} mode; credentials resolve through the integration hub
 * ({@code PAYMENT_GATEWAY}, org override over platform default) with env config as the fallback.
 * The gateway is the source of truth for an outcome: a row starts PENDING and converges via the
 * Pesapal IPN callback or an on-read status refresh — never by trusting the browser.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payments")
package ug.co.smsone.payments;
