package com.pifa.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is making the current request: username, lower-case roles and the regions they may edit.
 *
 * <p>Services receive this instead of Spring types, which keeps authorization logic testable
 * without a web layer. For system actions (SFTP poller, synthetic generator) use {@link #system}.
 *
 * @param username the Keycloak {@code preferred_username}, or an SFTP/system account name
 * @param roles    lower-case roles such as {@code forecaster}, {@code planner}, {@code admin}
 * @param regions  upper-case region codes from the token's {@code regions} claim (empty for non-regional users)
 */
public record CurrentUser(String username, Set<String> roles, Set<String> regions) {

    /**
     * Convenience constructor for users without region scope.
     *
     * @param username the user name
     * @param roles    lower-case roles
     */
    public CurrentUser(String username, Set<String> roles) {
        this(username, roles, Set.of());
    }

    /**
     * Builds the user from the Spring authentication of the request. Roles come from the
     * {@code ROLE_*} authorities; regions come from the {@code regions} claim when the
     * authentication is a JWT.
     *
     * @param auth the authentication (its name is the username)
     * @return the current user
     */
    public static CurrentUser from(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5).toLowerCase())
            .collect(Collectors.toUnmodifiableSet());
        Set<String> regions = Set.of();
        if (auth instanceof JwtAuthenticationToken t) {
            List<String> claim = t.getToken().getClaimAsStringList("regions");
            if (claim != null) regions = claim.stream().map(r -> r.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }
        return new CurrentUser(auth.getName(), roles, regions);
    }

    /**
     * A non-human actor (poller, generator) with no roles; services that allow it check the actor name.
     *
     * @param name actor name recorded in audit entries, e.g. {@code sftp-forecast}
     * @return a role-less user with that name
     */
    public static CurrentUser system(String name) {
        return new CurrentUser(name, Set.of(), Set.of());
    }

    /**
     * @param role lower-case role name
     * @return true if the user holds the role
     */
    public boolean has(String role) { return roles.contains(role); }

    /**
     * @param region region code in any letter case
     * @return true if the user may edit that region
     */
    public boolean inRegion(String region) { return regions.contains(region.toUpperCase(Locale.ROOT)); }
}
