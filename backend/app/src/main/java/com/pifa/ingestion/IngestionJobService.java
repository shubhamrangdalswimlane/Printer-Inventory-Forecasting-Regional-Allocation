package com.pifa.ingestion;

import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetService;
import com.pifa.dataset.DatasetType;
import com.pifa.ingestion.IngestionService.Finding;
import com.pifa.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read access to ingestion jobs and their error reports (PRD FR-FC-01, FR-PL-01, FR-AD-04). */
@Service
public class IngestionJobService {

    /** One ingestion attempt as shown in the ingestion panel. */
    public record JobRow(UUID id, String channel, String sourceAccount, String datasetType, String originalFilename,
                         String status, int errorCount, int warningCount, UUID versionId, String createdBy,
                         OffsetDateTime startedAt) {}

    private final JdbcClient jdbc;

    public IngestionJobService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists recent jobs the caller may see: Forecaster sees forecast jobs, Planner inventory jobs,
     * Admin everything.
     *
     * @param user  the caller
     * @param limit maximum rows (newest first)
     * @return the visible jobs
     * @throws ApiException 403 if the caller is none of Forecaster, Planner, Admin
     */
    public List<JobRow> list(CurrentUser user, int limit) {
        List<DatasetType> visible = visibleTypes(user);
        List<String> names = visible.stream().map(Enum::name).toList();
        return jdbc.sql("""
                SELECT id, channel, source_account, dataset_type, original_filename, status, error_count, warning_count,
                       version_id, created_by, started_at
                FROM ingestion_job WHERE dataset_type IN (:types) ORDER BY started_at DESC LIMIT :n
                """).param("types", names).param("n", Math.min(Math.max(limit, 1), 500))
            .query((rs, n) -> new JobRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getObject(9, UUID.class),
                rs.getString(10), rs.getObject(11, OffsetDateTime.class))).list();
    }

    /**
     * @param jobId job id
     * @param user  the caller (must be allowed to see this job's dataset type)
     * @return all errors and warnings of the job
     * @throws ApiException 404 if unknown or not visible to the caller
     */
    public List<Finding> findings(UUID jobId, CurrentUser user) {
        String type = jdbc.sql("SELECT dataset_type FROM ingestion_job WHERE id = :id").param("id", jobId)
            .query(String.class).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Job not found"));
        if (!visibleTypes(user).contains(DatasetType.valueOf(type))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Job not found");
        }
        return jdbc.sql("SELECT row_no, column_name, code, severity, message FROM ingestion_error WHERE job_id = :id ORDER BY severity, row_no, id")
            .param("id", jobId)
            .query((rs, n) -> new Finding(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).list();
    }

    /**
     * Builds the downloadable error report as CSV (row, column, code, severity, message), with the
     * formula-injection guard applied to every text field.
     *
     * @param jobId job id
     * @param user  the caller
     * @return UTF-8 CSV bytes
     */
    public byte[] errorReportCsv(UUID jobId, CurrentUser user) {
        return toCsv(findings(jobId, user));
    }

    /**
     * Renders findings as CSV (row, column, code, severity, message) with the formula-injection guard.
     * Also used by the SFTP poller to write {@code <file>_errors.csv} for the source system.
     *
     * @param findings the findings of one job
     * @return UTF-8 CSV bytes including the header line
     */
    public static byte[] toCsv(List<Finding> findings) {
        List<String> lines = new ArrayList<>();
        lines.add("row,column,code,severity,message");
        for (Finding f : findings) {
            lines.add(f.rowNo() + "," + DatasetServiceCsv.field(f.column()) + "," + f.code() + "," + f.severity() + "," + DatasetServiceCsv.field(f.message()));
        }
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Which dataset types a caller may see jobs for. */
    private static List<DatasetType> visibleTypes(CurrentUser user) {
        if (user.has("admin")) return List.of(DatasetType.FORECAST_UPLOAD, DatasetType.INVENTORY_UPLOAD);
        List<DatasetType> t = new ArrayList<>();
        if (user.has("forecaster")) t.add(DatasetType.FORECAST_UPLOAD);
        if (user.has("planner")) t.add(DatasetType.INVENTORY_UPLOAD);
        if (t.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Ingestion jobs are visible to Forecaster, Planner and Admin only");
        return t;
    }

    /** Tiny CSV helper kept separate so it can be unit-tested without Spring. */
    static final class DatasetServiceCsv {
        private DatasetServiceCsv() {}

        /** @param s raw text, may be null @return CSV-escaped, formula-safe field */
        static String field(String s) {
            String t = ExcelFiles.safe(s);
            return (t.contains(",") || t.contains("\"") || t.contains("\n")) ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
        }
    }
}
