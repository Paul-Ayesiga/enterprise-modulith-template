package ug.co.smsone.payments.internal;

import java.io.StringReader;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import ug.co.smsone.integration.Integrations;

/**
 * Yo! Payments (Yo Uganda Limited) — the XML {@code AutoCreate} API at {@code task.php}, per the
 * vendor's published SDKs. {@code acdepositfunds} with {@code NonBlocking=TRUE} pushes a
 * mobile-money approval prompt to the payer's handset and returns immediately with a
 * {@code TransactionReference}; {@code actransactioncheckstatus} then reports
 * SUCCEEDED / FAILED / PENDING / INDETERMINATE. A response with {@code Status != OK} throws with the
 * vendor's message. The payer's number is the {@code Account} — bare digits, country code first.
 */
@Component
class YoUgandaGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(YoUgandaGateway.class);
    static final String PROVIDER = "yo-uganda";

    private final RestClient restClient;
    private final PaymentsProperties properties;
    private final ObjectProvider<Integrations> integrations;

    YoUgandaGateway(RestClient paymentsRestClient, PaymentsProperties properties,
            ObjectProvider<Integrations> integrations) {
        this.restClient = paymentsRestClient;
        this.properties = properties;
        this.integrations = integrations;
    }

    private record Config(String mode, String baseUrl, String apiUsername, String apiPassword) {
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String mode(UUID orgId) {
        return config(orgId).mode();
    }

    @Override
    public Initiation initiate(UUID orgId, Payment payment) {
        if (payment.getPhoneNumber() == null || payment.getPhoneNumber().isBlank()) {
            throw new IllegalStateException("Yo! collections need the payer's mobile-money number");
        }
        Config config = config(orgId);
        String account = payment.getPhoneNumber().replaceAll("[\\s()./+-]", "");
        String xml = request(config, """
                <Method>acdepositfunds</Method>
                <NonBlocking>TRUE</NonBlocking>
                <Amount>%s</Amount>
                <Account>%s</Account>
                <Narrative>%s</Narrative>
                <ExternalReference>%s</ExternalReference>"""
                .formatted(payment.getAmount().toPlainString(), escape(account),
                        escape(payment.getDescription()), escape(payment.getMerchantReference())));
        Document response = call(config, xml);
        requireOk(response);
        String reference = text(response, "TransactionReference");
        String transactionStatus = text(response, "TransactionStatus");
        log.info("Yo! deposit initiated: org={} ref={} yoRef={} status={} mode={}",
                orgId, payment.getMerchantReference(), reference, transactionStatus, config.mode());
        return new Initiation(reference, null, map(transactionStatus),
                "Approval prompt sent to " + account + " — awaiting the payer's PIN");
    }

    @Override
    public StatusResult status(UUID orgId, Payment payment) {
        Config config = config(orgId);
        String xml = request(config, """
                <Method>actransactioncheckstatus</Method>
                <TransactionReference>%s</TransactionReference>"""
                .formatted(escape(payment.getGatewayReference())));
        Document response = call(config, xml);
        requireOk(response);
        String transactionStatus = text(response, "TransactionStatus");
        return new StatusResult(map(transactionStatus), "Yo! reports " + transactionStatus,
                text(response, "MNOTransactionReferenceId"));
    }

    private static PaymentStatus map(String transactionStatus) {
        if (transactionStatus == null) {
            return PaymentStatus.PENDING; // accepted, not yet resolved
        }
        return switch (transactionStatus.trim().toUpperCase()) {
            case "SUCCEEDED" -> PaymentStatus.COMPLETED;
            case "FAILED" -> PaymentStatus.FAILED;
            case "PENDING" -> PaymentStatus.PENDING;
            default -> PaymentStatus.INDETERMINATE;
        };
    }

    private String request(Config config, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <AutoCreate>
                <Request>
                <APIUsername>%s</APIUsername>
                <APIPassword>%s</APIPassword>
                %s
                </Request>
                </AutoCreate>""".formatted(escape(config.apiUsername()), escape(config.apiPassword()), body);
    }

    private Document call(Config config, String xml) {
        String raw = restClient.post()
                .uri(config.baseUrl())
                .contentType(MediaType.TEXT_XML)
                .body(xml)
                .retrieve()
                .body(String.class);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Yo! Payments returned an empty response");
        }
        return parse(raw);
    }

    private static void requireOk(Document response) {
        String status = text(response, "Status");
        if (!"OK".equalsIgnoreCase(status)) {
            String message = text(response, "StatusMessage");
            String error = text(response, "ErrorMessage");
            throw new IllegalStateException("Yo! Payments refused the request: "
                    + (error != null ? error : message != null ? message : "Status=" + status));
        }
    }

    /** XXE-hardened parse — the payload is a vendor response, but the parser stays locked anyway. */
    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable Yo! Payments response", e);
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** Integration hub (provider {@code yo-uganda}, org override wins) first; env as fallback. */
    private Config config(UUID orgId) {
        PaymentsProperties.Yo env = properties.yo();
        Integrations hub = integrations.getIfAvailable();
        if (hub != null) {
            var resolved = hub.resolve(orgId, Integrations.Kind.PAYMENT_GATEWAY)
                    .filter(integration -> PROVIDER.equalsIgnoreCase(integration.provider()))
                    .orElse(null);
            if (resolved != null) {
                Map<String, String> settings = resolved.settings();
                String mode = settings.getOrDefault("mode", env.mode());
                String baseUrl = PaymentsProperties.LIVE.equals(mode) ? env.liveBaseUrl() : env.sandboxBaseUrl();
                return new Config(mode,
                        settings.getOrDefault("baseUrl", baseUrl),
                        settings.getOrDefault("apiUsername", env.apiUsername()),
                        settings.getOrDefault("apiPassword", env.apiPassword()));
            }
        }
        if (!env.configured()) {
            throw new IllegalStateException("No Yo! Payments credentials: configure the PAYMENT_GATEWAY "
                    + "integration (provider 'yo-uganda') or set YO_API_USERNAME/YO_API_PASSWORD.");
        }
        return new Config(env.mode(), env.baseUrl(), env.apiUsername(), env.apiPassword());
    }
}
