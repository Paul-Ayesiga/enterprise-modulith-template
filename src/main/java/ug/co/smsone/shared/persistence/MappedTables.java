package ug.co.smsone.shared.persistence;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Type;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Where each mapped table lives, read off the entity mapping itself.
 *
 * <p>ADR 0010 §2 splits the schema in two, and the two halves are addressed differently on purpose:
 * a PLATFORM-tier table is named {@code platform.<table>} explicitly — Hibernate honours a declared
 * schema whatever the connection's {@code search_path} is, which is the only thing that keeps a
 * platform read correct on a tenant-pinned connection — while a TENANT-tier table is named bare, so
 * {@code search_path} decides whether it means {@code tenant_pool} or a silo. Get that backwards on
 * one table and it silently reads the wrong tier.
 *
 * <p>Which means the qualification is not a fact about a query, it is a fact about the MAPPING. This
 * class is the one place that reads it, so raw-SQL callers that address a table by name — the
 * soft-delete purge's batches, the recovery seam's restore — resolve the same home the ORM would
 * rather than carrying a second list of which tables are platform's. A second list is the thing that
 * would drift, and it would drift silently: the wrong answer is a statement that runs, matches
 * nothing, and reports success.
 *
 * <p>Populated from the metamodel, so the source of truth is {@code @Table(schema = "platform")} on
 * the entity — which {@code PlatformSchemaQualificationTest} pins to the {@code Tier:} lines in
 * {@code docs/DATA_MODEL.md}. A table with no entity ({@code search_document}, {@code shedlock},
 * {@code api_usage_daily}, …) is not here at all and is written qualified in its own SQL; asking for
 * one throws rather than guessing, because guessing "unqualified" would be the silent failure.
 */
@Component
public class MappedTables {

    /** ADR 0010 §2's one-copy schema. Not a search_path entry: it is always named. */
    public static final String PLATFORM_SCHEMA = "platform";

    private final Map<String, String> qualifiedByTable;

    MappedTables(EntityManagerFactory entityManagerFactory) {
        Map<String, String> byTable = new LinkedHashMap<>();
        entityManagerFactory.getMetamodel().getEntities().stream()
                .map(Type::getJavaType)
                .map(type -> type.getAnnotation(Table.class))
                .filter(table -> table != null && !table.name().isBlank())
                .forEach(table -> byTable.put(table.name().toLowerCase(Locale.ROOT), qualify(table)));
        this.qualifiedByTable = Map.copyOf(byTable);
    }

    /**
     * The name raw SQL must use for a mapped table: {@code platform.person}, but plain {@code ticket}.
     *
     * @throws IllegalArgumentException if no entity maps the table — the caller is holding a name the
     *     mapping does not know, and answering "unqualified" would be a guess that fails by matching
     *     nothing rather than by throwing.
     */
    public String qualified(String table) {
        String qualified = qualifiedByTable.get(table.toLowerCase(Locale.ROOT));
        if (qualified == null) {
            throw new IllegalArgumentException(
                    "No entity maps a table named '" + table + "', so its tenancy tier is unknown. Tables"
                            + " with no entity are schema-qualified in their own SQL (ADR 0010 §2).");
        }
        return qualified;
    }

    /** Every mapped table, bare name to the name SQL must use. Iteration order is the metamodel's. */
    public Map<String, String> all() {
        return qualifiedByTable;
    }

    /** The same rule off a single entity class, for callers that hold the type rather than the name. */
    public static String qualified(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        if (table == null || table.name().isBlank()) {
            // Every entity in this codebase names its table explicitly; a derived name would be a guess,
            // and guessing wrong here reads or writes the wrong table entirely.
            throw new IllegalStateException(
                    entityType.getSimpleName() + " must declare @Table(name = ...) to be addressable by"
                            + " raw SQL.");
        }
        return qualify(table);
    }

    private static String qualify(Table table) {
        return table.schema().isBlank() ? table.name() : table.schema() + "." + table.name();
    }
}
