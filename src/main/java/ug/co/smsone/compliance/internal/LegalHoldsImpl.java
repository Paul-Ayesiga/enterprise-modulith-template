package ug.co.smsone.compliance.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.compliance.LegalHolds;

/** The port impl: a cheap indexed existence check, consulted by the purge job and the erasure path. */
@Service
class LegalHoldsImpl implements LegalHolds {

    private final LegalHoldRepository holds;

    LegalHoldsImpl(LegalHoldRepository holds) {
        this.holds = holds;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean subjectHeld(String subject) {
        return subject != null && holds.existsBySubjectAndReleasedAtIsNull(subject);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean orgHeld(UUID organizationId) {
        return organizationId != null && holds.existsByOrgIdAndReleasedAtIsNull(organizationId);
    }
}
