package ug.co.smsone.shared.persistence;

/**
 * The database-vendor SEAM for this template. A product built from the template on a non-Postgres
 * RDBMS provides its own {@code DbDialect} bean instead of hunting through queries for the
 * Postgres-isms. This is a porting AID, not a full ORM abstraction: the deep vendor differences
 * (the durable-queue claim <em>statements</em>, the {@code ON CONFLICT} upserts, and the native
 * full-text search) are whole-statement rewrites documented in {@code docs/PORTING.md} — a SQL
 * fragment cannot bridge, say, Postgres {@code UPDATE … FROM … RETURNING} and Oracle {@code MERGE}.
 * What lives here is the reusable clause that recurs verbatim across the queues.
 */
public interface DbDialect {

    /**
     * The row-locking clause that claims rows exclusively while SKIPPING rows another worker already
     * holds — the competing-consumer primitive under every durable queue (notification/webhook
     * delivery, exchange jobs). Postgres, Oracle, and MySQL&nbsp;8 all spell it
     * {@code for update skip locked}; SQL Server has no such clause (it uses a
     * {@code WITH (UPDLOCK, READPAST)} table hint), so on SQL Server the whole claim statement is
     * rewritten — see {@code docs/PORTING.md}.
     */
    String skipLocked();
}
