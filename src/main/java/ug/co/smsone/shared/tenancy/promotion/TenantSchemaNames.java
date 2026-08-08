package ug.co.smsone.shared.tenancy.promotion;

import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * The two shapes a tenant schema may take, as one guard: {@code tenant_pool} or {@code t_<32 hex>}.
 *
 * <p>Everything in this package interpolates schema names into DDL and into {@code INSERT … SELECT}
 * across two schemas, and a schema name <strong>cannot be a bound parameter</strong> — so validating
 * it is not defence in depth, it is the entire defence. {@code TenantSchemas.requireSiloSchema} is the
 * regex ADR 0010 §3.1 specifies; this adds the one legal name that regex deliberately excludes, because
 * the promoter's source is usually the pool and its destination usually a silo, and a demotion swaps
 * them.
 *
 * <p>{@code TenantSchemaMigrator} makes the same judgement privately for the same reason. Two callers
 * with the same rule is a rule that can drift; this is the copy the promotion path uses, and the day a
 * third appears the right move is to lift it into {@code TenantSchemas} beside the regex.
 */
final class TenantSchemaNames {

    private TenantSchemaNames() {}

    /**
     * @throws IllegalArgumentException if this is not a name a tenant's rows may live under. Nothing
     *     else may reach a statement built in this package.
     */
    static String require(String schemaName) {
        if (TenantSchemas.TENANT_POOL.equals(schemaName)) {
            return schemaName;
        }
        return TenantSchemas.requireSiloSchema(schemaName);
    }

    /** True for a promoted tenant's own schema, false for the shared pool. */
    static boolean isSilo(String schemaName) {
        return !TenantSchemas.TENANT_POOL.equals(schemaName);
    }
}
