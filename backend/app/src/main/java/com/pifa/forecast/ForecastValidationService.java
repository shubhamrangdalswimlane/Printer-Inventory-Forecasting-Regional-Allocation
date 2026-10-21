package com.pifa.forecast;

import com.pifa.config.PifaProperties;
import com.pifa.rules.RulesFacade;
import com.pifa.rules.model.ForecastCell;
import com.pifa.rules.model.ForecastConfig;
import com.pifa.rules.model.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Runs the Drools forecast checks over the whole working copy of a cycle (PRD FR-FC-05):
 * ERRORs (quantity above the maximum) block publishing; WARNINGs (a forecast far above the 6-month
 * sales average) are shown but do not block.
 */
@Service
public class ForecastValidationService {

    private final JdbcClient jdbc;
    private final RulesFacade rules;
    private final PifaProperties props;

    public ForecastValidationService(JdbcClient jdbc, RulesFacade rules, PifaProperties props) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.props = props;
    }

    /**
     * Validates the published-quantity column of the working copy.
     *
     * @param cycleId the cycle
     * @return all findings (errors and warnings); empty if the working copy is clean or does not exist
     */
    public List<Violation> validate(UUID cycleId) {
        List<ForecastCell> cells = new ArrayList<>();
        jdbc.sql("""
                SELECT p.product_name, l.region_code, l.published_qty, COALESCE(a.avg_qty, -1)
                FROM plan_line l JOIN product p ON p.id = l.product_id
                LEFT JOIN (
                    SELECT product_id, region_code, AVG(qty) AS avg_qty FROM sales_history
                    WHERE month IN (SELECT DISTINCT month FROM sales_history ORDER BY month DESC LIMIT 6)
                    GROUP BY product_id, region_code
                ) a ON a.product_id = l.product_id AND a.region_code = l.region_code
                WHERE l.cycle_id = :c
                """).param("c", cycleId)
            .query((rs, n) -> cells.add(new ForecastCell(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getDouble(4)))).list();
        ForecastConfig cfg = new ForecastConfig(props.forecast().changeThresholdPct(), props.ingestion().spikeFactor(), props.ingestion().maxQty());
        return rules.validateForecast(cells, cfg);
    }
}
