package com.pifa.master;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reads and writes the product and region master tables. */
@Service
public class MasterDataService {

    /** A product id with its display name. */
    public record Product(UUID id, String name) {}

    private final JdbcClient jdbc;

    public MasterDataService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return codes of active regions in display order (NORTH, EAST, WEST, SOUTH); these are also the
     *         region column headers expected in the forecast workbook
     */
    public List<String> activeRegionCodes() {
        return jdbc.sql("SELECT code FROM region WHERE active ORDER BY display_order")
            .query(String.class).list();
    }

    /**
     * Builds the lookup used to match {@code Product_name} cells.
     *
     * @return map from normalised product name or alias to the product id; only active products
     *         are included (a deactivated product behaves as unknown)
     */
    public Map<String, UUID> productLookup() {
        Map<String, UUID> map = new HashMap<>();
        jdbc.sql("SELECT normalized_name, id FROM product WHERE active")
            .query((rs, n) -> map.put(rs.getString(1), rs.getObject(2, UUID.class))).list();
        jdbc.sql("SELECT a.normalized_alias, a.product_id FROM product_alias a JOIN product p ON p.id = a.product_id WHERE p.active")
            .query((rs, n) -> map.put(rs.getString(1), rs.getObject(2, UUID.class))).list();
        return map;
    }

    /** @return all active products ordered by name */
    public List<Product> activeProducts() {
        return jdbc.sql("SELECT id, product_name FROM product WHERE active ORDER BY product_name")
            .query((rs, n) -> new Product(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    /** @return map product id to display name, for all products (including inactive) */
    public Map<UUID, String> productNames() {
        Map<UUID, String> map = new HashMap<>();
        jdbc.sql("SELECT id, product_name FROM product")
            .query((rs, n) -> map.put(rs.getObject(1, UUID.class), rs.getString(2))).list();
        return map;
    }

    /**
     * Adds a product, or returns the existing one with the same normalised name.
     *
     * @param name   display name, e.g. {@code Mono Laser M100}
     * @param source {@code MANUAL} or {@code SYNTHETIC}
     * @return the id of the new or existing product
     */
    public UUID addProduct(String name, String source) {
        String key = ProductNames.normalize(name);
        return jdbc.sql("SELECT id FROM product WHERE normalized_name = :k").param("k", key)
            .query(UUID.class).optional().orElseGet(() -> {
                UUID id = UUID.randomUUID();
                jdbc.sql("INSERT INTO product (id, product_name, normalized_name, source) VALUES (:id, :n, :k, :s)")
                    .param("id", id).param("n", name.trim()).param("k", key).param("s", source).update();
                return id;
            });
    }

    /**
     * Registers an alternative spelling for a product.
     *
     * @param productId the product the alias points to
     * @param alias     the alternative name as it appears in files
     */
    public void addAlias(UUID productId, String alias) {
        jdbc.sql("INSERT INTO product_alias (id, product_id, normalized_alias) VALUES (:id, :p, :a) ON CONFLICT DO NOTHING")
            .param("id", UUID.randomUUID()).param("p", productId).param("a", ProductNames.normalize(alias)).update();
    }
}
