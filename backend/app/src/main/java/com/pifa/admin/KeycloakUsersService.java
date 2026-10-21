package com.pifa.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.pifa.common.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Read-only list of users, roles, regions and last login from Keycloak, for the Admin dashboard
 * (PRD FR-AD-09). Users are managed in Keycloak itself; this only shows them.
 *
 * <p>It uses its own confidential client ({@code pifa-admin-reader}) whose service account holds only the
 * {@code view-users} and {@code view-events} roles of the realm, so the API never holds admin rights.
 * If the client is not configured the endpoint answers 503.
 */
@Service
public class KeycloakUsersService {

    private final String baseUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final RestClient http = RestClient.create();

    public KeycloakUsersService(@Value("${pifa.keycloak.admin-base-url:}") String baseUrl, @Value("${pifa.keycloak.realm:pifa-dev}") String realm,
                                @Value("${pifa.keycloak.reader-client-id:pifa-admin-reader}") String clientId,
                                @Value("${pifa.keycloak.reader-client-secret:}") String clientSecret) {
        this.baseUrl = baseUrl;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * @return one entry per user: username, name, email, enabled, roles (the four PIFA roles only), regions, lastLogin (if known)
     * @throws ApiException 503 when the reader client is not configured or Keycloak cannot be reached
     */
    public List<Map<String, Object>> users() {
        if (baseUrl.isBlank() || clientSecret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "The Keycloak user view is not configured (pifa.keycloak.*)");
        }
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            JsonNode token = http.post().uri(baseUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
            String bearer = "Bearer " + token.get("access_token").asText();
            String admin = baseUrl + "/admin/realms/" + realm;

            Map<String, OffsetDateTime> lastLogin = new java.util.HashMap<>();
            JsonNode events = http.get().uri(admin + "/events?type=LOGIN&max=2000").header("Authorization", bearer).retrieve().body(JsonNode.class);
            if (events != null) for (JsonNode e : events) {
                String uid = e.path("userId").asText();
                OffsetDateTime t = OffsetDateTime.ofInstant(Instant.ofEpochMilli(e.path("time").asLong()), ZoneOffset.UTC);
                lastLogin.merge(uid, t, (a, b) -> a.isAfter(b) ? a : b);
            }

            List<Map<String, Object>> out = new ArrayList<>();
            JsonNode users = http.get().uri(admin + "/users?max=1000&briefRepresentation=false").header("Authorization", bearer).retrieve().body(JsonNode.class);
            for (JsonNode u : users) {
                if (u.path("username").asText().startsWith("service-account-")) continue;
                List<String> roles = new ArrayList<>();
                JsonNode rm = http.get().uri(admin + "/users/" + u.path("id").asText() + "/role-mappings/realm").header("Authorization", bearer).retrieve().body(JsonNode.class);
                if (rm != null) for (JsonNode r : rm) {
                    String n = r.path("name").asText();
                    if (List.of("forecaster", "regional_manager", "planner", "admin").contains(n)) roles.add(n);
                }
                List<String> regions = new ArrayList<>();
                u.path("attributes").path("regions").forEach(r -> regions.add(r.asText()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", u.path("username").asText());
                m.put("name", (u.path("firstName").asText("") + " " + u.path("lastName").asText("")).trim());
                m.put("email", u.path("email").asText(""));
                m.put("enabled", u.path("enabled").asBoolean());
                m.put("roles", roles);
                m.put("regions", regions);
                m.put("lastLogin", lastLogin.get(u.path("id").asText()));
                out.add(m);
            }
            return out;
        } catch (RestClientException | NullPointerException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "KEYCLOAK_UNAVAILABLE", "Could not read users from Keycloak: " + e.getMessage());
        }
    }

    /** @return the Keycloak admin console URL for the realm (for the "manage users" link), or null if not configured */
    public String consoleUrl() {
        return baseUrl.isBlank() ? null : baseUrl + "/admin/master/console/#/" + realm + "/users";
    }
}
