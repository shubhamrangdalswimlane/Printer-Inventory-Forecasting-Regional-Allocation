package com.pifa.api;

import com.pifa.common.ApiException;
import com.pifa.golive.GoLiveTemplates;
import com.pifa.golive.MatchingReportService;
import com.pifa.golive.ReadinessService;
import com.pifa.golive.ReconciliationService;
import com.pifa.golive.SalesHistoryImportService;
import com.pifa.security.CurrentUser;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Go-live tools for Admins (Phase 6): readiness check, product matching report, real sales-history import,
 * parallel-run reconciliation, and the templates for those files. All endpoints require the Admin role.
 */
@RestController
@RequestMapping("/api/v1/admin/go-live")
@PreAuthorize("hasRole('ADMIN')")
public class GoLiveController {

    private final ReadinessService readiness;
    private final MatchingReportService matching;
    private final SalesHistoryImportService sales;
    private final ReconciliationService reconciliation;
    private final GoLiveTemplates templates;

    public GoLiveController(ReadinessService readiness, MatchingReportService matching, SalesHistoryImportService sales,
                            ReconciliationService reconciliation, GoLiveTemplates templates) {
        this.readiness = readiness;
        this.matching = matching;
        this.sales = sales;
        this.reconciliation = reconciliation;
        this.templates = templates;
    }

    /** {@code GET /admin/go-live/readiness} - every automatable go-live checklist item with PASS/WARN/FAIL, plus the manual ones. */
    @GetMapping("/readiness")
    public ReadinessService.Report readiness() {
        return readiness.run();
    }

    /** {@code POST /admin/go-live/matching-report} (multipart {@code file}) - which product names in a real file match the master. */
    @PostMapping("/matching-report")
    public MatchingReportService.Report matchingReport(@RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        return matching.report(file.getBytes(), name(file), CurrentUser.from(auth));
    }

    /**
     * {@code POST /admin/go-live/sales-history} (multipart {@code file}) - imports real monthly sales, all-or-nothing.
     * Answers 200 with the counts, or 422 with the problems (nothing imported).
     */
    @PostMapping("/sales-history")
    public ResponseEntity<SalesHistoryImportService.Result> importSales(@RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        var result = sales.importSales(file.getBytes(), name(file), "UPLOAD", CurrentUser.from(auth).username());
        return ResponseEntity.status(result.ok() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }

    /** {@code POST /admin/go-live/cycles/{id}/reconciliation} (multipart {@code file}) - compares the old process's allocation with PIFA's. */
    @PostMapping("/cycles/{id}/reconciliation")
    public ReconciliationService.Result reconcile(@PathVariable UUID id, @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        return reconciliation.reconcile(id, file.getBytes(), name(file), CurrentUser.from(auth));
    }

    /** {@code GET /admin/go-live/templates/{kind}} - empty {@code sales} or {@code allocation} workbook. */
    @GetMapping("/templates/{kind}")
    public ResponseEntity<byte[]> template(@PathVariable String kind) {
        byte[] bytes = templates.template(kind);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("PIFA_" + kind + "_template.xlsx").build().toString())
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    /**
     * @return the client's file name without any path, or "upload.xlsx"
     * @throws ApiException 400 FILE_EMPTY for an empty upload
     */
    private static String name(MultipartFile file) {
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "The file is empty");
        String n = file.getOriginalFilename();
        if (n == null || n.isBlank()) return "upload.xlsx";
        return n.replaceAll(".*[/\\\\]", "");
    }
}
