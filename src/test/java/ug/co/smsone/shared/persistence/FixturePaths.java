package ug.co.smsone.shared.persistence;

import java.nio.file.Path;

/**
 * Where the tenant-migration test fixtures live, named once so the two tests that use them cannot
 * disagree about it.
 *
 * <p>They sit under {@code src/test/resources} and outside {@code db/migration} on purpose: a
 * deliberately failing migration in the real tenant sequence would fail every deploy forever, and a
 * fixture that took a number from AGENTS §4.5's single global counter would leave a hole in it. The
 * runner accepts its script set as a constructor parameter for exactly this reason.
 */
final class FixturePaths {

    /** Release N: {@code V9001} alone, so a schema has a previous version to be left at. */
    static final String RELEASE_N = "tenant-migration-fixtures/release-n";

    /** Release N+1: the same {@code V9001} plus the {@code V9002} that fails on a wedged schema. */
    static final String RELEASE_N_PLUS_ONE = "tenant-migration-fixtures/release-n1";

    /** A {@code .sql.conf} sidecar — the only thing on flyway-core 12.4.0 that can set
     * {@code executeInTransaction=false}, and what the tenant sequence must refuse to load. */
    static final String NON_TRANSACTIONAL = "tenant-migration-fixtures/nontransactional";

    /** The shared migration whose two copies must stay byte-identical, or release N+1 fails validation. */
    static final String SHARED_SCRIPT = "V9001__fixture_first.sql";

    private FixturePaths() {}

    static Path onDisk(String location) {
        return Path.of("src", "test", "resources").resolve(location);
    }
}
