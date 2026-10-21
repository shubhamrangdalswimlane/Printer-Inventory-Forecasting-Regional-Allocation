package com.pifa.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Live refresh over PostgreSQL LISTEN/NOTIFY: an event published inside a transaction reaches the open
 * stream only when the transaction commits, and never when it rolls back. Also: the stream needs a token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=", "pifa.storage.dir=target/test-files"})
class LiveEventsTest {

    @MockBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mvc;
    @Autowired LiveEvents events;
    @Autowired TransactionTemplate tx;

    @Test
    void eventsArriveOnlyAfterCommit() throws Exception {
        mvc.perform(get("/api/v1/events")).andReturn().getResponse().getStatus();
        assertThat(mvc.perform(get("/api/v1/events")).andReturn().getResponse().getStatus()).isEqualTo(401);

        MvcResult stream = mvc.perform(get("/api/v1/events").with(jwt())).andReturn();
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();

        UUID rolledBack = UUID.randomUUID();
        UUID committed = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            events.publish(rolledBack, "TEST");
            s.setRollbackOnly();
        });
        tx.executeWithoutResult(s -> events.publish(committed, "TEST"));

        String body = "";
        for (int i = 0; i < 50 && !body.contains(committed.toString()); i++) {
            Thread.sleep(100);
            body = stream.getResponse().getContentAsString();
        }
        assertThat(body).contains("event:change").contains(committed.toString());
        assertThat(body).doesNotContain(rolledBack.toString());
    }
}
