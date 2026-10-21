package com.pifa.api;

import com.pifa.forecast.ForecastEditService;
import com.pifa.forecast.ForecastEditService.EditRequest;
import com.pifa.forecast.ForecastEditService.EditedLine;
import com.pifa.forecast.ForecastQueryService;
import com.pifa.forecast.ForecastValidationService;
import com.pifa.forecast.SalesAnalysisService;
import com.pifa.rules.model.Violation;
import com.pifa.security.CurrentUser;
import com.pifa.workflow.CycleWorkflowService;
import com.pifa.workflow.CycleWorkflowService.CycleDetail;
import com.pifa.workflow.CycleWorkflowService.TransitionRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints of the forecast workflow: cycle status, permissions, transitions, the forecast grid,
 * cell edits, validation and sales analysis. Controllers only translate HTTP; rules live in the services.
 */
@RestController
@RequestMapping("/api/v1")
public class CycleController {

    /** Body of PATCH forecast/lines. @param edits the cell edits to apply together (all or nothing) */
    public record EditBatch(List<EditRequest> edits) {}

    private final CycleWorkflowService workflow;
    private final ForecastEditService edits;
    private final ForecastQueryService query;
    private final ForecastValidationService validation;
    private final SalesAnalysisService sales;

    public CycleController(CycleWorkflowService workflow, ForecastEditService edits, ForecastQueryService query,
                           ForecastValidationService validation, SalesAnalysisService sales) {
        this.workflow = workflow;
        this.edits = edits;
        this.query = query;
        this.validation = validation;
        this.sales = sales;
    }

    /** {@code GET /cycles/{id}} - the cycle with its state, row version, deadlines and per-region progress. */
    @GetMapping("/cycles/{id}")
    public CycleDetail detail(@PathVariable UUID id) {
        return workflow.detail(id);
    }

    /** {@code GET /cycles/{id}/permissions} - what the caller may do now (which cells and buttons to enable). */
    @GetMapping("/cycles/{id}/permissions")
    public Map<String, Object> permissions(@PathVariable UUID id, Authentication auth) {
        return workflow.permissions(id, CurrentUser.from(auth));
    }

    /**
     * {@code POST /cycles/{id}/transitions} - PUBLISH, RECALL, SUBMIT_REGION, PROCEED or RETURN_REGIONS.
     *
     * @return the cycle after the transition
     */
    @PostMapping("/cycles/{id}/transitions")
    public CycleDetail transition(@PathVariable UUID id, @RequestBody TransitionRequest body, Authentication auth) {
        return workflow.transition(id, body, CurrentUser.from(auth));
    }

    /**
     * {@code GET /cycles/{id}/forecast?q=&page=0&size=100} - one page of the forecast grid (all regions,
     * read-only for everyone; edits are controlled by the permissions).
     */
    @GetMapping("/cycles/{id}/forecast")
    public ForecastQueryService.GridPage grid(@PathVariable UUID id, @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return query.grid(id, q, page, size);
    }

    /** {@code PATCH /cycles/{id}/forecast/lines} - apply cell edits (all or nothing; 409 lists stale rows). */
    @PatchMapping("/cycles/{id}/forecast/lines")
    public List<EditedLine> edit(@PathVariable UUID id, @RequestBody EditBatch body, Authentication auth) {
        return edits.apply(id, body.edits(), CurrentUser.from(auth));
    }

    /** {@code GET /cycles/{id}/forecast/lines/{lineId}/history} - who changed this cell, when and why. */
    @GetMapping("/cycles/{id}/forecast/lines/{lineId}/history")
    public List<Map<String, Object>> history(@PathVariable UUID id, @PathVariable UUID lineId) {
        return query.history(lineId);
    }

    /** {@code GET /cycles/{id}/forecast/validation} - Drools findings over the working copy (errors block publishing). */
    @GetMapping("/cycles/{id}/forecast/validation")
    public List<Map<String, Object>> validate(@PathVariable UUID id) {
        return validation.validate(id).stream().map(CycleController::toMap).toList();
    }

    /** {@code GET /cycles/{id}/sales-analysis?region=NORTH} - per-product sales averages, trend and forecast for a region. */
    @GetMapping("/cycles/{id}/sales-analysis")
    public List<SalesAnalysisService.Row> salesAnalysis(@PathVariable UUID id, @RequestParam String region, Authentication auth) {
        return sales.analysis(id, region, CurrentUser.from(auth));
    }

    /** {@code GET /sales-history?productId=&region=&months=24} - monthly sales of one product in one region. */
    @GetMapping("/sales-history")
    public List<SalesAnalysisService.Point> salesHistory(@RequestParam UUID productId, @RequestParam String region,
                                                         @RequestParam(defaultValue = "24") int months, Authentication auth) {
        return sales.history(productId, region, months, CurrentUser.from(auth));
    }

    private static Map<String, Object> toMap(Violation v) {
        return Map.of("code", v.getCode(), "severity", v.getSeverity().name(), "column", String.valueOf(v.getColumn()), "message", v.getMessage());
    }
}
