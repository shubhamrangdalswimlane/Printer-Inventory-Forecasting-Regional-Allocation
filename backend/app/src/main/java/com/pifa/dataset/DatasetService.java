package com.pifa.dataset;

import com.pifa.audit.AuditService;
import com.pifa.common.ApiException;
import com.pifa.config.PifaProperties;
import com.pifa.dataset.DatasetRepository.VersionRow;
import com.pifa.ingestion.ExcelFiles;
import com.pifa.master.MasterDataService;
import com.pifa.security.CurrentUser;
import com.pifa.storage.FileStore;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules for dataset versions: who may list, view, download, activate, delete, undelete
 * and purge them, and how two versions are compared (PRD 8.5, 9.2). Every state change writes an
 * audit entry in the same transaction.
 */
@Service
public class DatasetService {

    /** A file ready to send to the client. */
    public record Download(byte[] bytes, String filename, String contentType) {}

    /**
     * Difference between two versions of the same type.
     *
     * @param added   keys present only in the target version
     * @param removed keys present only in the base version
     * @param changed keys present in both with different quantities
     */
    public record Comparison(List<String> added, List<String> removed, List<Change> changed) {}

    /** One changed quantity: {@code key} is the product (inventory) or {@code product / REGION} (forecast). */
    public record Change(String key, int base, int target) {}

    /** Cycle states in which datasets may still be re-activated (PRD 8.5). */
    private static final Set<String> FORECAST_EDITABLE = Set.of("FORECAST_DRAFT");
    private static final Set<String> INVENTORY_EDITABLE = Set.of("FORECAST_DRAFT", "REGIONAL_REVIEW", "PLANNING");

    private final DatasetRepository repo;
    private final MasterDataService master;
    private final JdbcClient jdbc;
    private final FileStore files;
    private final AuditService audit;
    private final PifaProperties props;

    public DatasetService(DatasetRepository repo, MasterDataService master, JdbcClient jdbc, FileStore files,
                          AuditService audit, PifaProperties props) {
        this.repo = repo;
        this.master = master;
        this.jdbc = jdbc;
        this.files = files;
        this.audit = audit;
        this.props = props;
    }

    /**
     * Lists versions.
     *
     * @param type           restrict to one type, or null
     * @param cycleId        restrict to one cycle, or null
     * @param includeDeleted request deleted/purged versions; honoured only for Admin
     * @param user           the caller
     * @return matching versions, newest first
     */
    public List<VersionRow> list(DatasetType type, UUID cycleId, boolean includeDeleted, CurrentUser user) {
        return repo.list(type, cycleId, includeDeleted && user.has("admin"));
    }

    /**
     * @param id   version id
     * @param user the caller (only Admin may see deleted versions)
     * @return the version
     * @throws ApiException 404 if missing, or deleted and the caller is not Admin
     */
    public VersionRow get(UUID id, CurrentUser user) {
        VersionRow v = repo.findVersion(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Version not found"));
        if (("DELETED".equals(v.status()) || "PURGED".equals(v.status())) && !user.has("admin")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Version not found");
        }
        return v;
    }

    /**
     * Returns the data rows of a version in a UI-friendly shape.
     * Forecast: {@code product, regions{CODE:qty}, total, comment}. Inventory: {@code product, availableQty, asOfDate}.
     *
     * @param id   version id
     * @param user the caller
     * @return one map per product, ordered by product name
     */
    public List<Map<String, Object>> lines(UUID id, CurrentUser user) {
        VersionRow v = get(id, user);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v.type().isForecastShaped()) {
            for (var e : forecastPivot(id).entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("product", e.getKey());
                row.put("regions", e.getValue().qty);
                row.put("total", e.getValue().qty.values().stream().mapToInt(Integer::intValue).sum());
                row.put("comment", e.getValue().comment);
                out.add(row);
            }
        } else {
            jdbc.sql("SELECT p.product_name, i.available_qty, i.as_of_date FROM inventory_line i JOIN product p ON p.id = i.product_id WHERE i.version_id = :id ORDER BY p.product_name")
                .param("id", id).query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("product", rs.getString(1));
                    row.put("availableQty", rs.getInt(2));
                    row.put("asOfDate", rs.getObject(3, LocalDate.class).toString());
                    return out.add(row);
                }).list();
        }
        return out;
    }

    /**
     * Makes a version the active one (PRD 9.2 "Activate / restore").
     *
     * <p>Allowed for: Forecaster on forecast versions while the cycle is FORECAST_DRAFT; Planner on
     * inventory versions while the cycle is up to PLANNING; Admin on either under the same state limits.
     *
     * @param id     version to activate (must be VALID or SUPERSEDED)
     * @param reason mandatory explanation, stored in the audit log
     * @param user   the caller
     * @throws ApiException 400 no reason, 403 wrong role, 409 wrong status or cycle state
     */
    @Transactional
    public void activate(UUID id, String reason, CurrentUser user) {
        requireReason(reason);
        VersionRow v = get(id, user);
        requireUploadType(v);
        boolean forecast = v.type() == DatasetType.FORECAST_UPLOAD;
        requireAny(user, forecast ? "forecaster" : "planner", "admin");
        if (!Set.of("VALID", "SUPERSEDED").contains(v.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_NOT_ACTIVATABLE", "Only VALID or SUPERSEDED versions can be activated (status is " + v.status() + ")");
        }
        String state = repo.findCycle(v.cycleId()).orElseThrow().state();
        if (!(forecast ? FORECAST_EDITABLE : INVENTORY_EDITABLE).contains(state)) {
            throw new ApiException(HttpStatus.CONFLICT, "CYCLE_LOCKED", "Cycle is in state " + state + "; this dataset can no longer be changed");
        }
        repo.activate(id);
        audit.record(user.username(), "ACTIVATE", "dataset_version", id.toString(), Map.of("label", v.label(), "reason", reason));
    }

    /**
     * Soft-deletes a version (PRD 9.2). The row stays for audit; it is hidden from normal views.
     *
     * <p>Safeguards: a reason is required; the ACTIVE version cannot be deleted; a version referenced
     * by a later snapshot cannot be deleted; workflow snapshots are never deletable; a FINAL cycle is locked.
     * Admin may delete any upload; Forecaster only their own forecast uploads; Planner any inventory upload.
     *
     * @param id     version to delete
     * @param reason mandatory explanation
     * @param user   the caller
     * @throws ApiException 400 no reason, 403 not allowed, 409 safeguard violated
     */
    @Transactional
    public void softDelete(UUID id, String reason, CurrentUser user) {
        requireReason(reason);
        VersionRow v = get(id, user);
        requireUploadType(v);
        boolean forecast = v.type() == DatasetType.FORECAST_UPLOAD;
        boolean allowed = user.has("admin")
            || (forecast && user.has("forecaster") && user.username().equals(v.createdBy()))
            || (!forecast && user.has("planner"));
        if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may not delete this version");
        if ("DELETED".equals(v.status()) || "PURGED".equals(v.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_DELETED", "Version is already deleted");
        }
        if (v.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_ACTIVE", "The active version cannot be deleted; activate another version first");
        }
        if (repo.isReferenced(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_REFERENCED", "Version is used by a published forecast or an allocation and cannot be deleted");
        }
        if ("FINAL".equals(repo.findCycle(v.cycleId()).orElseThrow().state())) {
            throw new ApiException(HttpStatus.CONFLICT, "CYCLE_LOCKED", "Cycle is FINAL");
        }
        repo.softDelete(id, user.username(), reason);
        audit.record(user.username(), "DELETE", "dataset_version", id.toString(), Map.of("label", v.label(), "reason", reason));
    }

    /**
     * Restores a soft-deleted version to VALID (Admin only).
     *
     * @param id     version id
     * @param reason mandatory explanation
     * @param user   the caller (must be Admin)
     * @throws ApiException 403 not Admin, 409 if the version is not DELETED
     */
    @Transactional
    public void undelete(UUID id, String reason, CurrentUser user) {
        requireReason(reason);
        requireAny(user, "admin");
        VersionRow v = get(id, user);
        if (!"DELETED".equals(v.status())) throw new ApiException(HttpStatus.CONFLICT, "NOT_DELETED", "Only DELETED versions can be restored");
        repo.undelete(id);
        audit.record(user.username(), "UNDELETE", "dataset_version", id.toString(), Map.of("label", v.label(), "reason", reason));
    }

    /**
     * Permanently removes the data and file of a soft-deleted version, keeping a PURGED tombstone row
     * and an audit entry (Admin only). A non-synthetic version must have been deleted for at least the
     * retention period ({@code pifa.retention.purge-days}).
     *
     * @param id   version id
     * @param user the caller (must be Admin)
     * @throws ApiException 403 not Admin, 409 not deleted or retention period not over
     */
    @Transactional
    public void purge(UUID id, CurrentUser user) {
        requireAny(user, "admin");
        VersionRow v = get(id, user);
        if (!"DELETED".equals(v.status())) throw new ApiException(HttpStatus.CONFLICT, "NOT_DELETED", "Only soft-deleted versions can be purged");
        boolean synthetic = "SYNTHETIC".equals(v.source());
        if (!synthetic && v.deletedAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusDays(props.retention().purgeDays()))) {
            throw new ApiException(HttpStatus.CONFLICT, "RETENTION", "Version must stay deleted for " + props.retention().purgeDays() + " days before it can be purged");
        }
        if (v.objectKey() != null) files.delete(v.objectKey());
        repo.purge(id);
        audit.record(user.username(), "PURGE", "dataset_version", id.toString(), Map.of("label", v.label(), "sha256", String.valueOf(v.sha256())));
    }

    /**
     * Compares two versions of the same type.
     *
     * @param baseId   the older/reference version
     * @param targetId the version to compare against it
     * @param user     the caller
     * @return added, removed and changed keys
     * @throws ApiException 400 if the two versions are of different types
     */
    public Comparison compare(UUID baseId, UUID targetId, CurrentUser user) {
        VersionRow base = get(baseId, user);
        VersionRow target = get(targetId, user);
        if (base.type() != target.type()) throw new ApiException(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH", "Only versions of the same type can be compared");
        Map<String, Integer> a = flat(base), b = flat(target);
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        List<Change> changed = new ArrayList<>();
        b.forEach((k, v) -> { if (!a.containsKey(k)) added.add(k); else if (!a.get(k).equals(v)) changed.add(new Change(k, a.get(k), v)); });
        a.keySet().stream().filter(k -> !b.containsKey(k)).forEach(removed::add);
        return new Comparison(added, removed, changed);
    }

    /**
     * Produces a file for a version.
     *
     * <p>{@code original}: the exact bytes received (Forecaster, Planner, Admin only; audited).
     * {@code xlsx}/{@code csv}: normalised data in template format with a formula-injection guard;
     * xlsx files carry a Metadata sheet and can be re-uploaded.
     *
     * @param id     version id
     * @param format {@code original}, {@code xlsx} or {@code csv}
     * @param user   the caller
     * @return bytes, file name and content type
     * @throws ApiException 403 for original without a permitted role; 409 if the file was purged
     */
    @Transactional
    public Download download(UUID id, String format, CurrentUser user) {
        VersionRow v = get(id, user);
        String base = v.label();
        switch (format.toLowerCase()) {
            case "original" -> {
                if (!(user.has("forecaster") || user.has("planner") || user.has("admin"))) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may not download original files");
                }
                if (v.objectKey() == null) throw new ApiException(HttpStatus.CONFLICT, "PURGED", "The original file has been purged");
                audit.record(user.username(), "DOWNLOAD_ORIGINAL", "dataset_version", id.toString(), Map.of("label", v.label()));
                String name = v.originalFilename() != null ? v.originalFilename() : base + ".xlsx";
                return new Download(files.get(v.objectKey()), name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            case "xlsx" -> {
                return new Download(exportXlsx(v, user), base + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            case "csv" -> {
                return new Download(exportCsv(v).getBytes(StandardCharsets.UTF_8), base + ".csv", "text/csv; charset=utf-8");
            }
            default -> throw new IllegalArgumentException("format must be original, xlsx or csv");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Product -> region quantities (+ comment) of a forecast-shaped version, ordered by product name. */
    private static final class Pivot { final Map<String, Integer> qty = new LinkedHashMap<>(); String comment; }

    private Map<String, Pivot> forecastPivot(UUID versionId) {
        Map<String, Pivot> map = new TreeMap<>();
        jdbc.sql("""
                SELECT p.product_name, f.region_code, f.qty, f.comment FROM forecast_line f
                JOIN product p ON p.id = f.product_id JOIN region r ON r.code = f.region_code
                WHERE f.version_id = :id ORDER BY p.product_name, r.display_order
                """).param("id", versionId).query((rs, n) -> {
                    Pivot pv = map.computeIfAbsent(rs.getString(1), k -> new Pivot());
                    pv.qty.put(rs.getString(2), rs.getInt(3));
                    if (rs.getString(4) != null) pv.comment = rs.getString(4);
                    return null;
                }).list();
        return map;
    }

    /** Flattens a version into comparable keys: {@code product / REGION} (forecast) or {@code product} (inventory). */
    private Map<String, Integer> flat(VersionRow v) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (v.type().isForecastShaped()) {
            forecastPivot(v.id()).forEach((prod, pv) -> pv.qty.forEach((reg, q) -> m.put(prod + " / " + reg, q)));
        } else {
            jdbc.sql("SELECT p.product_name, i.available_qty FROM inventory_line i JOIN product p ON p.id = i.product_id WHERE i.version_id = :id")
                .param("id", v.id()).query((rs, n) -> m.put(rs.getString(1), rs.getInt(2))).list();
        }
        return m;
    }

    private byte[] exportXlsx(VersionRow v, CurrentUser user) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Version", v.label());
        meta.put("Cycle", v.cycleMonth());
        meta.put("Exported by", user.username());
        meta.put("Exported at (UTC)", OffsetDateTime.now(ZoneOffset.UTC).toString());
        if (v.type().isForecastShaped()) {
            List<String> regions = master.activeRegionCodes();
            List<ExcelFiles.ForecastRow> rows = new ArrayList<>();
            forecastPivot(v.id()).forEach((prod, pv) -> rows.add(new ExcelFiles.ForecastRow(prod, pv.qty, pv.comment)));
            return ExcelFiles.forecast(regions, rows, v.cycleMonth(), meta);
        }
        List<ExcelFiles.InventoryRow> rows = new ArrayList<>();
        jdbc.sql("SELECT p.product_name, i.available_qty FROM inventory_line i JOIN product p ON p.id = i.product_id WHERE i.version_id = :id ORDER BY p.product_name")
            .param("id", v.id()).query((rs, n) -> rows.add(new ExcelFiles.InventoryRow(rs.getString(1), rs.getInt(2)))).list();
        LocalDate asOf = jdbc.sql("SELECT MAX(as_of_date) FROM inventory_line WHERE version_id = :id").param("id", v.id()).query(LocalDate.class).optional().orElse(LocalDate.now());
        return ExcelFiles.inventory(rows, asOf, v.cycleMonth(), meta);
    }

    private String exportCsv(VersionRow v) {
        StringBuilder sb = new StringBuilder();
        if (v.type().isForecastShaped()) {
            List<String> regions = master.activeRegionCodes();
            sb.append("Product_name,Forecast_Month");
            regions.forEach(r -> sb.append(',').append(ExcelFiles.capitalise(r)));
            sb.append('\n');
            forecastPivot(v.id()).forEach((prod, pv) -> {
                sb.append(csv(prod)).append(',').append(v.cycleMonth());
                regions.forEach(r -> sb.append(',').append(pv.qty.getOrDefault(r, 0)));
                sb.append('\n');
            });
        } else {
            sb.append("Product_name,Available_Qty,As_Of_Date,Planning_Month\n");
            jdbc.sql("SELECT p.product_name, i.available_qty, i.as_of_date FROM inventory_line i JOIN product p ON p.id = i.product_id WHERE i.version_id = :id ORDER BY p.product_name")
                .param("id", v.id()).query((rs, n) -> sb.append(csv(rs.getString(1))).append(',').append(rs.getInt(2)).append(',')
                    .append(rs.getObject(3, LocalDate.class)).append(',').append(v.cycleMonth()).append('\n')).list();
        }
        return sb.toString();
    }

    /** Escapes one CSV field: formula guard first, then quoting if it holds a comma, quote or line break. */
    static String csv(String s) {
        String t = ExcelFiles.safe(s);
        return (t.contains(",") || t.contains("\"") || t.contains("\n")) ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason is required");
    }

    private static void requireUploadType(VersionRow v) {
        if (!v.type().isUpload()) throw new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_IMMUTABLE", "Workflow snapshots cannot be changed or deleted");
    }

    /**
     * @param user  the caller
     * @param roles roles of which at least one is required
     * @throws ApiException 403 if the caller holds none of them
     */
    private static void requireAny(CurrentUser user, String... roles) {
        for (String r : roles) if (user.has(r)) return;
        throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Requires role " + String.join(" or ", roles));
    }
}
