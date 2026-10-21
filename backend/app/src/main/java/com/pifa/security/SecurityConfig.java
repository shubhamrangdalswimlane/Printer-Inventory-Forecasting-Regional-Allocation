package com.pifa.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures how the API authenticates and authorizes requests.
 *
 * <p>The API is an OAuth2 <em>resource server</em>: the browser logs in to Keycloak, receives a
 * signed JWT (access token) and sends it as {@code Authorization: Bearer <token>}. This class
 * makes Spring verify that token (signature, issuer, expiry; keys come from Keycloak's JWKS
 * endpoint via {@code issuer-uri} in application.yml) and turns its claims into Spring roles.
 *
 * <p>{@code @EnableMethodSecurity} allows later services to use annotations such as
 * {@code @PreAuthorize("hasRole('PLANNER')")}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Defines the HTTP security rules for every request.
     *
     * <ul>
     *   <li>CSRF is off: it protects cookie sessions, and this API uses bearer tokens only.</li>
     *   <li>Sessions are stateless: nothing is remembered between requests; the token is the identity.</li>
     *   <li>Public: health probes and the OpenAPI/Swagger pages. Everything else needs a valid token.</li>
     *   <li>CORS uses Spring defaults (same-origin); the Vite dev server proxies {@code /api} instead.</li>
     * </ul>
     *
     * @param http Spring's builder for the security filter chain (injected by Spring)
     * @return the finished filter chain applied to all requests
     * @throws Exception if the configuration is invalid (propagated from the builder)
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, @org.springframework.beans.factory.annotation.Value("${management.server.port:9091}") int managementPort) throws Exception {
        // Actuator endpoints (probes, metrics) are open ONLY on the internal management port, which the ingress
        // never exposes. On the public port they would need a token like everything else.
        org.springframework.security.web.util.matcher.RequestMatcher onManagementPort = r -> r.getLocalPort() == managementPort;
        http
            .csrf(csrf -> csrf.disable()) // bearer-token API, no cookies
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Security headers on every API response (the SPA's own CSP is set by nginx).
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
            .authorizeHttpRequests(a -> a
                // The live-event stream (SseEmitter) completes on an ASYNC dispatch of the already-authorised
                // request; that internal re-dispatch must not be checked again (it has no new credentials).
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .requestMatchers(onManagementPort).permitAll()
                .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .cors(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Builds the converter that turns a verified JWT into a Spring authentication object.
     *
     * <p>It does two things: maps Keycloak realm roles to Spring authorities (see
     * {@link #authorities(Jwt)}), and uses the {@code preferred_username} claim as the user's name
     * (instead of the default {@code sub}, which is an opaque id).
     *
     * @return a converter from {@link Jwt} to an authentication token
     */
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    /**
     * Reads the Keycloak realm roles from the token and converts them to Spring authorities.
     *
     * <p>Keycloak puts roles at {@code realm_access.roles}, for example
     * {@code ["regional_manager"]}. Spring expects the {@code ROLE_} prefix and conventionally
     * upper case, so {@code regional_manager} becomes {@code ROLE_REGIONAL_MANAGER}. A token with
     * no {@code realm_access} claim yields an empty list (the user then has no roles).
     *
     * @param jwt the verified access token
     * @return one {@link GrantedAuthority} per realm role; never {@code null}
     */
    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> result = new ArrayList<>();
        Object realmAccess = jwt.getClaim("realm_access");
        // Pattern-matching checks both the shape (Map) and the content (a collection under "roles").
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                result.add(new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()));
            }
        }
        return result;
    }
}
