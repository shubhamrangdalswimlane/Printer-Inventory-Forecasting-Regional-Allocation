package com.pifa.api;

import com.pifa.security.RegionScope;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    public record MeResponse(String username, List<String> roles, Set<String> regions, String landing) {}

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt, org.springframework.security.core.Authentication auth) {
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

    /** Priority order when a user holds several roles. */
    static String landing(List<String> roles) {
        if (roles.contains("admin")) return "/admin";
        if (roles.contains("planner")) return "/planner";
        if (roles.contains("forecaster")) return "/forecaster";
        if (roles.contains("regional_manager")) return "/regional";
        return "/";
    }
}
