package com.pifa.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/** Regions a user may edit, taken from the token's `regions` claim. */
public record RegionScope(Set<String> regions) {

    public static RegionScope from(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList("regions");
        if (claim == null) {
            return new RegionScope(Set.of());
        }
        return new RegionScope(claim.stream().map(r -> r.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()));
    }

    public boolean canEdit(String region) {
        return regions.contains(region.toUpperCase(Locale.ROOT));
    }
}
