package com.pifa.api;

import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetType;
import com.pifa.ingestion.ExcelFiles;
import com.pifa.ingestion.IngestionJobService;
import com.pifa.ingestion.IngestionJobService.JobRow;
import com.pifa.ingestion.IngestionService.Finding;
import com.pifa.master.MasterDataService;
import com.pifa.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for ingestion jobs, error reports and blank Excel templates. */
@RestController
@RequestMapping("/api/v1")
public class IngestionController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final IngestionJobService jobs;
    private final MasterDataService master;

    public IngestionController(IngestionJobService jobs, MasterDataService master) {
        this.jobs = jobs;
        this.master = master;
    }

    /** {@code GET /ingestion-jobs?limit=50} - recent ingestion attempts visible to the caller's role. */
    @GetMapping("/ingestion-jobs")
    public List<JobRow> list(@RequestParam(defaultValue = "50") int limit, Authentication auth) {
        return jobs.list(CurrentUser.from(auth), limit);
    }

    /** {@code GET /ingestion-jobs/{id}/findings} - all errors and warnings of one job, as JSON. */
    @GetMapping("/ingestion-jobs/{id}/findings")
    public List<Finding> findings(@PathVariable UUID id, Authentication auth) {
        return jobs.findings(id, CurrentUser.from(auth));
    }

    /** {@code GET /ingestion-jobs/{id}/error-report} - the findings as a downloadable CSV. */
    @GetMapping("/ingestion-jobs/{id}/error-report")
    public ResponseEntity<byte[]> errorReport(@PathVariable UUID id, Authentication auth) {
        byte[] csv = jobs.errorReportCsv(id, CurrentUser.from(auth));
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("errors-" + id + ".csv").build().toString())
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .body(csv);
    }

    /**
     * {@code GET /templates/{forecast|inventory}} - blank template with one example row.
     * Forecast: Forecaster or Admin. Inventory: Planner or Admin.
     */
    @GetMapping("/templates/{type}")
    public ResponseEntity<byte[]> template(@PathVariable String type, Authentication auth) {
        DatasetType t = DatasetType.fromPath(type);
        CurrentUser user = CurrentUser.from(auth);
        String role = t == DatasetType.FORECAST_UPLOAD ? "forecaster" : "planner";
        if (!(user.has(role) || user.has("admin"))) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Requires role " + role + " or admin");
        byte[] bytes = t == DatasetType.FORECAST_UPLOAD
            ? ExcelFiles.forecast(master.activeRegionCodes(),
                List.of(new ExcelFiles.ForecastRow("Mono Laser M100", Map.of("NORTH", 40, "EAST", 35, "WEST", 30, "SOUTH", 25), "example row - delete")),
                "2026-11", null)
            : ExcelFiles.inventory(List.of(new ExcelFiles.InventoryRow("Mono Laser M100", 100)), LocalDate.now().minusDays(1), "2026-11", null);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(type.toLowerCase() + "_template.xlsx").build().toString())
            .contentType(MediaType.parseMediaType(XLSX))
            .body(bytes);
    }
}
