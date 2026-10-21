package com.pifa.api;

import com.pifa.common.ApiException;
import com.pifa.dataset.DatasetRepository;
import com.pifa.dataset.DatasetRepository.VersionRow;
import com.pifa.dataset.DatasetService;
import com.pifa.dataset.DatasetType;
import com.pifa.ingestion.IngestionService;
import com.pifa.ingestion.IngestionService.IngestRequest;
import com.pifa.ingestion.IngestionService.IngestResult;
import com.pifa.security.CurrentUser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for dataset versions and file upload (architecture.md section 10).
 * Controllers only translate HTTP to service calls; the rules live in the services.
 */
@RestController
@RequestMapping("/api/v1")
public class DatasetController {

    /** Body of activate / delete / undelete requests. @param reason mandatory explanation */
    public record ReasonRequest(String reason) {}

    private final DatasetService datasets;
    private final DatasetRepository repo;
    private final IngestionService ingestion;

    public DatasetController(DatasetService datasets, DatasetRepository repo, IngestionService ingestion) {
        this.datasets = datasets;
        this.repo = repo;
        this.ingestion = ingestion;
    }

    /**
     * {@code GET /datasets} - lists versions.
     *
     * @param type           optional filter: FORECAST_UPLOAD or INVENTORY_UPLOAD
     * @param cycleId        optional filter by cycle
     * @param includeDeleted include deleted/purged versions (Admin only; ignored otherwise)
     * @param auth           the caller
     * @return the versions, newest first
     */
    @GetMapping("/datasets")
    public List<VersionRow> list(@RequestParam(required = false) DatasetType type, @RequestParam(required = false) UUID cycleId,
                                 @RequestParam(defaultValue = "false") boolean includeDeleted, Authentication auth) {
        return datasets.list(type, cycleId, includeDeleted, CurrentUser.from(auth));
    }

    /** {@code GET /cycles} - lists planning cycles, newest month first. */
    @GetMapping("/cycles")
    public List<DatasetRepository.Cycle> cycles() {
        return repo.listCycles();
    }

    /** {@code GET /datasets/{id}} - one version's metadata (404 if missing). */
    @GetMapping("/datasets/{id}")
    public VersionRow get(@PathVariable UUID id, Authentication auth) {
        return datasets.get(id, CurrentUser.from(auth));
    }

    /** {@code GET /datasets/{id}/lines} - the version's data rows (forecast: per region; inventory: per product). */
    @GetMapping("/datasets/{id}/lines")
    public List<Map<String, Object>> lines(@PathVariable UUID id, Authentication auth) {
        return datasets.lines(id, CurrentUser.from(auth));
    }

    /**
     * {@code GET /datasets/compare?base=&target=} - differences between two versions of one type.
     * Declared before {@code /datasets/{id}} routes conflict is avoided because "compare" is a literal path segment.
     */
    @GetMapping("/datasets/compare")
    public DatasetService.Comparison compare(@RequestParam UUID base, @RequestParam UUID target, Authentication auth) {
        return datasets.compare(base, target, CurrentUser.from(auth));
    }

    /**
     * {@code GET /datasets/{id}/download?format=original|xlsx|csv} - file download.
     *
     * @param format {@code original} (exact bytes received), {@code xlsx} or {@code csv} (normalised export)
     */
    @GetMapping("/datasets/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam(defaultValue = "xlsx") String format, Authentication auth) {
        DatasetService.Download d = datasets.download(id, format, CurrentUser.from(auth));
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.filename()).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.parseMediaType(d.contentType()))
            .body(d.bytes());
    }

    /** {@code POST /datasets/{id}/activate} body {@code {"reason": "..."}} - make this the active version. */
    @PostMapping("/datasets/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID id, @RequestBody ReasonRequest body, Authentication auth) {
        datasets.activate(id, body.reason(), CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    /** {@code DELETE /datasets/{id}} body {@code {"reason": "..."}} - soft delete. */
    @DeleteMapping("/datasets/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestBody ReasonRequest body, Authentication auth) {
        datasets.softDelete(id, body.reason(), CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /datasets/{id}/undelete} body {@code {"reason": "..."}} - Admin only. */
    @PostMapping("/datasets/{id}/undelete")
    public ResponseEntity<Void> undelete(@PathVariable UUID id, @RequestBody ReasonRequest body, Authentication auth) {
        datasets.undelete(id, body.reason(), CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    /** {@code DELETE /datasets/{id}/purge} - Admin only; permanently removes data of a soft-deleted version. */
    @DeleteMapping("/datasets/{id}/purge")
    public ResponseEntity<Void> purge(@PathVariable UUID id, Authentication auth) {
        datasets.purge(id, CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /datasets/{type}/uploads} (multipart field {@code file}) - ingest a file through the same
     * pipeline as SFTP. Forecast files need role forecaster; inventory files need role planner.
     *
     * @param type {@code forecast} or {@code inventory}
     * @param file the uploaded .xlsx
     * @return 201 with the result when accepted; 422 with the same body listing the findings when rejected
     * @throws IOException if the upload cannot be read
     */
    @PostMapping("/datasets/{type}/uploads")
    public ResponseEntity<IngestResult> upload(@PathVariable String type, @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        DatasetType t = DatasetType.fromPath(type);
        CurrentUser user = CurrentUser.from(auth);
        String needed = t == DatasetType.FORECAST_UPLOAD ? "forecaster" : "planner";
        if (!user.has(needed)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Uploading " + type + " files requires role " + needed);
        IngestResult r = ingestion.ingest(new IngestRequest(file.getBytes(), file.getOriginalFilename(), t, "UI", user.username(), user.username()));
        return ResponseEntity.status("VALID".equals(r.status()) ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }
}
