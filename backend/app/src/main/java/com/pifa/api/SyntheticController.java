package com.pifa.api;

import com.pifa.ingestion.IngestionService.IngestResult;
import com.pifa.security.CurrentUser;
import com.pifa.synthetic.SyntheticDataService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only controls for synthetic data (PRD FR-AD-10). All calls fail with 403 when synthetic data is disabled. */
@RestController
@RequestMapping("/api/v1/admin/synthetic")
@PreAuthorize("hasRole('ADMIN')")
public class SyntheticController {

    private final SyntheticDataService synthetic;

    public SyntheticController(SyntheticDataService synthetic) {
        this.synthetic = synthetic;
    }

    /**
     * {@code POST /admin/synthetic/generate?month=2026-11&seed=1} - generates a forecast and an inventory
     * file and ingests them like an SFTP drop.
     *
     * @param month cycle month (default: next month)
     * @param seed  random seed (same seed, same data)
     * @return the two ingestion results (forecast first)
     */
    @PostMapping("/generate")
    public List<IngestResult> generate(@RequestParam(required = false) String month, @RequestParam(defaultValue = "1") long seed, Authentication auth) {
        return synthetic.generate(month, seed, CurrentUser.from(auth));
    }

    /** {@code POST /admin/synthetic/negative-pack} - runs the bad-file pack through the pipeline; returns each result. */
    @PostMapping("/negative-pack")
    public Map<String, IngestResult> negativePack(@RequestParam(required = false) String month, @RequestParam(defaultValue = "1") long seed, Authentication auth) {
        String m = month != null ? month : java.time.YearMonth.now().plusMonths(1).toString();
        return synthetic.runNegativePack(m, seed, CurrentUser.from(auth));
    }

    /** {@code POST /admin/synthetic/purge} - removes all synthetic data; returns {@code {"versionsRemoved": n}}. */
    @PostMapping("/purge")
    public Map<String, Integer> purge(Authentication auth) {
        return Map.of("versionsRemoved", synthetic.purgeAll(CurrentUser.from(auth)));
    }
}
