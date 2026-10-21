package com.pifa.rules;

import com.pifa.rules.model.Classification;
import com.pifa.rules.model.ProductStock;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;

/** Typed entry point to the Drools ruleset. One shared container, one stateless session per call. */
public class RulesFacade {

    public static final String RULESET_VERSION = "0.1.0";

    private final KieContainer container;

    public RulesFacade() {
        this.container = KieServices.Factory.get().getKieClasspathContainer();
    }

    public Classification classify(String productName, int allocatableQty, int totalDemand) {
        ProductStock stock = new ProductStock(productName, Math.max(allocatableQty, 0), totalDemand);
        StatelessKieSession session = container.newStatelessKieSession();
        session.execute(stock);
        return stock.getClassification();
    }
}
