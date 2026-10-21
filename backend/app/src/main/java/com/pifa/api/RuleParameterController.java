package com.pifa.api;

import com.pifa.common.ApiException;
import com.pifa.parameters.RuleParameterService;
import com.pifa.parameters.RuleParameterService.Values;
import com.pifa.parameters.RuleParameterService.Version;
import com.pifa.rules.RulesFacade;
import com.pifa.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rule parameters (PRD FR-AD-06): Admin edits, Planner and Admin read; plus the deployed Drools ruleset version. */
@RestController
@RequestMapping("/api/v1/rule-parameters")
public class RuleParameterController {

    /**
     * Body of a save.
     *
     * @param values        the complete new set of values
     * @param effectiveFrom when it takes effect (null = now)
     * @param reason        why (mandatory)
     */
    public record SaveRequest(Values values, OffsetDateTime effectiveFrom, String reason) {}

    private final RuleParameterService params;

    public RuleParameterController(RuleParameterService params) {
        this.params = params;
    }

    /** {@code GET /rule-parameters} - the version in force, the full history and the ruleset version (Planner, Admin). */
    @GetMapping
    public Map<String, Object> get(Authentication auth) {
        CurrentUser u = CurrentUser.from(auth);
        if (!(u.has("admin") || u.has("planner"))) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Rule parameters are visible to Planner and Admin");
        Version current = params.current();
        List<Version> history = params.history();
        return Map.of("current", current, "history", history, "rulesetVersion", RulesFacade.RULESET_VERSION);
    }

    /** {@code POST /rule-parameters} - save a new version (Admin only; versioned and audited). */
    @PostMapping
    public Version save(@RequestBody SaveRequest body, Authentication auth) {
        return params.save(body.values(), body.effectiveFrom(), body.reason(), CurrentUser.from(auth));
    }
}
