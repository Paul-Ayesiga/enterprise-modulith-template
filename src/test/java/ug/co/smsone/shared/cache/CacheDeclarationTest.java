package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;

/**
 * The ADR 0010 §3.5 gate: <b>every cache name in the codebase is classified GLOBAL or TENANT.</b>
 *
 * <p><b>Why this test is the deliverable and not the registry.</b> Writing the registry fixes seven
 * caches once. This is what stops the eighth being added unclassified — and the eighth is the dangerous
 * one, because nothing about {@code @Cacheable("org-tickets")} looks different from
 * {@code @Cacheable("feature-flags")} at the point it is written. {@link TwoLevelCacheManager} already
 * refuses an undeclared name at runtime, but a runtime refusal is found by whoever exercises that path
 * first, which in the worst case is production. This finds it on the commit, without a container.
 *
 * <p><b>It reads the compiled annotations, not the source.</b> Cache names live on constants
 * ({@code PermissionResolver.CACHE}, {@code EntitlementResolver.CACHE}) that a lexical scan would have
 * to resolve for itself, badly. String constants are inlined by javac, so by the time the class file
 * exists {@code cacheNames = CACHE} IS the literal — the compiler does the resolution and this only has
 * to read it. The one thing bytecode cannot show is a name handed to {@code cacheManager.getCache(...)}
 * as a literal at a call site, so that arm reads the source.
 *
 * <p>The assertion runs in BOTH directions, deliberately: an undeclared name fails because it is the
 * leak, and a declaration nobody uses fails because a registry with stale lines is one people stop
 * reading. Same shape as
 * {@code SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity}.
 */
class CacheDeclarationTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /**
     * {@code cacheManager.getCache("literal")} — the one shape the bytecode scan cannot see, reaching
     * the same manager with the same consequences.
     */
    private static final Pattern GET_CACHE_LITERAL = Pattern.compile("getCache\\(\\s*\"([^\"]+)\"\\s*\\)");

    /**
     * {@code @Caching} bundles {@code @Cacheable}/{@code @CacheEvict}/{@code @CachePut} inside itself,
     * where the walk below does not look. Nothing uses it today; if that changes, this test has to learn
     * to unwrap it before it can keep claiming to enumerate everything.
     */
    private static final Pattern CACHING_BUNDLE = Pattern.compile("@Caching\\s*\\(");

    @Test
    void everyCacheNameInTheCodebaseIsDeclaredInTheRegistry() {
        Set<String> used = cacheNamesInMainCode();

        assertThat(used)
                .describedAs("no @Cacheable/@CacheEvict was found at all — this test would pass without "
                        + "checking anything")
                .isNotEmpty();

        assertThat(used)
                .describedAs("""
                        Cache names used in the codebase must match CacheRegistry exactly.
                        A name here but NOT in the registry is an unclassified cache: TwoLevelCacheManager \
                        refuses to create it, and it refuses because a cache nobody classified as GLOBAL or \
                        TENANT is the one that serves one tenant's rows to another (ADR 0010 §3.5).
                        A name in the registry but NOT here is a stale declaration — delete the line.""")
                .containsExactlyInAnyOrderElementsOf(CacheRegistry.standard().declaredNames());
    }

    /**
     * The lexical half: a cache name written as a literal at a {@code getCache} call site, and the
     * {@code @Caching} bundle the annotation walk cannot see through. Neither survives into a form that
     * walk can read, so both are checked over the source.
     */
    @Test
    void cacheNamesReachedOutsideTheAnnotationsAreDeclaredToo() {
        Set<String> declared = CacheRegistry.standard().declaredNames();
        List<String> violations = new ArrayList<>();

        for (Path path : mainSources()) {
            String source = read(path);
            Matcher literal = GET_CACHE_LITERAL.matcher(source);
            while (literal.find()) {
                if (!declared.contains(literal.group(1))) {
                    violations.add(path + " — getCache(\"" + literal.group(1) + "\") names a cache that is"
                            + " not declared in CacheRegistry");
                }
            }
            if (CACHING_BUNDLE.matcher(source).find()) {
                violations.add(path + " — @Caching bundles cache annotations inside itself, and"
                        + " CacheDeclarationTest does not unwrap it yet. Teach it to, or the names inside"
                        + " that bundle are enumerated by nothing.");
            }
        }

        assertThat(violations)
                .describedAs("cache names reached without a @Cacheable/@CacheEvict annotation")
                .isEmpty();
    }

    /**
     * Every {@code cacheNames} (and {@code value} — they are {@code @AliasFor} each other, so only the
     * one actually written is populated when the annotation is read straight off the element) on every
     * class and method in main.
     */
    private static Set<String> cacheNamesInMainCode() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ug.co.smsone");

        Set<String> names = new TreeSet<>();
        for (JavaClass type : classes) {
            collectFrom(type, names);
            for (JavaMethod method : type.getMethods()) {
                collectFrom(method, names);
            }
        }
        return names;
    }

    private static void collectFrom(HasAnnotations<?> element, Set<String> names) {
        element.tryGetAnnotationOfType(Cacheable.class).ifPresent(annotation -> {
            add(names, annotation.cacheNames());
            add(names, annotation.value());
        });
        element.tryGetAnnotationOfType(CacheEvict.class).ifPresent(annotation -> {
            add(names, annotation.cacheNames());
            add(names, annotation.value());
        });
        element.tryGetAnnotationOfType(CachePut.class).ifPresent(annotation -> {
            add(names, annotation.cacheNames());
            add(names, annotation.value());
        });
        // Class-level defaults: a method naming no cache of its own inherits these.
        element.tryGetAnnotationOfType(CacheConfig.class)
                .ifPresent(annotation -> add(names, annotation.cacheNames()));
    }

    private static void add(Set<String> names, String[] values) {
        Arrays.stream(values).filter(name -> !name.isBlank()).forEach(names::add);
    }

    private static List<Path> mainSources() {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(sources).describedAs("java sources under %s", MAIN_SOURCES).isNotEmpty();
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + MAIN_SOURCES.toAbsolutePath(), e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
