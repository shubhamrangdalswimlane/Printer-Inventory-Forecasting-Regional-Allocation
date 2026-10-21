package com.pifa.api;

import com.pifa.admin.AdminInsightService;
import com.pifa.admin.AuditQueryService;
import com.pifa.admin.AuditQueryService.Filter;
import com.pifa.admin.CycleAdminService;
import com.pifa.admin.KeycloakUsersService;
import com.pifa.admin.MasterDataAdminService;
import com.pifa.audit.AuditChainVerifier;
import com.pifa.security.CurrentUser;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin dashboard endpoints (PRD FR-AD-01..09). Everything here requires the Admin role, except creating a
 * cycle (Admin or Forecaster, checked in the service).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    /** @param name product name or alias text */
    public record NameRequest(String name) {}

    /** @param name new name (null = keep) @param active new state (null = keep) @param code Product_code ("" = remove, null = keep) */
    public record ProductUpdate(String name, Boolean active, String code) {}

    /** @param month YYYY-MM @param reviewDeadline optional @param finalizeDeadline optional */
    public record CycleRequest(String month, OffsetDateTime reviewDeadline, OffsetDateTime finalizeDeadline) {}

    private final AdminInsightService insight;
    private final MasterDataAdminService master;
    private final AuditQueryService auditQuery;
    private final AuditChainVerifier verifier;
    private final CycleAdminService cycles;
    private final KeycloakUsersService users;

    public AdminController(AdminInsightService insight, MasterDataAdminService master, AuditQueryService auditQuery,
                           AuditChainVerifier verifier, CycleAdminService cycles, KeycloakUsersService users) {
        this.insight = insight;
        this.master = master;
        this.auditQuery = auditQuery;
        this.verifier = verifier;
        this.cycles = cycles;
        this.users = users;
    }

    // ---- overview and analytics (FR-AD-01, FR-AD-02, FR-AD-04)

    /** {@code GET /admin/overview?cycleId=} - state, region progress, deadlines, open issues, system health. */
    @GetMapping("/overview")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> overview(@RequestParam UUID cycleId) {
        return insight.overview(cycleId);
    }

    /** {@code GET /admin/system} - health rows (last SFTP poll, last audit verification, live connections). */
    @GetMapping("/system")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> system() {
        return insight.systemStatus();
    }

    /** {@code GET /admin/analytics?from=2026-01&to=2026-12} - per-cycle analytics. */
    @GetMapping("/analytics")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> analytics(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return insight.analytics(from, to);
    }

    // ---- master data (FR-AD-05)

    /** {@code GET /admin/products?q=&includeInactive=false} */
    @GetMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    public List<MasterDataAdminService.ProductRow> products(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return master.products(q, includeInactive);
    }

    /** {@code POST /admin/products} body {@code {"name": "..."}} */
    @PostMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> addProduct(@RequestBody NameRequest body, Authentication auth) {
        return Map.of("id", master.addProduct(body.name(), CurrentUser.from(auth)));
    }

    /** {@code PATCH /admin/products/{id}} body {@code {"name": "...", "active": true, "code": "PRN-M100"}} (all optional) */
    @PatchMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateProduct(@PathVariable UUID id, @RequestBody ProductUpdate body, Authentication auth) {
        master.updateProduct(id, body.name(), body.active(), body.code(), CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /admin/products/{id}/aliases} body {@code {"name": "alternative spelling"}} */
    @PostMapping("/products/{id}/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> addAlias(@PathVariable UUID id, @RequestBody NameRequest body, Authentication auth) {
        return Map.of("id", master.addAlias(id, body.name(), CurrentUser.from(auth)));
    }

    /** {@code DELETE /admin/products/{id}/aliases/{aliasId}} */
    @DeleteMapping("/products/{id}/aliases/{aliasId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeAlias(@PathVariable UUID id, @PathVariable UUID aliasId, Authentication auth) {
        master.removeAlias(id, aliasId, CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /admin/products/import} (multipart {@code file}) - bulk import from sheet "Products". */
    @PostMapping("/products/import")
    @PreAuthorize("hasRole('ADMIN')")
    public MasterDataAdminService.ImportResult importProducts(@RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        return master.importProducts(file.getBytes(), CurrentUser.from(auth));
    }

    /** {@code GET /admin/regions} */
    @GetMapping("/regions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> regions() {
        return master.regions();
    }

    /** {@code PATCH /admin/regions/{code}} body {@code {"name": "..."}} - display name only */
    @PatchMapping("/regions/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> renameRegion(@PathVariable String code, @RequestBody NameRequest body, Authentication auth) {
        master.renameRegion(code, body.name(), CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    // ---- audit (FR-AD-07)

    /** {@code GET /admin/audit?actor=&action=&entityType=&entityId=&from=&to=&q=&page=0&size=50} */
    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> audit(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                     @RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                     @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return auditQuery.search(new Filter(actor, action, entityType, entityId, from, to, q), page, size);
    }

    /** {@code GET /admin/audit/export?format=csv|xlsx&...same filters} - up to 100,000 rows. */
    @GetMapping("/audit/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> auditExport(@RequestParam(defaultValue = "xlsx") String format, @RequestParam(required = false) String actor,
                                              @RequestParam(required = false) String action, @RequestParam(required = false) String entityType,
                                              @RequestParam(required = false) String entityId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                              @RequestParam(required = false) String q) {
        byte[] bytes = auditQuery.export(new Filter(actor, action, entityType, entityId, from, to, q), format);
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("audit." + (xlsx ? "xlsx" : "csv")).build().toString())
            .contentType(MediaType.parseMediaType(xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv; charset=utf-8"))
            .body(bytes);
    }

    /** {@code POST /admin/audit/verify} - verify the hash chain now (it also runs nightly). */
    @PostMapping("/audit/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public AuditChainVerifier.Result verifyAudit() {
        return verifier.verify();
    }

    // ---- cycles (FR-AD-08)

    /** {@code POST /admin/cycles} body {@code {"month":"2026-12","reviewDeadline":null,"finalizeDeadline":null}} - Admin or Forecaster. */
    @PostMapping("/cycles")
    public Map<String, Object> createCycle(@RequestBody CycleRequest body, Authentication auth) {
        return Map.of("id", cycles.create(body.month(), body.reviewDeadline(), body.finalizeDeadline(), CurrentUser.from(auth)));
    }

    /** {@code PATCH /admin/cycles/{id}/deadlines} body {@code {"reviewDeadline":..., "finalizeDeadline":...}} */
    @PatchMapping("/cycles/{id}/deadlines")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deadlines(@PathVariable UUID id, @RequestBody CycleRequest body, Authentication auth) {
        cycles.setDeadlines(id, body.reviewDeadline(), body.finalizeDeadline(), CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    // ---- users (FR-AD-09)

    /** {@code GET /admin/users} - read-only users, roles, regions and last login from Keycloak (503 if not configured). */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> users() {
        Map<String, Object> m = new HashMap<>();
        m.put("users", users.users());
        m.put("consoleUrl", users.consoleUrl());
        return m;
    }
}
