package com.pifa.rules;

import com.pifa.rules.model.Classification;
import com.pifa.rules.model.ProductStock;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;

/**
 * The single, typed entry point from Java code into the Drools ruleset.
 *
 * <p>Callers never touch Drools types. They call a method here with plain values and get a plain
 * result back. This keeps Drools details (sessions, facts, DRL files) inside this module, as the
 * architecture requires (architecture.md section 7).
 *
 * <p>Lifecycle: create <b>one</b> instance at application start (Spring does this in
 * {@code PifaApplication}). Building the {@link KieContainer} compiles the DRL files, which is
 * slow, so it is done once. Each call then creates a cheap, throw-away stateless session, which
 * makes the class safe to use from many threads.
 */
public class RulesFacade {

    /** Version of the ruleset; stamped on allocation snapshots so results can be explained later. */
    public static final String RULESET_VERSION = "0.1.0";

    /** Compiled rules, loaded from {@code META-INF/kmodule.xml} on the classpath. Shared and read-only. */
    private final KieContainer container;

    /**
     * Loads and compiles every rule found on the classpath.
     *
     * <p>Side effects: reads {@code kmodule.xml} and the {@code .drl} files; throws a runtime
     * exception at startup if any rule fails to compile, which is the desired fail-fast behaviour.
     */
    public RulesFacade() {
        this.container = KieServices.Factory.get().getKieClasspathContainer();
    }

    /**
     * Decides whether a model is constrained or sufficient (PRD rule BR-AL-02).
     *
     * <p>Steps: negative stock is treated as 0, a {@link ProductStock} fact is built, a new
     * stateless session runs the rules against it, and the classification the rules wrote onto the
     * fact is returned.
     *
     * @param productName    printer model name (informational, used only for the fact)
     * @param allocatableQty units that can be allocated; values below 0 are treated as 0
     * @param totalDemand    total revised demand across all regions
     * @return {@link Classification#CONSTRAINED} if {@code totalDemand > allocatableQty},
     *         otherwise {@link Classification#SUFFICIENT}
     */
    public Classification classify(String productName, int allocatableQty, int totalDemand) {
        // Guard: stock can never be negative, so clamp before the rules see it.
        ProductStock stock = new ProductStock(productName, Math.max(allocatableQty, 0), totalDemand);
        // Stateless session: no memory between calls, so there is no shared mutable state.
        StatelessKieSession session = container.newStatelessKieSession();
        session.execute(stock); // fires all matching rules, which call stock.setClassification(...)
        return stock.getClassification();
    }
}
