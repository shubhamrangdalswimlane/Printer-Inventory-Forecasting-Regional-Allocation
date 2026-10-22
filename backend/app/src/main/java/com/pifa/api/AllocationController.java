package com.pifa.api;

import com.pifa.allocation.AllocationService;
import com.pifa.allocation.StockService;
import com.pifa.rules.model.Violation;
import com.pifa.security.CurrentUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * REST endpoints of planning and allocation (PRD FR-PL-02..05, FR-PL-09, FR-PL-10). Approved-allocation
 * cell edits go through {@code PATCH /cycles/{id}/forecast/lines} with {@code field = APPROVED}, so they share
 * optimistic locking and the change log with forecast edits.
 */
@RestController
@RequestMapping("/api/v1/cycles/{id}")
public class AllocationController {

    /**
     * Body of a reserve change.
     *
     * @param reserveQty units to hold back
     * @param reason     why (mandatory)
     * @param rowVersion stock row version the client read
     */
    public record ReserveRequest(int reserveQty, String reason, long rowVersion) {}

    private final AllocationService allocation;
    private final StockService stock;

    public AllocationController(AllocationService allocation, StockService stock) {
        this.allocation = allocation;
        this.stock = stock;
    }

    /**
     * {@code GET /cycles/{id}/allocation?q=&constrained=false&page=0&size=50} - demand vs stock and the
     * allocation per region, one page of products, plus a cycle summary.
     */
    @GetMapping("/allocation")
    public AllocationService.GridPage grid(@PathVariable UUID id, @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "false") boolean constrained,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return allocation.grid(id, q, constrained, page, size);
    }

    /**
     * {@code POST /cycles/{id}/allocation/preview[?basis=FIXED|FORECAST_SHARE|SALES_SHARE]} - what a re-run would change,
     * without saving (Planner). The optional basis overrides how constrained stock is split for this run.
     */
    @PostMapping("/allocation/preview")
    public AllocationService.ProposalResult preview(@PathVariable UUID id, @RequestParam(required = false) String basis, Authentication auth) {
        return allocation.propose(id, false, CurrentUser.from(auth), basis);
    }

    /** {@code POST /cycles/{id}/allocation/proposal[?basis=...]} - run the allocation and store it (Planner); see preview for basis. */
    @PostMapping("/allocation/proposal")
    public AllocationService.ProposalResult propose(@PathVariable UUID id, @RequestParam(required = false) String basis, Authentication auth) {
        return allocation.propose(id, true, CurrentUser.from(auth), basis);
    }

    /** {@code GET /cycles/{id}/allocation/violations?stage=APPROVED|FINAL} - Drools allocation findings. */
    @GetMapping("/allocation/violations")
    public List<Map<String, Object>> violations(@PathVariable UUID id, @RequestParam(defaultValue = "APPROVED") String stage) {
        return allocation.validate(id, stage).stream().map(AllocationController::toMap).toList();
    }

    /** {@code PATCH /cycles/{id}/stock/{productId}} - set a product's reserve (Planner, PLANNING, reason required). */
    @PatchMapping("/stock/{productId}")
    public Map<String, Object> reserve(@PathVariable UUID id, @PathVariable UUID productId, @RequestBody ReserveRequest body, Authentication auth) {
        long v = stock.setReserve(id, productId, body.reserveQty(), body.reason(), body.rowVersion(), CurrentUser.from(auth));
        return Map.of("rowVersion", v);
    }

    /**
     * {@code GET /cycles/{id}/allocation/export?stage=approved&region=NORTH} - Excel export of a stage,
     * all regions or one region.
     */
    @GetMapping("/allocation/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "approved") String stage,
                                         @RequestParam(required = false) String region, Authentication auth) {
        byte[] bytes = allocation.export(id, stage, region, CurrentUser.from(auth));
        String name = "allocation_" + stage + (region == null ? "_all" : "_" + region.toLowerCase()) + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    private static Map<String, Object> toMap(Violation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", v.getCode());
        m.put("severity", v.getSeverity().name());
        m.put("productId", v.getSubject());
        m.put("region", v.getColumn());
        m.put("message", v.getMessage());
        return m;
    }
}
