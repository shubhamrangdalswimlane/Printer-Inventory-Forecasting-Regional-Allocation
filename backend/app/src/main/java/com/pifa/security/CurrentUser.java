package com.pifa.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

/**
 * Who is making the current request: username plus lower-case roles.
 *
 * <p>Services receive this instead of Spring types, which keeps authorization logic testable
 * without a web layer. For system actions (SFTP poller, synthetic generator) use {@link #system}.
 *
 * @param username the Keycloak {@code preferred_username}, or an SFTP/system account name
 * @param roles    lower-case roles such as {@code forecaster}, {@code planner}, {@code admin}
 */
public record CurrentUser(String username, Set<String> roles) {

    /**
     * Builds the user from the Spring authentication of the request.
     *
     * @param auth the authentication (its name is the username; {@code ROLE_X} authorities become role {@code x})
     * @return the current user
     */
    public static CurrentUser from(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5).toLowerCase())
            .collect(Collectors.toUnmodifiableSet());
        return new CurrentUser(auth.getName(), roles);
    }

    /**
     * A non-human actor (poller, generator) with no roles; services that allow it check the actor name.
     *
     * @param name actor name recorded in audit entries, e.g. {@code sftp-forecast}
     * @return a role-less user with that name
     */
    public static CurrentUser system(String name) {
        return new CurrentUser(name, Set.of());
    }

    /**
     * @param role lower-case role name
     * @return true if the user holds the role
     */
    public boolean has(String role) { return roles.contains(role); }
}
