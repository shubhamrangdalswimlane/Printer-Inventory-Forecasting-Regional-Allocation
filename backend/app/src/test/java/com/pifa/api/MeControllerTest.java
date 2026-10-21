package com.pifa.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for {@link MeController} and the security rules in front of it.
 *
 * <p>The whole Spring context starts, but the database, JPA and Flyway auto-configuration are
 * switched off (no Postgres needed) and the {@code JwtDecoder} is replaced by a mock (no Keycloak
 * needed). {@code MockMvc} then sends fake HTTP requests; the {@code jwt()} helper injects an
 * already-"verified" token, so these tests check our own logic, not Keycloak's signing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // Turn off everything that needs a real database.
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    // Empty issuer: nothing tries to contact Keycloak at startup.
    "spring.security.oauth2.resourceserver.jwt.issuer-uri="
})
class MeControllerTest {

    /** Sends simulated HTTP requests through the full security filter chain. */
    @Autowired MockMvc mvc;

    /** Stand-in for the real token verifier, so no network call to Keycloak is made. */
    @MockBean JwtDecoder jwtDecoder;

    /** No token at all must be rejected with HTTP 401 (deny-by-default). */
    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    /**
     * A Regional Manager token for North returns HTTP 200 with their username, their single
     * region and the {@code /regional} landing page.
     */
    @Test
    void regionalManagerSeesRegionsAndLanding() throws Exception {
        mvc.perform(get("/api/v1/me").with(jwt()
                // Claims exactly as Keycloak would issue them.
                .jwt(j -> j.claim("preferred_username", "anita.north")
                           .claim("regions", List.of("NORTH"))
                           .claim("realm_access", Map.of("roles", List.of("regional_manager"))))
                // The jwt() helper skips our converter, so the Spring authority is given directly.
                .authorities(new SimpleGrantedAuthority("ROLE_REGIONAL_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("anita.north"))
            .andExpect(jsonPath("$.regions[0]").value("NORTH"))
            .andExpect(jsonPath("$.landing").value("/regional"));
    }
}
