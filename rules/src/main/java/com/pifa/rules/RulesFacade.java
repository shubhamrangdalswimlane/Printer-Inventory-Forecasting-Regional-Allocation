package com.pifa.rules;

import com.pifa.rules.model.AllocationConfig;
import com.pifa.rules.model.AllocationDirective;
import com.pifa.rules.model.AllocationLine;
import com.pifa.rules.model.Classification;
import com.pifa.rules.model.ForecastConfig;
import com.pifa.rules.model.IngestionConfig;
import com.pifa.rules.model.ProductStock;
import com.pifa.rules.model.PublishCheck;
import com.pifa.rules.model.TransferCheck;
import com.pifa.rules.model.Violation;
import com.pifa.rules.model.WorkflowCheck;
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
    public static final String RULESET_VERSION = "0.4.0";

    /**
     * Every DRL file in the ruleset, as classpath resource paths. <b>Add new rule files here</b>;
     * a file not listed is not loaded (a unit test guards against forgetting).
     */
    static final List<String> DRL_FILES = List.of(
        "pifa/rules/allocation/strategy/classification.drl",
        "pifa/rules/allocation/validation/allocation_validation.drl",
        "pifa/rules/ingestion/ingestion.drl",
        "pifa/rules/forecast/forecast.drl",
        "pifa/rules/transfer/transfer.drl",
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
        run(List.of(stock), null, null);
        return stock.getClassification();
    }

    /**
     * Classifies every product and chooses its allocation strategy ({@code pifa.rules.allocation.strategy}).
     *
     * @param stocks one fact per product; their {@code classification} is set as a side effect
     * @param config the policy switches (cap-at-demand)
     * @return one directive per product
     */
    public List<AllocationDirective> decideStrategies(List<ProductStock> stocks, AllocationConfig config) {
        List<AllocationDirective> directives = new ArrayList<>();
        run(stocks, config, directives);
        return directives;
    }

    /**
     * Checks an allocation ({@code pifa.rules.allocation.validation}, BR-VA-01..04).
     *
     * @param stocks one fact per product, with classification already set
     * @param lines  one fact per product x region
     * @return ERROR and WARNING findings, each with the product id as subject
     */
    public List<Violation> validateAllocation(List<ProductStock> stocks, List<AllocationLine> lines) {
        List<Object> facts = new ArrayList<>(stocks);
        facts.addAll(lines);
        return run(facts, null, null);
    }

    /**
     * Validates one transfer ({@code pifa.rules.transfer}, BR-TR-01..04).
     *
     * @param check the transfer and the current allocation around it
     * @return findings; any ERROR means the transfer is refused
     */
    public List<Violation> validateTransfer(TransferCheck check) {
        return run(List.of(check), null, null);
    }

    /**
     * Runs a workflow guard ({@code pifa.rules.workflow}, BR-WF-02/03).
     *
     * @param check what the workflow knows
     * @return ERROR findings that block the action; empty if it is allowed
     */
    public List<Violation> checkWorkflow(WorkflowCheck check) {
        return run(List.of(check), null, null);
    }

    /**
     * Runs the ingestion business rules ({@code pifa.rules.ingestion}) over the facts of one parsed file.
     *
     * @param facts  the row facts built by the caller ({@code ProductRow}, {@code QtyCell},
     *               {@code MonthCell}, {@code DateCell}); may be empty
     * @param config limits used by the rules (max quantity, spike factor)
     * @return all ERROR and WARNING findings, in no guaranteed order; never null
     */
    public List<Violation> validateIngestion(List<?> facts, IngestionConfig config) {
        return run(facts, config, null);
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
        return run(facts, config, null);
    }

    /**
     * Runs the publish guard ({@code pifa.rules.workflow}, BR-WF-01).
     *
     * @param check what the workflow knows about the cycle
     * @return ERROR findings that block publishing; empty if publishing is allowed
     */
    public List<Violation> checkPublish(PublishCheck check) {
        return run(List.of(check), null, null);
    }

    /**
     * Shared runner: fresh stateless session, both DRL globals set (so no rule can hit a null global),
     * facts (+ optional config fact) inserted, all rules fired once.
     *
     * @param facts      the facts
     * @param config     an extra configuration fact, or null
     * @param directives list receiving allocation directives, or null to discard them
     * @return the findings collected in the {@code violations} global
     */
    private List<Violation> run(List<?> facts, Object config, List<AllocationDirective> directives) {
        List<Violation> found = new ArrayList<>();
        StatelessKieSession session = container.newStatelessKieSession();
        session.setGlobal("violations", found);
        session.setGlobal("directives", directives != null ? directives : new ArrayList<>());
        List<Object> all = new ArrayList<>(facts);
        if (config != null) all.add(config); // the config is a fact too, so rules can read the limits
        session.execute(all);
        return found;
    }
}
