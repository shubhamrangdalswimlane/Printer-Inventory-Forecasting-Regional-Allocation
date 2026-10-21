package com.pifa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.rules.model.ForecastCell;
import com.pifa.rules.model.ForecastChange;
import com.pifa.rules.model.ForecastConfig;
import com.pifa.rules.model.PublishCheck;
import com.pifa.rules.model.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for forecast.drl (BR-VA-05/06, BR-FC-MAX, BR-FC-SPIKE) and workflow.drl (BR-WF-01). */
class ForecastRulesTest {

    private final RulesFacade rules = new RulesFacade();
    private final ForecastConfig cfg = new ForecastConfig(20, 3.0, 1_000_000);

    private List<String> codes(List<?> facts) {
        return rules.validateForecast(facts, cfg).stream().map(Violation::getCode).toList();
    }

    @Test
    void changeWithinThresholdNeedsNoReason() {
        // 100 -> 120 is exactly 20%: not above the threshold
        assertThat(codes(List.of(new ForecastChange("A", "NORTH", 100, 120, null)))).isEmpty();
    }

    @Test
    void changeAboveThresholdWithoutReasonIsAnError() {
        assertThat(codes(List.of(new ForecastChange("A", "NORTH", 100, 121, " ")))).containsExactly("BR-VA-05");
    }

    @Test
    void changeAboveThresholdWithReasonIsAccepted() {
        assertThat(codes(List.of(new ForecastChange("A", "NORTH", 100, 150, "promotion")))).isEmpty();
    }

    @Test
    void decreaseIsMeasuredToo() {
        assertThat(codes(List.of(new ForecastChange("A", "EAST", 100, 70, null)))).containsExactly("BR-VA-05");
    }

    @Test
    void changeAbove100PercentWarnsEvenWithReason() {
        var found = rules.validateForecast(List.of(new ForecastChange("A", "WEST", 100, 250, "big launch")), cfg);
        assertThat(found).extracting(Violation::getCode).containsExactly("BR-VA-06");
        assertThat(found.get(0).getSeverity()).isEqualTo(Violation.Severity.WARNING);
    }

    @Test
    void changeFromZeroCountsAsVeryLarge() {
        assertThat(codes(List.of(new ForecastChange("A", "SOUTH", 0, 5, null)))).containsExactlyInAnyOrder("BR-VA-05", "BR-VA-06");
    }

    @Test
    void cellAboveMaximumIsAnError() {
        assertThat(codes(List.of(new ForecastCell("A", "NORTH", 2_000_000, -1)))).containsExactly("BR-FC-MAX");
    }

    @Test
    void cellFarAboveSalesIsAWarning() {
        assertThat(codes(List.of(new ForecastCell("A", "NORTH", 400, 100.0)))).containsExactly("BR-FC-SPIKE");
        assertThat(codes(List.of(new ForecastCell("A", "NORTH", 400, -1)))).isEmpty();
    }

    @Test
    void publishGuardNeedsActiveForecastAndNoErrors() {
        assertThat(rules.checkPublish(new PublishCheck(false, 0))).extracting(Violation::getCode).containsExactly("BR-WF-01");
        assertThat(rules.checkPublish(new PublishCheck(true, 3))).extracting(Violation::getCode).containsExactly("BR-WF-01");
        assertThat(rules.checkPublish(new PublishCheck(true, 0))).isEmpty();
    }
}
