package com.pifa.rules;

import com.pifa.rules.model.Classification;
import com.pifa.rules.model.ForecastConfig;
import com.pifa.rules.model.IngestionConfig;
import com.pifa.rules.model.PublishCheck;
import com.pifa.rules.model.ProductStock;
import com.pifa.rules.model.Violation;
import java.util.ArrayList;
import java.util.List;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;
import org.kie.internal.io.ResourceFactory;

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
 *
 * <p><b>Why the container is built by hand:</b> the usual {@code getKieClasspathContainer()} scans the
 * classpath for {@code META-INF/kmodule.xml}, which fails inside a Spring Boot fat jar (nested jars
 * are not scannable) - the rules would silently not load in a deployed build. Reading each DRL as a
 * classpath resource works in both a plain classpath and a fat jar.
 */
public class RulesFacade {

    /** Version of the ruleset; stamped on results so they can be explained later. Bump with every DRL change. */
    public static final String RULESET_VERSION = "0.3.0";

    /**
     * Every DRL file in the ruleset, as classpath resource paths. <b>Add new rule files here</b>;
     * a file not listed is not loaded (a unit test guards against forgetting).
     */
    static final List<String> DRL_FILES = List.of(
        "pifa/rules/allocation/strategy/classification.drl",
        "pifa/rules/ingestion/ingestion.drl",
        "pifa/rules/forecast/forecast.drl",
        "pifa/rules/workflow/workflow.drl");

    /** Compiled rules. Shared and read-only. */
    private final KieContainer container;

    /**
     * Compiles every DRL listed in {@link #DRL_FILES}.
     *
     * @throws IllegalStateException at startup if a DRL file is missing or does not compile, which is
     *         the desired fail-fast behaviour (a broken rule must stop the application, not be skipped)
     */
    public RulesFacade() {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        for (String path : DRL_FILES) {
            kfs.write("src/main/resources/" + path, ResourceFactory.newClassPathResource(path, RulesFacade.class));
        }
        KieBuilder builder = ks.newKieBuilder(kfs).buildAll();
        if (builder.getResults().hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException("Rules failed to compile: " + builder.getResults().getMessages(Message.Level.ERROR));
        }
        this.container = ks.newKieContainer(ks.getRepository().getDefaultReleaseId());
    }

    /**
     * Decides whether a model is constrained or sufficient (PRD rule BR-AL-02).
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
        StatelessKieSession session = container.newStatelessKieSession();
        session.execute(stock); // fires all matching rules, which call stock.setClassification(...)
        return stock.getClassification();
    }

    /**
     * Runs the ingestion business rules ({@code pifa.rules.ingestion}) over the facts of one parsed file.
     *
     * <p>A fresh stateless session is created, the findings list is registered as the DRL global
     * {@code violations}, every fact plus the config is inserted, and all rules fire once.
     *
     * @param facts  the row facts built by the caller ({@code ProductRow}, {@code QtyCell},
     *               {@code MonthCell}, {@code DateCell}); may be empty
     * @param config limits used by the rules (max quantity, spike factor)
     * @return all ERROR and WARNING findings, in no guaranteed order; never null
     */
    public List<Violation> validateIngestion(List<?> facts, IngestionConfig config) {
        return run(facts, config);
    }

    /**
     * Runs the forecast rules ({@code pifa.rules.forecast}): change control for Regional Manager
     * revisions (BR-VA-05/06) and working-copy limit/spike checks (BR-FC-MAX, BR-FC-SPIKE).
     *
     * @param facts  {@code ForecastChange} and/or {@code ForecastCell} facts; may be empty
     * @param config the limits (change threshold, spike factor, maximum quantity)
     * @return all ERROR and WARNING findings; never null
     */
    public List<Violation> validateForecast(List<?> facts, ForecastConfig config) {
        return run(facts, config);
    }

    /**
     * Runs the publish guard ({@code pifa.rules.workflow}, BR-WF-01).
     *
     * @param check what the workflow knows about the cycle
     * @return ERROR findings that block publishing; empty if publishing is allowed
     */
    public List<Violation> checkPublish(PublishCheck check) {
        return run(List.of(check), null);
    }

    /** Shared runner: fresh stateless session, findings list as the DRL global, facts (+ optional config) inserted. */
    private List<Violation> run(List<?> facts, Object config) {
        List<Violation> found = new ArrayList<>();
        StatelessKieSession session = container.newStatelessKieSession();
        session.setGlobal("violations", found);
        List<Object> all = new ArrayList<>(facts);
        if (config != null) all.add(config); // the config is a fact too, so rules can read the limits
        session.execute(all);
        return found;
    }
}
