package com.pifa;

import com.pifa.rules.RulesFacade;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PifaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PifaApplication.class, args);
    }

    /** Singleton: the Drools container is built once at startup. */
    @Bean
    RulesFacade rulesFacade() {
        return new RulesFacade();
    }
}
