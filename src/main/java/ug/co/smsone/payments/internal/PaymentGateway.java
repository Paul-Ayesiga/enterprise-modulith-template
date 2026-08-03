package ug.co.smsone.payments.internal;

import java.util.UUID;

/**
 * The internal seam one gateway adapter implements. {@code initiate} starts a collection and returns
 * the gateway's reference (plus a hosted-page redirect when the flow has one); {@code status} asks
 * the gateway for the current outcome — always the gateway's word, never a client's claim. The org
 * id rides along so credential resolution can honor a per-org integration override.
 */
interface PaymentGateway {

    /** The provider key — matches the integration hub's provider and the API's {@code provider} field. */
    String provider();

    Initiation initiate(UUID orgId, Payment payment);

    StatusResult status(UUID orgId, Payment payment);

    /** The mode ({@code sandbox} | {@code live}) the adapter would use for this org right now. */
    String mode(UUID orgId);

    record Initiation(String gatewayReference, String redirectUrl, PaymentStatus status, String detail) {
    }

    record StatusResult(PaymentStatus status, String detail, String confirmationCode) {
    }
}
