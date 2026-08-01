package ug.co.smsone.exchange.internal;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

interface ExchangeScheduleRepository
        extends JpaRepository<ExchangeSchedule, UUID>, JpaSpecificationExecutor<ExchangeSchedule> {

    Optional<ExchangeSchedule> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);

    /**
     * Due schedules, row-locked with SKIP LOCKED (timeout -2 is Hibernate's spelling of it): the
     * firing job runs under ShedLock so normally one instance scans, but the row locks make even a
     * lock-lease overlap fire each schedule once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select s from ExchangeSchedule s where s.enabled = true and s.nextRunAt <= :now")
    List<ExchangeSchedule> lockDue(@Param("now") Instant now, Limit limit);
}
