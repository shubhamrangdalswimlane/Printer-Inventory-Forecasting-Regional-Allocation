package com.pifa.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for {@link MeController}, the security rules in front of it, and role checks on upload.
 *
 * <p>The whole Spring context starts and needs a PostgreSQL database (set {@code PIFA_DB_URL},
 * {@code PIFA_DB_USER}, {@code PIFA_DB_PASSWORD}; defaults match docker-compose). The
 * {@code JwtDecoder} is replaced by a mock (no Keycloak needed) and the {@code jwt()} helper injects
 * an already-"verified" token, so these tests check our own logic, not Keycloak's signing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // Empty issuer: nothing tries to contact Keycloak at startup.
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
    "pifa.storage.dir=target/test-files"
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

    /** A Regional Manager may not upload a forecast: the endpoint requires role forecaster (HTTP 403). */
    @Test
    void regionalManagerCannotUploadForecast() throws Exception {
        mvc.perform(multipart("/api/v1/datasets/forecast/uploads")
                .file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", new byte[]{1, 2, 3}))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REGIONAL_MANAGER"))))
            .andExpect(status().isForbidden());
    }

    /** A Forecaster may not upload an inventory file (that is the Planner's job): HTTP 403. */
    @Test
    void forecasterCannotUploadInventory() throws Exception {
        mvc.perform(multipart("/api/v1/datasets/inventory/uploads")
                .file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", new byte[]{1, 2, 3}))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_FORECASTER"))))
            .andExpect(status().isForbidden());
    }
}
