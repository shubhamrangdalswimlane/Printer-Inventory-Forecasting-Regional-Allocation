package com.pifa.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.security.CurrentUser;
import com.pifa.workflow.CyclePurger;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 3 exit criterion: "a proposal for 10,000 products completes in under 10 s". A cycle with
 * 10,000 products x 4 regions and random demand and stock is created directly in the database, then the
 * real {@link AllocationService#propose} (Drools classification + calculator + database writes) is timed.
 * The products are created inactive so they never leak into other tests' working copies.
 */
@SpringBootTest
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
class AllocationPerformanceTest {

    private static final String MONTH = "2035-01";
    private static final CurrentUser PLANNER = new CurrentUser("pat.planner", Set.of("planner"));

    @MockBean JwtDecoder jwtDecoder;
    @Autowired AllocationService allocation;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void proposalFor10000ProductsUnder10Seconds() {
        cleanUp();
        UUID c = UUID.randomUUID();
        jdbc.sql("INSERT INTO planning_cycle (id, month, state) VALUES (:id, :m, 'PLANNING')").param("id", c).param("m", MONTH).update();
        jdbc.sql("INSERT INTO product (id, product_name, normalized_name, active, source) SELECT gen_random_uuid(), 'Perf Model ' || lpad(g::text, 5, '0'), "
            + "'perf model ' || lpad(g::text, 5, '0'), FALSE, 'PERF' FROM generate_series(1, 10000) g").update();
        jdbc.sql("""
                INSERT INTO plan_line (id, cycle_id, product_id, region_code, uploaded_qty, published_qty, revised_qty)
                SELECT gen_random_uuid(), :c, p.id, r.code, x.q, x.q, x.q FROM product p CROSS JOIN region r
                CROSS JOIN LATERAL (SELECT (random() * 200)::int + (p.id IS NULL)::int * 0 AS q) x WHERE p.source = 'PERF'
                """).param("c", c).update();
        jdbc.sql("INSERT INTO plan_product_stock (cycle_id, product_id, available_qty) SELECT :c, id, (random() * 800)::int FROM product WHERE source = 'PERF'")
            .param("c", c).update();

        long t0 = System.nanoTime();
        var r = allocation.propose(c, true, PLANNER);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("PERF: proposal for " + r.products() + " products (" + r.constrained() + " constrained) took " + ms + " ms");

        assertThat(r.products()).isEqualTo(10_000);
        assertThat(ms).as("proposal time in ms").isLessThan(10_000);
        long t1 = System.nanoTime();
        int errors = allocation.errorCount(c, "APPROVED");
        long validateMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("PERF: validation of 40,000 allocation lines took " + validateMs + " ms");
        assertThat(errors).isZero(); // never above stock, never negative
        assertThat(validateMs).as("validation time in ms (used by the approve guard and the violations panel)").isLessThan(10_000);
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT id FROM planning_cycle WHERE month = :m").param("m", MONTH).query(UUID.class).optional().ifPresent(id -> CyclePurger.purge(jdbc, id));
            jdbc.sql("DELETE FROM product WHERE source = 'PERF'").update();
        });
    }
}
