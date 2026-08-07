package ug.co.smsone.compliance.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.compliance.LegalHolds;

/**
 * The port impl: a cheap indexed existence check on {@code idx_legal_hold_active_person}, consulted by
 * the erasure path. The purge job does NOT come through here — it inlines the same predicate as SQL.
 */
@Service
class LegalHoldsImpl implements LegalHolds {

    private final LegalHoldRepository holds;

    LegalHoldsImpl(LegalHoldRepository holds) {
        this.holds = holds;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean personHeld(UUID personId) {
        return personId != null && holds.existsByPersonIdAndReleasedAtIsNull(personId);
    }
}
