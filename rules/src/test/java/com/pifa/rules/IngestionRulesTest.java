package com.pifa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.rules.model.DateCell;
import com.pifa.rules.model.IngestionConfig;
import com.pifa.rules.model.MonthCell;
import com.pifa.rules.model.ProductRow;
import com.pifa.rules.model.QtyCell;
import com.pifa.rules.model.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code ingestion.drl}. Each test builds a small set of facts, runs the rules through
 * {@link RulesFacade#validateIngestion} and asserts which codes (and severities) come out.
 */
class IngestionRulesTest {

    private final RulesFacade rules = new RulesFacade();
    private final IngestionConfig config = new IngestionConfig(1_000_000, 3.0);

    /** Runs the rules and returns just the codes, for compact assertions. */
    private List<String> codes(List<?> facts) {
        return rules.validateIngestion(facts, config).stream().map(Violation::getCode).toList();
    }

    @Test
    void cleanRowsProduceNoFindings() {
        var facts = List.of(
            new ProductRow(2, "Mono Laser M100", true, "mono laser m100"),
            new QtyCell(2, "North", true, 40, null, 38.0));
        assertThat(codes(facts)).isEmpty();
    }

    @Test
    void blankProductIsReported() {
        assertThat(codes(List.of(new ProductRow(2, "  ", false, "")))).containsExactly("PRODUCT_BLANK");
    }

    @Test
    void unknownProductIsReported() {
        assertThat(codes(List.of(new ProductRow(2, "Nope 9000", false, "nope 9000")))).containsExactly("PRODUCT_UNKNOWN");
    }

    @Test
    void duplicateIsReportedOnlyOnTheLaterRow() {
        var found = rules.validateIngestion(List.of(
            new ProductRow(2, "A", true, "a"),
            new ProductRow(3, "a ", true, "a")), config);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCode()).isEqualTo("PRODUCT_DUPLICATE");
        assertThat(found.get(0).getRowNo()).isEqualTo(3);
    }

    @Test
    void invalidQuantityIsReported() {
        assertThat(codes(List.of(new QtyCell(2, "East", false, 0, "negative", -1)))).containsExactly("QTY_INVALID");
    }

    @Test
    void quantityAboveMaximumIsReported() {
        assertThat(codes(List.of(new QtyCell(2, "East", true, 2_000_000, null, -1)))).containsExactly("QTY_INVALID");
    }

    @Test
    void spikeIsAWarningNotAnError() {
        var found = rules.validateIngestion(List.of(new QtyCell(2, "West", true, 400, null, 100.0)), config);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCode()).isEqualTo("QTY_SPIKE");
        assertThat(found.get(0).getSeverity()).isEqualTo(Violation.Severity.WARNING);
    }

    @Test
    void noSpikeWhenHistoryUnknown() {
        assertThat(codes(List.of(new QtyCell(2, "West", true, 400, null, -1)))).isEmpty();
    }

    @Test
    void monthRulesCatchInvalidAndDifferingMonths() {
        assertThat(codes(List.of(
            new MonthCell(2, "Forecast_Month", false, null, "2026-11"),
            new MonthCell(3, "Forecast_Month", true, "2026-12", "2026-11"))))
            .containsExactlyInAnyOrder("MONTH_MISMATCH", "MONTH_MISMATCH");
    }

    @Test
    void dateRulesCatchMissingInconsistentAndFuture() {
        assertThat(codes(List.of(
            new DateCell(2, false, true, false),
            new DateCell(3, true, false, false),
            new DateCell(4, true, true, true))))
            .containsExactlyInAnyOrder("DATE_INVALID", "DATE_INVALID", "DATE_INVALID");
    }
}
