package com.pifa.api;

import com.pifa.security.RegionScope;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session endpoint: tells the frontend who the logged-in user is.
 *
 * <p>The single-page app calls {@code GET /api/v1/me} right after login to learn the user's
 * roles, regions and which dashboard to open first. The data comes only from the verified token,
 * so no database is needed. The backend remains the authority; the UI uses this only to decide
 * what to show.
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    /**
     * JSON body returned by {@code /me}.
     *
     * @param username the Keycloak {@code preferred_username}, e.g. {@code anita.north}
     * @param roles    lower-case role names, sorted, e.g. {@code ["regional_manager"]}
     * @param regions  upper-case region codes the user may edit; empty for non-regional users
     * @param landing  front-end route to open first, e.g. {@code /regional}
     */
    public record MeResponse(String username, List<String> roles, Set<String> regions, String landing) {}

    /**
     * Returns the current user's profile.
     *
     * <p>Requires a valid bearer token (enforced by {@code SecurityConfig}); anonymous callers get
     * HTTP 401 before this method runs.
     *
     * @param jwt  the verified access token, injected by Spring Security
     * @param auth the authentication object holding the {@code ROLE_*} authorities built from the token
     * @return the user's username, roles, regions and landing route (HTTP 200, JSON)
     */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt, org.springframework.security.core.Authentication auth) {
        // Strip the "ROLE_" prefix and lower-case so the API speaks the same role names as Keycloak.
        List<String> roles = auth.getAuthorities().stream()
            .map(a -> a.getAuthority().substring("ROLE_".length()).toLowerCase())
            .sorted()
            .toList();
        return new MeResponse(
            jwt.getClaimAsString("preferred_username"),
            roles,
            RegionScope.from(jwt).regions(),
            landing(roles));
    }

    /**
     * Picks the dashboard to open first. A user may hold several roles, so a fixed priority is used:
     * admin, planner, forecaster, regional_manager.
     *
     * @param roles the user's lower-case role names
     * @return the front-end route of the chosen dashboard, or {@code "/"} if the user has none of the four roles
     */
    static String landing(List<String> roles) {
        if (roles.contains("admin")) return "/admin";
        if (roles.contains("planner")) return "/planner";
        if (roles.contains("forecaster")) return "/forecaster";
        if (roles.contains("regional_manager")) return "/regional";
        return "/";
    }
}
