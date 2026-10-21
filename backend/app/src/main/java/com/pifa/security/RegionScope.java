package com.pifa.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The set of regions a user is allowed to edit, read from the token's {@code regions} claim.
 *
 * <p>Roles say <em>what kind</em> of user someone is; the region scope says <em>which data</em>
 * they may change. A Regional Manager for North has {@code regions = ["NORTH"]} and must get a
 * 403 when writing to EAST data. Later phases call {@link #canEdit(String)} before every write.
 *
 * <p>This is a Java {@code record}: an immutable holder whose constructor, getter
 * ({@link #regions()}), equals and toString are generated automatically.
 *
 * @param regions upper-case region codes such as {@code NORTH}; never {@code null}
 */
public record RegionScope(Set<String> regions) {

    /**
     * Builds the scope from a verified token.
     *
     * @param jwt the access token; its {@code regions} claim is a list of strings set in Keycloak
     * @return the user's regions, normalised to upper case; an empty scope if the claim is absent
     *         (users such as Planner or Admin have no region restriction claim)
     */
    public static RegionScope from(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList("regions");
        if (claim == null) {
            return new RegionScope(Set.of());
        }
        return new RegionScope(claim.stream().map(r -> r.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * Checks whether the user may edit data of a given region.
     *
     * @param region region code in any letter case, for example {@code "north"} or {@code "NORTH"}
     * @return {@code true} if the region is in the user's scope, otherwise {@code false}
     */
    public boolean canEdit(String region) {
        return regions.contains(region.toUpperCase(Locale.ROOT));
    }
}
