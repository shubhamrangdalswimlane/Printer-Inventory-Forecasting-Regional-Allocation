package com.pifa.admin;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.ingestion.ExcelReader;
import com.pifa.master.ProductNames;
import com.pifa.rules.model.Violation;
import com.pifa.security.CurrentUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master data maintenance for Admin (PRD FR-AD-05): products, their aliases (alternative spellings used in
 * files) and region display names, plus a bulk import from Excel. Every change is audited.
 */
@Service
public class MasterDataAdminService {

    /** One product with its aliases. */
    public record ProductRow(UUID id, String name, String code, boolean active, String source, List<Map<String, Object>> aliases) {}

    /** Outcome of a bulk import. */
    public record ImportResult(int created, int updated, int aliasesAdded, List<String> problems) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public MasterDataAdminService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static void requireAdmin(CurrentUser u) {
        if (!u.has("admin")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Admin only");
    }

    /**
     * @param search          part of the name or alias, or null
     * @param includeInactive include deactivated products
     * @return products ordered by name, with aliases
     */
    public List<ProductRow> products(String search, boolean includeInactive) {
        String like = search == null || search.isBlank() ? null : "%" + ProductNames.normalize(search) + "%";
        Map<UUID, ProductRow> rows = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT p.id, p.product_name, p.active, p.source, p.product_code FROM product p
                WHERE (:all OR p.active) AND p.source <> 'PERF'
                  AND (CAST(:q AS TEXT) IS NULL OR p.normalized_name LIKE :q OR lower(p.product_code) LIKE :q OR EXISTS (SELECT 1 FROM product_alias a WHERE a.product_id = p.id AND a.normalized_alias LIKE :q))
                ORDER BY p.product_name LIMIT 2000
                """).param("all", includeInactive).param("q", like)
            .query((rs, n) -> rows.put(rs.getObject(1, UUID.class), new ProductRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(5), rs.getBoolean(3), rs.getString(4), new ArrayList<>()))).list();
        if (!rows.isEmpty()) {
            jdbc.sql("SELECT id, product_id, normalized_alias FROM product_alias WHERE product_id IN (:ids) ORDER BY normalized_alias")
                .param("ids", new ArrayList<>(rows.keySet()))
                .query((rs, n) -> rows.get(rs.getObject(2, UUID.class)).aliases().add(Map.of("id", rs.getObject(1, UUID.class), "alias", rs.getString(3)))).list();
        }
        return new ArrayList<>(rows.values());
    }

    /**
     * Adds a product.
     *
     * @return the new product's id
     * @throws ApiException 409 if the (normalised) name or an alias already uses it
     */
    @Transactional
    public UUID addProduct(String name, CurrentUser user) {
        requireAdmin(user);
        String key = requireName(name);
        requireFree(key, null);
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO product (id, product_name, normalized_name, source) VALUES (:id, :n, :k, 'MANUAL')").param("id", id).param("n", name.trim()).param("k", key).update();
        audit.record(user.username(), "PRODUCT_ADD", "product", id.toString(), Map.of("name", name.trim()));
        return id;
    }

    /**
     * Renames, (de)activates and/or sets the code of a product. A deactivated product behaves as unknown in new files.
     *
     * @param name   new name, or null to keep
     * @param active new state, or null to keep
     * @param code   new {@code Product_code} (Phase 7), "" to remove it, or null to keep
     * @throws ApiException 400 invalid code, 409 name or code already used, 404 unknown product
     */
    @Transactional
    public void updateProduct(UUID id, String name, Boolean active, String code, CurrentUser user) {
        requireAdmin(user);
        String old = jdbc.sql("SELECT product_name FROM product WHERE id = :id").param("id", id).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Product not found"));
        if (name != null && !name.isBlank()) {
            String key = requireName(name);
            requireFree(key, id);
            jdbc.sql("UPDATE product SET product_name = :n, normalized_name = :k WHERE id = :id").param("n", name.trim()).param("k", key).param("id", id).update();
        }
        if (active != null) jdbc.sql("UPDATE product SET active = :a WHERE id = :id").param("a", active).param("id", id).update();
        if (code != null) {
            String c = ProductNames.normalizeCode(code);
            if (!c.isEmpty() && !ProductNames.isValidCode(c)) throw new ApiException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "Codes may contain letters, digits, '.', '-' and '_' (max 50)");
            if (!c.isEmpty() && jdbc.sql("SELECT EXISTS (SELECT 1 FROM product WHERE product_code = :c AND id <> :id)").param("c", c).param("id", id).query(Boolean.class).single()) {
                throw new ApiException(HttpStatus.CONFLICT, "CODE_TAKEN", "Code " + c + " is already used by another product");
            }
            jdbc.sql("UPDATE product SET product_code = :c WHERE id = :id").param("c", c.isEmpty() ? null : c).param("id", id).update();
        }
        audit.record(user.username(), "PRODUCT_UPDATE", "product", id.toString(), Map.of("oldName", old, "newName", String.valueOf(name),
            "active", String.valueOf(active), "code", String.valueOf(code)));
    }

    /** Adds an alternative spelling to a product (409 if it is already a product name or another alias). */
    @Transactional
    public UUID addAlias(UUID productId, String alias, CurrentUser user) {
        requireAdmin(user);
        String key = requireName(alias);
        requireFree(key, null);
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO product_alias (id, product_id, normalized_alias) VALUES (:id, :p, :a)").param("id", id).param("p", productId).param("a", key).update();
        audit.record(user.username(), "ALIAS_ADD", "product", productId.toString(), Map.of("alias", key));
        return id;
    }

    /** Removes an alias. */
    @Transactional
    public void removeAlias(UUID productId, UUID aliasId, CurrentUser user) {
        requireAdmin(user);
        int n = jdbc.sql("DELETE FROM product_alias WHERE id = :a AND product_id = :p").param("a", aliasId).param("p", productId).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Alias not found");
        audit.record(user.username(), "ALIAS_REMOVE", "product", productId.toString(), Map.of("aliasId", aliasId.toString()));
    }

    /** @return regions in display order with name and active flag */
    public List<Map<String, Object>> regions() {
        return jdbc.sql("SELECT code, name, display_order, active FROM region ORDER BY display_order")
            .query((rs, n) -> Map.<String, Object>of("code", rs.getString(1), "name", rs.getString(2), "displayOrder", rs.getInt(3), "active", rs.getBoolean(4))).list();
    }

    /**
     * Renames a region's display name. Codes, order and active flags are not editable here: they drive the
     * file columns and every allocation, so changing them mid-cycle would break running cycles.
     */
    @Transactional
    public void renameRegion(String code, String name, CurrentUser user) {
        requireAdmin(user);
        if (name == null || name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Name is required");
        int n = jdbc.sql("UPDATE region SET name = :n WHERE code = :c").param("n", name.trim()).param("c", code.toUpperCase()).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Region not found");
        audit.record(user.username(), "REGION_RENAME", "region", code.toUpperCase(), Map.of("name", name.trim()));
    }

    /**
     * Bulk import from an Excel sheet {@code Products} with columns {@code Product_name}, optional
     * {@code Aliases} (comma separated), optional {@code Active} (yes/no) and optional {@code Product_code}. All-or-nothing: if any row has
     * a problem, nothing is changed and the problems are returned.
     *
     * @param bytes the .xlsx file
     * @param user  the caller (Admin)
     * @return counts, or the list of problems (then nothing was imported)
     */
    @Transactional
    public ImportResult importProducts(byte[] bytes, CurrentUser user) {
        requireAdmin(user);
        var sheet = ExcelReader.read(bytes, "Products", List.of("Product_name"), Set.of("Product_name", "Aliases", "Active", "Product_code"), 50_000);
        List<String> problems = new ArrayList<>(sheet.violations().stream().filter(v -> v.getSeverity() == Violation.Severity.ERROR).map(Violation::getMessage).toList());
        if (!problems.isEmpty()) return new ImportResult(0, 0, 0, problems);

        Map<String, UUID> existing = new HashMap<>();
        jdbc.sql("SELECT normalized_name, id FROM product").query((rs, n) -> existing.put(rs.getString(1), rs.getObject(2, UUID.class))).list();
        Map<String, UUID> aliasOwner = new HashMap<>();
        jdbc.sql("SELECT normalized_alias, product_id FROM product_alias").query((rs, n) -> aliasOwner.put(rs.getString(1), rs.getObject(2, UUID.class))).list();

        Map<String, UUID> codeOwner = new HashMap<>();
        jdbc.sql("SELECT product_code, id FROM product WHERE product_code IS NOT NULL").query((rs, n) -> codeOwner.put(rs.getString(1), rs.getObject(2, UUID.class))).list();
        Set<String> seenCodes = new java.util.HashSet<>();

        record Item(int row, String name, String key, List<String> aliases, Boolean active, String code) {}
        List<Item> items = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (var r : sheet.rows()) {
            Object raw = r.get("Product_name") == null ? null : r.get("Product_name").value();
            String name = raw == null ? "" : raw.toString().trim();
            String key = ProductNames.normalize(name);
            if (key.isEmpty()) { problems.add("Row " + r.rowNo() + ": Product_name is empty"); continue; }
            if (!seen.add(key)) { problems.add("Row " + r.rowNo() + ": '" + name + "' appears twice"); continue; }
            List<String> aliases = new ArrayList<>();
            var a = r.get("Aliases");
            if (a != null && a.value() != null) for (String s : a.value().toString().split(",")) if (!s.isBlank()) aliases.add(ProductNames.normalize(s));
            Boolean active = null;
            var act = r.get("Active");
            if (act != null && act.value() != null) {
                String v = act.value().toString().trim().toLowerCase();
                if (Set.of("yes", "y", "true", "1", "1.0").contains(v)) active = true;
                else if (Set.of("no", "n", "false", "0", "0.0").contains(v)) active = false;
                else problems.add("Row " + r.rowNo() + ": Active must be yes or no");
            }
            if (aliasOwner.containsKey(key) && !aliasOwner.get(key).equals(existing.get(key))) problems.add("Row " + r.rowNo() + ": '" + name + "' is already an alias of another product");
            for (String al : aliases) {
                UUID owner = aliasOwner.get(al);
                UUID self = existing.get(key);
                if ((owner != null && !owner.equals(self)) || (existing.containsKey(al) && !al.equals(key))) {
                    problems.add("Row " + r.rowNo() + ": alias '" + al + "' is already used by another product");
                }
            }
            String code = null;
            var pc = r.get("Product_code");
            if (pc != null && pc.value() != null) {
                Object v = pc.value();
                code = ProductNames.normalizeCode(v instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : v.toString());
                if (code.isEmpty()) code = null;
                else if (!ProductNames.isValidCode(code)) problems.add("Row " + r.rowNo() + ": Product_code '" + code + "' is not valid");
                else if (!seenCodes.add(code)) problems.add("Row " + r.rowNo() + ": Product_code " + code + " appears twice");
                else if (codeOwner.containsKey(code) && !codeOwner.get(code).equals(existing.get(key))) problems.add("Row " + r.rowNo() + ": Product_code " + code + " is already used by another product");
            }
            items.add(new Item(r.rowNo(), name, key, aliases, active, code));
        }
        if (!problems.isEmpty()) return new ImportResult(0, 0, 0, problems);

        int created = 0, updated = 0, aliasesAdded = 0;
        for (Item it : items) {
            UUID id = existing.get(it.key());
            if (id == null) {
                id = UUID.randomUUID();
                jdbc.sql("INSERT INTO product (id, product_name, normalized_name, active, source) VALUES (:id, :n, :k, :a, 'MANUAL')")
                    .param("id", id).param("n", it.name()).param("k", it.key()).param("a", it.active() == null || it.active()).update();
                created++;
            } else if (it.active() != null) {
                updated += jdbc.sql("UPDATE product SET active = :a WHERE id = :id AND active <> :a").param("a", it.active()).param("id", id).update();
            }
            if (it.code() != null) {
                updated += jdbc.sql("UPDATE product SET product_code = :c WHERE id = :id AND product_code IS DISTINCT FROM :c").param("c", it.code()).param("id", id).update();
            }
            for (String al : it.aliases()) {
                aliasesAdded += jdbc.sql("INSERT INTO product_alias (id, product_id, normalized_alias) VALUES (:id, :p, :a) ON CONFLICT DO NOTHING")
                    .param("id", UUID.randomUUID()).param("p", id).param("a", al).update();
            }
        }
        audit.record(user.username(), "PRODUCT_IMPORT", "product", null, Map.of("created", created, "updated", updated, "aliasesAdded", aliasesAdded));
        return new ImportResult(created, updated, aliasesAdded, List.of());
    }

    private static String requireName(String name) {
        String key = ProductNames.normalize(name);
        if (key.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "A name is required");
        if (name.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "NAME_TOO_LONG", "Names are limited to 200 characters");
        return key;
    }

    /** 409 if the normalised key is already a product name (other than {@code self}) or an alias. */
    private void requireFree(String key, UUID self) {
        boolean taken = jdbc.sql("SELECT EXISTS (SELECT 1 FROM product WHERE normalized_name = :k AND (CAST(:s AS UUID) IS NULL OR id <> :s)) "
                + "OR EXISTS (SELECT 1 FROM product_alias WHERE normalized_alias = :k)")
            .param("k", key).param("s", self).query(Boolean.class).single();
        if (taken) throw new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "'" + key + "' is already used by a product or an alias");
    }
}
