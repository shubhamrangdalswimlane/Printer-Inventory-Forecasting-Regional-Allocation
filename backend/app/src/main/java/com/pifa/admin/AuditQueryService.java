package com.pifa.admin;

import com.pifa.ingestion.ExcelFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Search and export of the audit log for Admin (PRD FR-AD-07). Read-only: the log itself is append-only. */
@Service
public class AuditQueryService {

    /**
     * Search filters; every field is optional.
     *
     * @param actor      exact user / account name
     * @param action     exact action, e.g. TRANSFER_ACCEPTED
     * @param entityType exact entity type, e.g. planning_cycle
     * @param entityId   exact entity id (e.g. a cycle or version id)
     * @param from       earliest time (inclusive)
     * @param to         latest time (exclusive)
     * @param text       free text searched in the detail JSON
     */
    public record Filter(String actor, String action, String entityType, String entityId, OffsetDateTime from, OffsetDateTime to, String text) {}

    /** One audit row. */
    public record Entry(long id, OffsetDateTime at, String actor, String action, String entityType, String entityId, String detail) {}

    private final JdbcClient jdbc;

    public AuditQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String WHERE = """
         WHERE (CAST(:actor AS TEXT) IS NULL OR actor = :actor)
           AND (CAST(:action AS TEXT) IS NULL OR action = :action)
           AND (CAST(:etype AS TEXT) IS NULL OR entity_type = :etype)
           AND (CAST(:eid AS TEXT) IS NULL OR entity_id = :eid)
           AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR at >= :from)
           AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR at < :to)
           AND (CAST(:text AS TEXT) IS NULL OR detail::text ILIKE :text)
        """;

    /**
     * @param f     filters
     * @param page  zero-based page
     * @param size  rows per page (1..500)
     * @return {@code total} and newest-first {@code rows}
     */
    public java.util.Map<String, Object> search(Filter f, int page, int size) {
        int s = Math.min(Math.max(size, 1), 500);
        long total = bind(jdbc.sql("SELECT COUNT(*) FROM audit_log" + WHERE), f).query(Long.class).single();
        List<Entry> rows = bind(jdbc.sql("SELECT id, at, actor, action, entity_type, entity_id, detail::text FROM audit_log" + WHERE + " ORDER BY id DESC LIMIT :s OFFSET :o"), f)
            .param("s", s).param("o", Math.max(page, 0) * s).query(AuditQueryService::map).list();
        return java.util.Map.of("total", total, "rows", rows);
    }

    /**
     * Exports up to 100,000 matching rows (newest first).
     *
     * @param f      filters
     * @param format {@code csv} or {@code xlsx}
     * @return file bytes; text fields carry the formula-injection guard
     */
    public byte[] export(Filter f, String format) {
        List<Entry> rows = bind(jdbc.sql("SELECT id, at, actor, action, entity_type, entity_id, detail::text FROM audit_log" + WHERE + " ORDER BY id DESC LIMIT 100000"), f)
            .query(AuditQueryService::map).list();
        String[] head = {"id", "at", "actor", "action", "entity_type", "entity_id", "detail"};
        if ("xlsx".equalsIgnoreCase(format)) {
            try (SXSSFWorkbook wb = new SXSSFWorkbook(500); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sh = wb.createSheet("Audit");
                Row h = sh.createRow(0);
                for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
                int r = 1;
                for (Entry e : rows) {
                    Row row = sh.createRow(r++);
                    row.createCell(0).setCellValue(e.id());
                    row.createCell(1).setCellValue(e.at().toString());
                    row.createCell(2).setCellValue(ExcelFiles.safe(e.actor()));
                    row.createCell(3).setCellValue(ExcelFiles.safe(e.action()));
                    row.createCell(4).setCellValue(ExcelFiles.safe(e.entityType()));
                    row.createCell(5).setCellValue(ExcelFiles.safe(e.entityId()));
                    row.createCell(6).setCellValue(ExcelFiles.safe(e.detail()));
                }
                wb.write(out);
                wb.dispose();
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", head));
        for (Entry e : rows) {
            lines.add(e.id() + "," + e.at() + "," + csv(e.actor()) + "," + csv(e.action()) + "," + csv(e.entityType()) + "," + csv(e.entityId()) + "," + csv(e.detail()));
        }
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bind(org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec, Filter f) {
        return spec.param("actor", blank(f.actor())).param("action", blank(f.action())).param("etype", blank(f.entityType())).param("eid", blank(f.entityId()))
            .param("from", f.from()).param("to", f.to()).param("text", blank(f.text()) == null ? null : "%" + f.text().trim() + "%");
    }

    private static Entry map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Entry(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7));
    }

    private static String csv(String s) {
        String t = ExcelFiles.safe(s);
        return (t.contains(",") || t.contains("\"") || t.contains("\n")) ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
