package ug.co.smsone.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * OpenAPI 3.1 definition. Two auth schemes so Postman (which imports this spec natively) can either
 * paste a bearer token or run the Keycloak authorization-code flow directly.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";
    public static final String OAUTH2_SCHEME = "keycloak";
    private static final String IMPERSONATE_HEADER = "X-Impersonate";

    // ---------------------------------------------------------------------------------------------
    // The tag scheme: "<axis> · <resource>". Its two halves are deliberately NOT the same kind of
    // statement, and that asymmetry is the design — see authorizationAxisTags() for the full reasoning.
    //
    //   axis      DERIVED from the handler's own @PreAuthorize. It is a claim about WHO MAY CALL the
    //             operation, so it must not be able to disagree with the code that enforces it.
    //   resource  CURATED, from the controller map below. It is a filing label: getting it wrong puts
    //             a request in the wrong Postman folder and misleads nobody about authority.
    //
    // Never fold the two into a single hardcoded path-to-tag map. That trade buys a few lines and
    // silently converts an enforced fact into a maintained guess.
    // ---------------------------------------------------------------------------------------------

    static final String AXIS_PLATFORM = "Platform";
    static final String AXIS_ORGANIZATION = "Organization";
    static final String AXIS_SHARED = "Shared";
    /** U+00B7 MIDDLE DOT, spaced. Reads as a breadcrumb and survives Postman's flat folder list. */
    static final String AXIS_SEPARATOR = " · ";

    private static final String TAG_PLATFORM_USERS = AXIS_PLATFORM + AXIS_SEPARATOR + "Users";
    // Split out of "Users & impersonation" when the person lifecycle landed: that group reached 10
    // operations and OpenApiTagContractTest caps a group at 8, because a Postman folder you have to
    // scroll is a folder nobody reads. The split is also the better filing — impersonation is a
    // distinct capability with its own authority rules, not another user CRUD operation.
    private static final String TAG_PLATFORM_IMPERSONATION =
            AXIS_PLATFORM + AXIS_SEPARATOR + "Impersonation";
    private static final String TAG_PLATFORM_ORGS = AXIS_PLATFORM + AXIS_SEPARATOR + "Organizations";
    private static final String TAG_PLATFORM_SETTINGS = AXIS_PLATFORM + AXIS_SEPARATOR + "Settings & flags";
    private static final String TAG_PLATFORM_OPS = AXIS_PLATFORM + AXIS_SEPARATOR + "Ops";
    private static final String TAG_ORG_PROFILE = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Profile";
    private static final String TAG_ORG_MEMBERS = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Members";
    private static final String TAG_ORG_GROUPS = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Groups";
    private static final String TAG_ORG_ROLES = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Roles";
    private static final String TAG_ORG_WEBHOOKS = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Webhooks";
    private static final String TAG_ORG_AUDIT = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Audit";
    private static final String TAG_ORG_EXCHANGE = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Exchange";
    private static final String TAG_ORG_EXCHANGE_SCHEDULES =
            AXIS_ORGANIZATION + AXIS_SEPARATOR + "Exchange schedules";
    private static final String TAG_ORG_GEO = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Geolocation";
    private static final String TAG_ORG_BILLING = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Billing";
    private static final String TAG_ORG_API_KEYS = AXIS_ORGANIZATION + AXIS_SEPARATOR + "API keys";
    private static final String TAG_PLATFORM_API_KEYS = AXIS_PLATFORM + AXIS_SEPARATOR + "API keys";
    private static final String TAG_PLATFORM_BILLING = AXIS_PLATFORM + AXIS_SEPARATOR + "Billing & plans";
    private static final String TAG_PLATFORM_BILLING_ACCOUNTS = AXIS_PLATFORM + AXIS_SEPARATOR + "Billing accounts";
    private static final String TAG_SHARED_EXCHANGE = AXIS_SHARED + AXIS_SEPARATOR + "Exchange catalog";
    private static final String TAG_SHARED_WEBHOOK_EVENTS = AXIS_SHARED + AXIS_SEPARATOR + "Webhook events";
    private static final String TAG_SHARED_PROFILE = AXIS_SHARED + AXIS_SEPARATOR + "My profile";
    private static final String TAG_SHARED_DEVICES = AXIS_SHARED + AXIS_SEPARATOR + "My devices";
    private static final String TAG_SHARED_CONTACTS = AXIS_SHARED + AXIS_SEPARATOR + "My contacts";
    private static final String TAG_SHARED_PRIVACY = AXIS_SHARED + AXIS_SEPARATOR + "My privacy";
    private static final String TAG_PLATFORM_COMPLIANCE = AXIS_PLATFORM + AXIS_SEPARATOR + "Compliance";
    private static final String TAG_ORG_SECURITY = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Security policy";
    private static final String TAG_ORG_INTEGRATIONS = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Integrations";
    private static final String TAG_ORG_MAINTENANCE = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Maintenance";
    private static final String TAG_ORG_SUPPORT = AXIS_ORGANIZATION + AXIS_SEPARATOR + "Support";
    private static final String TAG_PLATFORM_SUPPORT_QUEUE = AXIS_PLATFORM + AXIS_SEPARATOR + "Support queue";
    private static final String TAG_PLATFORM_SLA = AXIS_PLATFORM + AXIS_SEPARATOR + "SLA policies";
    private static final String TAG_PLATFORM_RETENTION = AXIS_PLATFORM + AXIS_SEPARATOR + "Retention";
    private static final String TAG_PLATFORM_MAINTENANCE = AXIS_PLATFORM + AXIS_SEPARATOR + "Maintenance";
    private static final String TAG_PLATFORM_INTEGRATIONS = AXIS_PLATFORM + AXIS_SEPARATOR + "Integrations";
    private static final String TAG_SHARED_ME = AXIS_SHARED + AXIS_SEPARATOR + "Me & notifications";
    private static final String TAG_SHARED_FILES = AXIS_SHARED + AXIS_SEPARATOR + "Files";
    private static final String TAG_SHARED_SETTINGS = AXIS_SHARED + AXIS_SEPARATOR + "Settings & flags";
    private static final String TAG_SHARED_REFERENCE = AXIS_SHARED + AXIS_SEPARATOR + "Reference data";
    private static final String TAG_SHARED_SIGNUP = AXIS_SHARED + AXIS_SEPARATOR + "Sign-up";

    /**
     * The curated half: which resource a controller's operations are FILED under, keyed by simple class
     * name.
     *
     * <p>Keyed by name rather than by {@code Class} because {@code shared} must not compile-depend on a
     * business module ({@code ApplicationModules.verify()} would fail) and every controller here is
     * package-private in its module's {@code internal} package — it is not even visible from this file.
     * The cost is that a rename lands in the fallback instead of failing to compile;
     * {@code OpenApiTagContractTest} catches it, because the tag it would have filled goes unused.
     *
     * <p>Several controllers share a label on purpose ({@code SettingController} and
     * {@code FeatureFlagController} are one folder to a reader; {@code MeController} and
     * {@code NotificationController} are both "my stuff"). Nothing here decides authority.
     */
    private static final Map<String, String> RESOURCE_BY_CONTROLLER = Map.ofEntries(
            // PersonAdminController, not UserAdminController: the identity refactor renamed the class
            // and this key kept the old name, so the controller quietly fell through to the Platform
            // fallback group instead of failing to compile — exactly the failure this file's own
            // javadoc predicts for a rename. It stayed invisible while that controller had one
            // operation and surfaced only when adding two more pushed the fallback group past its cap.
            Map.entry("PersonAdminController", "Users"),
            Map.entry("ImpersonationController", "Impersonation"),
            Map.entry("SettingController", "Settings & flags"),
            Map.entry("FeatureFlagController", "Settings & flags"),
            Map.entry("SchedulerController", "Ops"),
            Map.entry("AnalyticsReportController", "Ops"),
            Map.entry("MemberController", "Members"),
            Map.entry("OrgGroupController", "Groups"),
            Map.entry("RoleController", "Roles"),
            Map.entry("WebhookController", "Webhooks"),
            Map.entry("ExchangeController", "Exchange"),
            Map.entry("ExchangeScheduleController", "Exchange schedules"),
            Map.entry("ExchangeHandlersController", "Exchange catalog"),
            Map.entry("AdminOrganizationController", "Organizations"),
            Map.entry("AdminSubscriptionController", "Billing & plans"),
            Map.entry("AdminBillingController", "Billing accounts"),
            Map.entry("OrgBillingController", "Billing"),
            Map.entry("PaymentController", "Billing"),
            Map.entry("SignupController", "Sign-up"),
            Map.entry("OrgApiKeyController", "API keys"),
            Map.entry("AdminApiKeyController", "API keys"),
            Map.entry("OrgIntegrationController", "Integrations"),
            Map.entry("AdminIntegrationController", "Integrations"),
            Map.entry("OrgMaintenanceController", "Maintenance"),
            Map.entry("AdminMaintenanceController", "Maintenance"),
            Map.entry("OrgTicketController", "Support"),
            Map.entry("AdminTicketController", "Support queue"),
            Map.entry("AdminSlaController", "SLA policies"),
            Map.entry("AdminRetentionController", "Retention"),
            Map.entry("WebhookEventTypesController", "Webhook events"),
            Map.entry("MeProfileController", "My profile"),
            Map.entry("MeDeviceController", "My devices"),
            // NOT folded into "My profile": that group is already at the readability cap, and the two
            // are different in kind anyway — a profile is cosmetics its owner may set to anything, a
            // contact address is what the platform reaches and resolves you by, half of it proven.
            Map.entry("MeContactController", "My contacts"),
            Map.entry("MeComplianceController", "My privacy"),
            Map.entry("AdminComplianceController", "Compliance"),
            Map.entry("OrgSecurityPolicyController", "Security policy"),
            Map.entry("AdminDeviceController", "Users"),
            Map.entry("OrgMembershipsController", "Me & notifications"),
            Map.entry("AdminProfileController", "Users"),
            Map.entry("MeController", "Me & notifications"),
            Map.entry("NotificationController", "Me & notifications"),
            Map.entry("FileController", "Files"),
            Map.entry("PermissionCatalogController", "Reference data"),
            Map.entry("GeoController", "Geolocation"),
            Map.entry("GeoPolicyController", "Geolocation"));

    /**
     * The overrides for controllers that serve BOTH axes and genuinely mean a different resource on
     * each. Consulted before {@link #RESOURCE_BY_CONTROLLER}, keyed {@code "<axis>|<class>"}.
     *
     * <p>{@code OrganizationController}: to an operator an org is one of many records they create and
     * switch on and off; to a member it is <em>their</em> organization's profile. {@code AuditController}:
     * the cross-tenant trail is an operations tool, the org-scoped one is a tenant feature.
     *
     * <p>This map only ever re-labels. The axis it is keyed on was already decided by the handler's
     * {@code @PreAuthorize}, so adding an entry here can move a request to another folder and can never
     * move it to another authority.
     */
    private static final Map<String, String> RESOURCE_BY_AXIS_AND_CONTROLLER = Map.of(
            AXIS_PLATFORM + "|OrganizationController", "Organizations",
            AXIS_ORGANIZATION + "|OrganizationController", "Profile",
            AXIS_PLATFORM + "|AuditController", "Ops",
            AXIS_ORGANIZATION + "|AuditController", "Audit");

    /**
     * Where an unmapped controller lands, per axis.
     *
     * <p>Each is a real, declared, populated group rather than a synthesized {@code "· Other"}: a tag no
     * root {@code tags} entry declares sorts outside the deliberate order in Swagger UI and appears in
     * Postman as a folder with no description — a spec defect — whereas one extra request in an existing
     * folder is only a filing mistake, which is all the curated half can ever cost. Each of the three is
     * the most general group on its axis, so the mistake is small and visible.
     */
    private static final Map<String, String> FALLBACK_TAG_BY_AXIS = Map.of(
            AXIS_PLATFORM, TAG_PLATFORM_OPS,
            AXIS_ORGANIZATION, TAG_ORG_PROFILE,
            AXIS_SHARED, TAG_SHARED_REFERENCE);

    private static final String CURSOR_SCHEMA_REF =
            "#/components/schemas/" + CursorPageRequest.class.getSimpleName();

    @Bean
    OpenAPI apiDefinition(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${OPENAPI_GATEWAY_URL:http://localhost:8090}") String gatewayUrl,
            @Value("${OPENAPI_LOCAL_URL:http://localhost:8080}") String localUrl,
            @Value("${OPENAPI_STAGING_URL:https://staging-api.smsone.co.ug}") String stagingUrl,
            @Value("${OPENAPI_PROD_URL:https://api.smsone.co.ug}") String prodUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("SMSOne Enterprise API")
                        .version(ApiMetaFactory.API_VERSION)
                        .description("""
                                Enterprise Spring Modulith template API. Every response uses the \
                                unified envelope ({data | errors, meta, links}) with meta.requestId \
                                always present; quote the requestId when reporting issues.

                                Operations are grouped as `<axis> · <resource>`. The axis is the \
                                authority the operation enforces — Platform (a realm role), \
                                Organization (a permission inside one tenant), Shared (any \
                                authenticated caller) — and it is read from the code that enforces it, \
                                so a group name cannot promise access the API will refuse.""")
                        .contact(new Contact().name("SMSOne").email("ayesigapo@gmail.com")))
                // The API gateway is the default target: it fronts /api/v1/** and applies the edge
                // (auth, quotas, tracing). Staging/Production URLs are the gateway too. The direct
                // service port is kept for local debugging, clearly labelled as bypassing the edge.
                .servers(List.of(
                        new Server().url(gatewayUrl).description("Local via the API gateway (recommended)"),
                        new Server().url(localUrl).description("Local, direct to the service (bypasses the gateway)"),
                        new Server().url(stagingUrl).description("Staging (via the gateway)"),
                        new Server().url(prodUrl).description("Production (via the gateway)")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste a Keycloak-issued access token."))
                        .addSecuritySchemes(OAUTH2_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Run the Keycloak authorization-code flow (PKCE).")
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(issuerUri + "/protocol/openid-connect/auth")
                                        .tokenUrl(issuerUri + "/protocol/openid-connect/token")
                                        .scopes(new Scopes()
                                                .addString("openid", "OpenID Connect")
                                                .addString("profile", "Profile claims"))))))
                // Declared here so the order is deliberate — the whole operator surface, then the whole
                // tenant surface, then what neither owns — rather than alphabetical, and so each group
                // states the rule that fills it. Anything an operation tags must appear in this list:
                // an undeclared tag sorts outside this order and reaches Postman as a bare folder.
                .tags(List.of(
                        new Tag().name(TAG_PLATFORM_USERS).description(
                                "Identities across every tenant: read one or many, correct a name, and "
                                + "suspend or restore access. platform-support reads; changing another "
                                + "human's name or access needs platform-admin. Suspension takes effect on "
                                + "the target's very next request, and a person suspended before they ever "
                                + "signed in is restored to INVITED, not ACTIVE."),
                        new Tag().name(TAG_PLATFORM_IMPERSONATION).description(
                                "The only sanctioned way from an operator to tenant data. platform-support "
                                + "may open a session; wearing an account that itself holds a platform role "
                                + "needs platform-superadmin. A session carries no authority of its own, so "
                                + "from inside one this whole group answers 403."),
                        new Tag().name(TAG_PLATFORM_ORGS).description(
                                "Creating a tenant, suspending it, reactivating it. These are platform "
                                + "actions ON an organization, not actions inside one, and that is why they "
                                + "are here: a fresh org has no member to hold a permission, and suspension "
                                + "cuts every member's access — neither can be gated on a permission granted "
                                + "inside the org being acted on. Reading and editing the record itself is "
                                + "the tenant's own business: " + TAG_ORG_PROFILE + "."),
                        new Tag().name(TAG_PLATFORM_SETTINGS).description(
                                "Writing platform-wide configuration: setting values and feature-flag state, "
                                + "platform-admin. The same keys are readable by any authenticated caller "
                                + "under " + TAG_SHARED_SETTINGS + " — same paths, different method, "
                                + "different authority, which is why one controller lands in two groups."),
                        new Tag().name(TAG_PLATFORM_SUPPORT_QUEUE).description(
                                "The cross-tenant support queue (platform-support): work tickets, assign, "
                                + "reply (public or an internal note the tenant never sees), transition "
                                + "status. SLA breaches escalate automatically — a minute job bumps "
                                + "priority, counts the breach, and fires org.ticket.escalated."),
                        new Tag().name(TAG_PLATFORM_SLA).description(
                                "Per-org SLA overrides (platform-admin writes, platform-support reads): set an "
                                + "org's first-response/resolution targets tighter or looser than the seeded "
                                + "per-priority defaults. Consulted at ticket open."),
                        new Tag().name(TAG_PLATFORM_RETENTION).description(
                                "Per-org retention overrides (platform-admin writes, platform-support reads) for "
                                + "the org-scoped logs (webhook-delivery, exchange-job): keep or drop one org's "
                                + "rows on a different schedule than the platform default."),
                        new Tag().name(TAG_PLATFORM_MAINTENANCE).description(
                                "Scheduling maintenance windows (platform-admin), platform-wide or "
                                + "targeted at one org. RESTRICT pauses org writes for the window; ANNOUNCE "
                                + "is banner-only. Distinct from a tenant's lifecycle (" + TAG_PLATFORM_ORGS
                                + ") — those are permanent, these are time-boxed."),
                        new Tag().name(TAG_PLATFORM_COMPLIANCE).description(
                                "Legal holds and erasure execution. A hold (subject or org) BLOCKS the "
                                + "retention purge and any erasure of the held data until released — "
                                + "placing is platform-admin, listing platform-support. Erasure "
                                + "soft-deletes now, hard-erases at retention (which honors holds)."),
                        new Tag().name(TAG_PLATFORM_INTEGRATIONS).description(
                                "The PLATFORM-DEFAULT provider configs (platform-admin) — SMS, email, "
                                + "payment gateway — used by any org that has no override of its own. "
                                + "Same encryption and masking as the org surface."),
                        new Tag().name(TAG_PLATFORM_API_KEYS).description(
                                "Platform machine credentials — support-tier keys minted by platform-admin. "
                                + "They read platform surfaces; they never satisfy a higher tier or carry "
                                + "org permissions."),
                        new Tag().name(TAG_PLATFORM_BILLING).description(
                                "The commercial axis of a tenant: the seeded plan catalogue and each "
                                + "org's subscription. Reading is platform-support; assigning a plan is "
                                + "platform-admin, audited, and bites the very next entitlement gate. A "
                                + "tenant reads its own state under " + TAG_ORG_PROFILE + "'s surface."),
                        new Tag().name(TAG_PLATFORM_BILLING_ACCOUNTS).description(
                                "One org's window into the Kill Bill side: link its account, start a "
                                + "billed subscription, read balance and invoices. The catalogue and the "
                                + "entitlement authority stay in " + TAG_PLATFORM_BILLING + " - money "
                                + "records here, access decisions there."),
                        new Tag().name(TAG_PLATFORM_OPS).description(
                                "Running the platform: scheduler lock state (proof that clustered jobs fire "
                                + "once), the curated analytics reports, and the cross-tenant audit trail. "
                                + "Read-only and platform-support throughout — investigating is the support "
                                + "job. One tenant's own view of that trail is " + TAG_ORG_AUDIT + "."),
                        new Tag().name(TAG_ORG_PROFILE).description(
                                "The organization record as its own members see it: read on org:read, edit "
                                + "on org:update. Lifecycle is deliberately absent — that is "
                                + TAG_PLATFORM_ORGS + "."),
                        new Tag().name(TAG_ORG_MEMBERS).description(
                                "Who belongs to one tenant and as what: list, invite, re-role, remove. Handing "
                                + "someone a role IS granting its permissions, so invite and re-role are "
                                + "additionally refused when they would grant more than the caller holds."),
                        new Tag().name(TAG_ORG_GROUPS).description(
                                "Named funnels that confer one role to their members, unioned with each "
                                + "member's own — the resolver adds the group's permissions, never replaces "
                                + "them. Managing a group is member:role:assign (a group can't grant more "
                                + "than its creator holds); reading is member:read. A group extends a "
                                + "member, it is not a way in."),
                        new Tag().name(TAG_ORG_ROLES).description(
                                "A tenant's roles — named bundles of permission codes from the fixed catalogue "
                                + "(" + TAG_SHARED_REFERENCE + "). Role codes are inert: no request path "
                                + "resolves authority from a code, only from the permission set. System roles "
                                + "refuse edits."),
                        new Tag().name(TAG_ORG_WEBHOOKS).description(
                                "One tenant's outbound event subscriptions and their delivery attempts, all on "
                                + "webhook:manage. Endpoint URLs are SSRF-checked before they are stored, and "
                                + "every delivery is signed and retried with backoff before it dead-letters."),
                        new Tag().name(TAG_ORG_AUDIT).description(
                                "The audit trail scoped to one tenant, on audit:read — an org's own admins can "
                                + "review their trail without any platform access. Rows written during an "
                                + "impersonation session name the operator, not the account they wore."),
                        new Tag().name(TAG_ORG_EXCHANGE).description(
                                "Import and export as background jobs inside one tenant: submit answers 202 "
                                + "and the work is polled, never awaited. Submitting is additionally refused "
                                + "without the chosen handler's own permission — the handler is picked at "
                                + "runtime, so no annotation can name that gate. Job metadata reads on "
                                + "org:read; the artifacts (source, error report, result) answer only to the "
                                + "requester or a holder of that same handler permission. The handler "
                                + "catalogue itself is " + TAG_SHARED_EXCHANGE + "."),
                        new Tag().name(TAG_ORG_API_KEYS).description(
                                "An organization's machine credentials (apikey:manage). A key carries a "
                                + "SUBSET of its creator's permissions — it can never out-rank them — and "
                                + "authenticates as X-Api-Key. The secret shows once, at mint."),
                        new Tag().name(TAG_ORG_SUPPORT).description(
                                "The tenant's support tickets — open, read, reply (org:read). Internal "
                                + "platform notes are never shown here; escalation and assignment are the "
                                + "platform's job (" + TAG_PLATFORM_SUPPORT_QUEUE + "). A public reply "
                                + "notifies the opener in-app."),
                        new Tag().name(TAG_ORG_MAINTENANCE).description(
                                "The maintenance windows in effect for this tenant (platform-wide plus its "
                                + "own), read on org:read for a client banner. During a RESTRICT window this "
                                + "org's writes answer 503 + Retry-After; reads pass. Scheduling is "
                                + TAG_PLATFORM_MAINTENANCE + "."),
                        new Tag().name(TAG_ORG_INTEGRATIONS).description(
                                "The organization's provider overrides (org:update) — SMS, email, payment "
                                + "gateway. An override wins over the platform default for that capability. "
                                + "Secret values are encrypted at rest and masked on read; the platform "
                                + "defaults are " + TAG_PLATFORM_INTEGRATIONS + "."),
                        new Tag().name(TAG_ORG_SECURITY).description(
                                "The organization's security policy (org:update to set, org:read to view): "
                                + "IP allowlist, require-a-trusted-device, session max age. Every field "
                                + "TIGHTENS access over the open default; a denial is a 403 naming the "
                                + "rule, distinct from RBAC. Blessing a member's device as trusted lives "
                                + "here too — trust is the org's grant, not a self-claim."),
                        new Tag().name(TAG_ORG_BILLING).description(
                                "The tenant's own money view — invoices, proxied from Kill Bill on "
                                + "org:read. Plans, balances and billing actions are the platform's "
                                + "surface: " + TAG_PLATFORM_BILLING + "."),
                        new Tag().name(TAG_ORG_EXCHANGE_SCHEDULES).description(
                                "Recurring exports on a cron (UTC), firing as their creator — whose export "
                                + "permission is re-checked at every fire, so a revocation stops the "
                                + "schedule loudly instead of letting it keep exporting. One-off jobs and "
                                + "their artifacts are " + TAG_ORG_EXCHANGE + "."),
                        new Tag().name(TAG_ORG_GEO).description(
                                "Geolocation stamps on a tenant's records, and the per-record-type capture "
                                + "policy. Attaching a fix needs geo:capture; reading needs geo:read "
                                + "(coordinates coarsened to ~1.1 km) or geo:read_precise (exact); configuring a "
                                + "record-type's OFF/OPTIONAL/REQUIRED policy needs geo:policy:manage."),
                        new Tag().name(TAG_SHARED_ME).description(
                                "The caller's own identity and in-app notifications; no axis owns them because "
                                + "a tenant member and a platform operator both legitimately arrive here. "
                                + "GET /me is the one path the provisioning gate lets through for an "
                                + "un-provisioned subject (onboarding), and still a hard stop for a disabled one."),
                        new Tag().name(TAG_SHARED_FILES).description(
                                "Upload, download, delete, presign. The one group whose rule lives in the "
                                + "handler rather than in an annotation, which is exactly why neither axis "
                                + "claims it: your own namespace always, someone else's only with a platform "
                                + "tier (support to read, admin to delete)."),
                        new Tag().name(TAG_SHARED_SETTINGS).description(
                                "Reading platform configuration — settings and feature-flag state are visible "
                                + "to any authenticated caller, because how the application behaves is not a "
                                + "secret. Writing them is " + TAG_PLATFORM_SETTINGS + "."),
                        new Tag().name(TAG_SHARED_REFERENCE).description(
                                "Fixed vocabularies any authenticated caller may read — today the permission "
                                + "catalogue that " + TAG_ORG_ROLES + " is composed from. A new shared "
                                + "read lands here until it earns a group of its own."),
                        new Tag().name(TAG_SHARED_SIGNUP).description(
                                "Self-service organization creation, off unless SIGNUP_ENABLED: request a "
                                + "verification e-mail (always 202 - enumeration-safe), then redeem the "
                                + "single-use token to mint the org and its first OWNER. The only "
                                + "unauthenticated write surface, and deliberately this small."),
                        new Tag().name(TAG_SHARED_EXCHANGE).description(
                                "The exchange handler catalogue and its downloadable templates — which "
                                + "datasets can move, what each file must look like, which permissions "
                                + "gate it. Readable by any authenticated caller; running one is "
                                + TAG_ORG_EXCHANGE + "."),
                        new Tag().name(TAG_SHARED_PROFILE).description(
                                "The caller's own record — profile, contacts, preferences, avatar, "
                                + "linked identity providers. No axis owns it: every row answers about "
                                + "YOU, whichever axis you arrived from. Support's read-only view of a "
                                + "user's profile is " + TAG_PLATFORM_USERS + "."),
                        new Tag().name(TAG_SHARED_DEVICES).description(
                                "The caller's own devices — register (idempotent per X-Device-Id), list, "
                                + "revoke. Marking one TRUSTED is not here: trust is an organization's "
                                + "grant (its security policy), not a self-claim."),
                        new Tag().name(TAG_SHARED_CONTACTS).description(
                                "The addresses this platform can reach you at — add, remove, choose "
                                + "your primary, and prove one. An UNVERIFIED address is inert: nothing "
                                + "resolves you by it and nothing falls back to it, which is why it can "
                                + "never be made primary. Proof is your identity provider's own "
                                + "verified-email claim; this platform sends no challenge of its own."),
                        new Tag().name(TAG_SHARED_PRIVACY).description(
                                "Your own compliance controls — consent history (append-only), a data "
                                + "export of your record (portability), and requesting your own erasure "
                                + "(GDPR art. 17; deferred while a legal hold is in force)."),
                        new Tag().name(TAG_SHARED_WEBHOOK_EVENTS).description(
                                "The subscribable webhook event vocabulary — the codes the subscription "
                                + "endpoints' events array accepts, with what each means. Managing "
                                + "subscriptions is " + TAG_ORG_WEBHOOKS + ".")))
                .security(List.of(
                        new SecurityRequirement().addList(BEARER_SCHEME),
                        new SecurityRequirement().addList(OAUTH2_SCHEME)));
    }

    /**
     * Publishes the REAL cursor parameters, {@code page[size]} and {@code page[after]}.
     *
     * <p>springdoc cannot see through {@link CursorPageRequestArgumentResolver}, so it documents the
     * handler's {@code CursorPageRequest page} argument as ONE query parameter named after the Java
     * variable ({@code page}) carrying an object schema. Anything generated from that spec sends the
     * wrong names — Postman flattens the object to {@code size=…&after=…} — and since the resolver only
     * reads the bracketed names, nothing binds and the request silently falls back to the default page
     * size. The failure is invisible: {@code size=3919} looks accepted, while the real
     * {@code page[size]=3919} would be rejected as over the {@value CursorPageRequest#MAX_SIZE} maximum.
     *
     * <p>Keyed off the handler's parameter type, so every paginated endpoint is corrected automatically
     * and a new one cannot forget.
     */
    @Bean
    OperationCustomizer cursorPaginationParameters() {
        return (operation, handlerMethod) -> {
            boolean paginated = Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(parameter -> CursorPageRequest.class.equals(parameter.getParameterType()));
            if (!paginated) {
                return operation;
            }
            if (operation.getParameters() != null) {
                operation.getParameters().removeIf(parameter -> parameter.getSchema() != null
                        && CURSOR_SCHEMA_REF.equals(parameter.getSchema().get$ref()));
            }
            // Schema's fluent minimum()/maximum() return the RAW Schema type, so chaining off them
            // drops the type argument and erases _default(T) to _default(Object). Holding the
            // parameterized type instead keeps the default type-checked; the setters are the same
            // field assignments the fluent pair makes. _default() stays — setDefault() is NOT a
            // synonym, it runs the value through cast() and flips defaultSetFlag.
            Schema<Number> pageSize = new IntegerSchema();
            pageSize.setMinimum(BigDecimal.ONE);
            pageSize.setMaximum(BigDecimal.valueOf(CursorPageRequest.MAX_SIZE));
            pageSize._default(CursorPageRequest.DEFAULT_SIZE);
            operation.addParametersItem(new Parameter()
                    .in("query").name("page[size]").required(false)
                    .description("Items per page, 1–" + CursorPageRequest.MAX_SIZE
                            + ". Defaults to " + CursorPageRequest.DEFAULT_SIZE + ".")
                    .schema(pageSize));
            operation.addParametersItem(new Parameter()
                    .in("query").name("page[after]").required(false)
                    .description("Opaque keyset cursor taken from a previous response's "
                            + "meta.page.nextCursor. Omit for the first page.")
                    .schema(new StringSchema()));
            return operation;
        };
    }

    /** Drops the {@code CursorPageRequest} schema the corrected parameters no longer reference. */
    @Bean
    OpenApiCustomizer removeOrphanedCursorSchema() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().remove(CursorPageRequest.class.getSimpleName());
            }
        };
    }

    /**
     * Documents the {@code X-Impersonate} request header.
     *
     * <p>It is not a parameter of any one endpoint — {@code ImpersonationFilter} reads it ahead of the
     * whole chain, so it applies to any operation an operator might want to perform as the session's
     * target. Declaring it per-controller would mean every future controller re-declaring it, so it is
     * added here once, the same way {@code X-Request-Id} is.
     *
     * <p>Excluded from {@code /admin/**}: the impersonated principal deliberately carries no platform
     * role, so those operations are 403 for the duration of a session. Advertising the header where it
     * can only ever fail would document a capability that does not exist.
     */
    @Bean
    OperationCustomizer impersonationHeader() {
        return (operation, handlerMethod) -> {
            String path = handlerMethod.getMethod().getDeclaringClass().getAnnotation(RequestMapping.class) != null
                    ? String.join("", handlerMethod.getMethod().getDeclaringClass()
                            .getAnnotation(RequestMapping.class).value())
                    : "";
            if (path.startsWith("/api/v1/admin")) {
                return operation;
            }
            operation.addParametersItem(new Parameter()
                    .in("header").name(IMPERSONATE_HEADER).required(false)
                    .description("""
                            Run this request as the target of an impersonation session (its id, from \
                            POST /api/v1/admin/impersonations). The session must belong to the calling \
                            operator; a READ_ONLY session refuses any unsafe method. Every write is \
                            audited against the operator, not the target.""")
                    .schema(new StringSchema().format("uuid")));
            return operation;
        };
    }

    /**
     * Tags every operation {@code "<axis> · <resource>"}, replacing springdoc's default
     * one-tag-per-controller-class.
     *
     * <p>Controller classes are an implementation detail — they split by module, so the default tags
     * scatter one concept across several groups and tell a reader nothing about who may call what. The
     * axis answers the only question a caller actually has (<em>can I call this?</em>); the resource half
     * exists because an axis alone leaves the tenant surface as one unreadable 18-operation folder in
     * Postman, which is a flat list of tags with no nesting.
     *
     * <p><strong>The two halves are not the same kind of statement, and must not be merged.</strong>
     *
     * <ul>
     * <li><b>Axis — derived, load-bearing.</b> Read off the handler's own {@code @PreAuthorize}, never
     * from a path or class list. A list would be a second source of truth that drifts the moment an
     * authority changes, and a group that contradicts the enforced authority is worse than no group at
     * all, because it is believed. Deriving it means a new endpoint is grouped correctly without anyone
     * remembering to, and re-tiering one re-groups it.</li>
     * <li><b>Resource — curated, cosmetic.</b> A label from {@link #RESOURCE_BY_CONTROLLER}. Its worst
     * failure is a request filed in the wrong folder in a UI; it cannot misstate who may call anything.
     * A hand-maintained map is the right tool for a label nothing enforces.</li>
     * </ul>
     *
     * <p>So do not "simplify" this into one hardcoded path-to-tag map. The halves look alike in the
     * output and are opposites in kind: that refactor would quietly turn an enforced fact into a
     * maintained guess, and the day it drifted the spec would still look tidy.
     *
     * <p>A controller whose handlers span both axes therefore splits across two tags on its own:
     * {@code AuditController} (platform-wide read vs org-scoped read) and {@code OrganizationController}
     * (create/suspend/reactivate vs get/patch) both do today. Nothing declares them as split —
     * {@link #RESOURCE_BY_AXIS_AND_CONTROLLER} only picks which label each side wears, and deleting both
     * entries would still leave two tags, just with one label between them. That the split happens
     * without being asked for is the observable proof the axis half is still derived, which is why
     * {@code OpenApiTagContractTest} pins it.
     *
     * <p>The tag is only ever as honest as the annotation. {@code /files} carries no {@code @PreAuthorize}
     * — it enforces owner-or-platform-tier inside the handler — so it lands on the shared axis, which is
     * right (both axes reach it) even though the reasoning is invisible here. That is called out in the
     * group's own description rather than special-cased.
     */
    @Bean
    OperationCustomizer authorizationAxisTags() {
        return (operation, handlerMethod) -> {
            PreAuthorize preAuthorize = handlerMethod.getMethodAnnotation(PreAuthorize.class);
            if (preAuthorize == null) {
                preAuthorize = handlerMethod.getBeanType().getAnnotation(PreAuthorize.class);
            }
            String expression = preAuthorize == null ? "" : preAuthorize.value();
            boolean org = expression.contains("hasPermission(");
            boolean platform = expression.contains("hasRole('platform-");
            // Both axes in one expression means neither owns it — the same reading as no annotation at
            // all. No operation does this today; handled so the first one that does is not silently
            // filed under whichever branch happens to run first.
            String axis = org && !platform ? AXIS_ORGANIZATION
                    : platform && !org ? AXIS_PLATFORM
                    : AXIS_SHARED;
            // set, not add — drops springdoc's controller-name default
            operation.setTags(List.of(tagFor(axis, handlerMethod.getBeanType().getSimpleName())));
            return operation;
        };
    }

    /**
     * Composes the tag: the derived axis, then the curated label for the controller on that axis, then
     * the controller's label on any axis, then the axis's fallback group.
     *
     * <p>{@code getBeanType()} returns the user-defined class even for the CGLIB proxy that
     * {@code @PreAuthorize} creates, so the simple name is the one written in the map.
     */
    private static String tagFor(String axis, String controller) {
        String resource = RESOURCE_BY_AXIS_AND_CONTROLLER.get(axis + "|" + controller);
        if (resource == null) {
            resource = RESOURCE_BY_CONTROLLER.get(controller);
        }
        return resource == null ? FALLBACK_TAG_BY_AXIS.get(axis) : axis + AXIS_SEPARATOR + resource;
    }

    /** Documents the always-present X-Request-Id response header on every operation. */
    @Bean
    OperationCustomizer requestIdHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            if (operation.getResponses() != null) {
                operation.getResponses().forEach((status, response) -> response.addHeaderObject(
                        RequestIdFilter.REQUEST_ID_HEADER,
                        new Header()
                                .description("Public request id (accepted inbound, else minted as a ULID).")
                                .schema(new StringSchema())));
            }
            return operation;
        };
    }
}
