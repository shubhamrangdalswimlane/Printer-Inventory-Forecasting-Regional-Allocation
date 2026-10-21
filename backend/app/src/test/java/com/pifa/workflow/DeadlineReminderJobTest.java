package com.pifa.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.TestData;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

/** Phase 7 deadline reminders: due-soon and overdue are each sent once per region and deadline, and only to regions still owing. */
@SpringBootTest
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
class DeadlineReminderJobTest {

    private static final String MONTH = "2037-05";

    @MockBean JwtDecoder jwtDecoder;
    @Autowired DeadlineReminderJob job;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void cleanUp() {
        TestData.wipeCycles(tx, jdbc, MONTH);
    }

    @Test
    void remindsOnceBeforeAndOnceAfterTheDeadline() {
        TestData.wipeCycles(tx, jdbc, MONTH);
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime deadline = now.plusHours(2);
        UUID cycle = UUID.randomUUID();
        jdbc.sql("INSERT INTO planning_cycle (id, month, state, review_deadline) VALUES (:id, :m, 'REGIONAL_REVIEW', :d)")
            .param("id", cycle).param("m", MONTH).param("d", deadline).update();
        for (String r : new String[]{"NORTH", "EAST", "WEST", "SOUTH"}) {
            jdbc.sql("INSERT INTO cycle_region_status (cycle_id, region_code, review_status) VALUES (:c, :r, :s)")
                .param("c", cycle).param("r", r).param("s", r.equals("NORTH") ? "SUBMITTED" : "OPEN").update();
        }

        assertThat(job.runOnce(now.minusHours(30))).as("more than 24 h before: nothing yet").isZero();
        assertThat(job.runOnce(now)).as("within 24 h: one reminder per open region").isEqualTo(3);
        assertThat(job.runOnce(now.plusMinutes(30))).as("never twice").isZero();
        assertThat(job.runOnce(deadline.plusMinutes(1))).as("after the deadline: one overdue per open region").isEqualTo(3);
        assertThat(job.runOnce(deadline.plusHours(5))).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE cycle_id = :c AND title LIKE 'Overdue:%'").param("c", cycle)
            .query(Long.class).single()).as("overdue reaches the Regional Managers and the Planner").isPositive();

        // Moving the deadline makes the reminders due again for the new date.
        jdbc.sql("UPDATE planning_cycle SET review_deadline = :d WHERE id = :c").param("d", deadline.plusDays(1)).param("c", cycle).update();
        assertThat(job.runOnce(deadline.plusHours(10))).isEqualTo(3);
    }
}
