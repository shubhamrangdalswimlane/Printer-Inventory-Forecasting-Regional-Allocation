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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri="
})
class MeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void regionalManagerSeesRegionsAndLanding() throws Exception {
        mvc.perform(get("/api/v1/me").with(jwt()
                .jwt(j -> j.claim("preferred_username", "anita.north")
                           .claim("regions", List.of("NORTH"))
                           .claim("realm_access", Map.of("roles", List.of("regional_manager"))))
                .authorities(new SimpleGrantedAuthority("ROLE_REGIONAL_MANAGER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("anita.north"))
            .andExpect(jsonPath("$.regions[0]").value("NORTH"))
            .andExpect(jsonPath("$.landing").value("/regional"));
    }
}
