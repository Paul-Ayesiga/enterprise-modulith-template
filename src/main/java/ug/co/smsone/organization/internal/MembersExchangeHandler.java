package ug.co.smsone.organization.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import ug.co.smsone.exchange.ExchangeContext;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.exchange.ImportOutcome;
import ug.co.smsone.exchange.InvalidRecordException;
import ug.co.smsone.exchange.RecordWriter;
import ug.co.smsone.identity.UserDirectory;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.error.ApiException;

/**
 * The reference {@code ExchangeHandler}: bulk member onboarding and roster export, driving the
 * SAME {@link MemberService} the REST surface uses — one rulebook, two entry points. Each import
 * record is a full invite (provision → Keycloak link → membership row) whose escalation guard runs
 * against the REQUESTER, so a role revoked after submit stops granting mid-file. Idempotent the
 * way invite is: a re-imported member is returned unchanged, never duplicated or re-roled.
 */
@Component
class MembersExchangeHandler implements ExchangeHandler {

    /** Data-record pages per export window — one membership query + one email batch-lookup each. */
    private static final int EXPORT_PAGE = 500;

    private static final Sort EXPORT_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final MemberService members;
    private final MembershipRepository memberships;
    private final UserDirectory userDirectory;

    MembersExchangeHandler(MemberService members, MembershipRepository memberships,
            UserDirectory userDirectory) {
        this.members = members;
        this.memberships = memberships;
        this.userDirectory = userDirectory;
    }

    @Override
    public String id() {
        return "org-members";
    }

    @Override
    public String importPermission() {
        return Permission.MEMBER_INVITE.code();
    }

    @Override
    public String exportPermission() {
        return Permission.MEMBER_READ.code();
    }

    @Override
    public List<String> header() {
        return List.of("email", "firstName", "lastName", "roleCode");
    }

    @Override
    public ImportOutcome importRecord(ExchangeContext context, Map<String, String> record) {
        String email = record.getOrDefault("email", "").trim();
        if (email.isEmpty()) {
            throw new InvalidRecordException("email is required.");
        }
        if (!email.contains("@")) {
            throw new InvalidRecordException("email is not a valid address: " + email);
        }
        String roleCode = record.getOrDefault("roleCode", "").trim();
        if (roleCode.isEmpty()) {
            throw new InvalidRecordException("roleCode is required.");
        }
        try {
            members.inviteAs(context.requester(), context.orgId(), email,
                    record.getOrDefault("firstName", "").trim(),
                    record.getOrDefault("lastName", "").trim(), roleCode);
            return ImportOutcome.APPLIED;
        } catch (ApiException ex) {
            // Everything the API surface would answer 4xx (unknown role, escalation, conflicts) is
            // a DATA problem here, and the message is already tenant-curated by construction.
            throw new InvalidRecordException(ex.getMessage());
        }
    }

    @Override
    public void export(ExchangeContext context, RecordWriter out) {
        Map<UUID, String> roleCodes = members.roleCodes(context.orgId());
        KeysetScrollPosition position = ScrollPosition.keyset();
        while (true) {
            KeysetScrollPosition current = position;
            Window<Membership> window = memberships.findBy(
                    (root, query, cb) -> cb.equal(root.get("orgId"), context.orgId()),
                    query -> query.limit(EXPORT_PAGE).sortBy(EXPORT_SORT).scroll(current));
            if (window.isEmpty()) {
                return;
            }
            Map<String, String> emails = userDirectory.emailsBySubjects(
                    window.getContent().stream().map(Membership::getUserSubject).toList());
            for (Membership membership : window) {
                Map<String, String> record = new LinkedHashMap<>();
                record.put("email", emails.getOrDefault(membership.getUserSubject(), ""));
                // Names live only in Keycloak (the local projection stores none) — exported blank.
                // The header still carries the columns so an exported file re-imports as-is.
                record.put("firstName", "");
                record.put("lastName", "");
                record.put("roleCode", roleCodes.getOrDefault(membership.getRoleId(), ""));
                out.write(record);
            }
            if (!window.hasNext()) {
                return;
            }
            position = (KeysetScrollPosition) window.positionAt(window.size() - 1);
        }
    }
}
