package com.pifa;

import com.pifa.rules.RulesFacade;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the PIFA backend (the {@code pifa-api} role).
 *
 * <p>{@code @SpringBootApplication} turns on three things: component scanning of the
 * {@code com.pifa} package (so controllers and services are found automatically), Spring Boot
 * auto-configuration (web server, security, database, Flyway ...), and this class as a source of
 * {@code @Bean} definitions. {@code @ConfigurationPropertiesScan} registers
 * {@link com.pifa.config.PifaProperties} so the {@code pifa.*} settings can be injected.
 * {@code @EnableScheduling} lets {@code @Scheduled} methods (the SFTP poller) run.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PifaApplication {

    /**
     * Starts the embedded web server and the whole Spring context.
     *
     * @param args command-line arguments, passed through to Spring (e.g. {@code --server.port=9090})
     */
    public static void main(String[] args) {
        SpringApplication.run(PifaApplication.class, args);
    }

    /**
     * Creates the one shared {@link RulesFacade} bean.
     *
     * <p>Building it compiles all Drools rules, which is slow, so Spring does it once at startup
     * (and fails fast if a rule is broken). Other classes receive it by constructor injection.
     *
     * @return the singleton gateway to the Drools ruleset
     */
    @Bean
    RulesFacade rulesFacade() {
        return new RulesFacade();
    }
}
