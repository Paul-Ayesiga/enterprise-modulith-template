package ug.co.smsone.organization;

import java.util.List;
import java.util.UUID;

/**
 * Who to tell about an organization's commercial standing: the OWNERs' email addresses, resolved
 * fresh at send time (members change; contact lists must not be snapshots). Consumed by billing's
 * dunning/receipt notifications.
 */
public interface OrgContacts {

    List<String> ownerEmails(UUID orgId);
}
