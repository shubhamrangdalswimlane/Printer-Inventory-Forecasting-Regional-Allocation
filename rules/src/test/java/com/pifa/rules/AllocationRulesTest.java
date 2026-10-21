package com.pifa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pifa.rules.model.AllocationConfig;
import com.pifa.rules.model.AllocationDirective;
import com.pifa.rules.model.AllocationLine;
import com.pifa.rules.model.Classification;
import com.pifa.rules.model.ProductStock;
import com.pifa.rules.model.Strategy;
import com.pifa.rules.model.TransferCheck;
import com.pifa.rules.model.Violation;
import com.pifa.rules.model.WorkflowCheck;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the allocation strategy, allocation validation, transfer and workflow-guard rules.
 * The PRD worked example (Model A, 100 units, constrained) is used throughout.
 */
class AllocationRulesTest {

    private final RulesFacade rules = new RulesFacade();

    private static List<String> codes(List<Violation> v) { return v.stream().map(Violation::getCode).toList(); }

    @Test
    void constrainedProductGetsWeightedBaselineAndSufficientGetsFullDemand() {
        ProductStock a = new ProductStock("A", "Model A", 100, 130);   // constrained
        ProductStock b = new ProductStock("B", "Model B", 500, 120);   // sufficient
        List<AllocationDirective> d = rules.decideStrategies(List.of(a, b), new AllocationConfig(false));
        assertThat(a.getClassification()).isEqualTo(Classification.CONSTRAINED);
        assertThat(b.getClassification()).isEqualTo(Classification.SUFFICIENT);
        assertThat(d).extracting(AllocationDirective::getProductId, AllocationDirective::getStrategy)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("A", Strategy.WEIGHTED_BASELINE),
                                       org.assertj.core.groups.Tuple.tuple("B", Strategy.FULL_DEMAND));
    }

    @Test
    void capAtDemandSwitchesTheConstrainedStrategy() {
        ProductStock a = new ProductStock("A", "Model A", 100, 130);
        assertThat(rules.decideStrategies(List.of(a), new AllocationConfig(true)))
            .extracting(AllocationDirective::getStrategy).containsExactly(Strategy.WEIGHTED_BASELINE_CAPPED);
    }

    @Test
    void plainClassificationProducesNoDirectivesAndNeedsNoConfig() {
        assertThat(rules.classify("A", 100, 130)).isEqualTo(Classification.CONSTRAINED);
    }

    private static ProductStock constrainedA() {
        ProductStock s = new ProductStock("A", "Model A", 100, 130);
        s.setClassification(Classification.CONSTRAINED);
        return s;
    }

    @Test
    void workedExampleAllocationIsValid() {
        var lines = List.of(new AllocationLine("A", "Model A", "NORTH", 25, 40), new AllocationLine("A", "Model A", "EAST", 25, 35),
                            new AllocationLine("A", "Model A", "WEST", 25, 30), new AllocationLine("A", "Model A", "SOUTH", 25, 25));
        assertThat(rules.validateAllocation(List.of(constrainedA()), lines)).isEmpty();
    }

    @Test
    void totalAboveAllocatableIsAnErrorAndNegativeToo() {
        var lines = List.of(new AllocationLine("A", "Model A", "NORTH", 60, 40), new AllocationLine("A", "Model A", "EAST", 50, 35),
                            new AllocationLine("A", "Model A", "WEST", -1, 30), new AllocationLine("A", "Model A", "SOUTH", 0, 25));
        List<Violation> v = rules.validateAllocation(List.of(constrainedA()), lines);
        assertThat(codes(v)).contains("BR-VA-01", "BR-VA-02", "BR-VA-03", "BR-VA-04");
        assertThat(v).filteredOn(x -> x.getCode().equals("BR-VA-01")).allMatch(x -> x.getSubject().equals("A") && x.getSeverity() == Violation.Severity.ERROR);
        assertThat(v).filteredOn(x -> x.getCode().equals("BR-VA-03")).allMatch(x -> x.getSeverity() == Violation.Severity.WARNING);
    }

    /** PRD worked example: move 5 of Model A from East to North (25/25/25/25 -> 30/20/25/25). */
    @Test
    void workedExampleTransferIsAllowed() {
        var t = new TransferCheck("A", "Model A", "EAST", "NORTH", 5, 25, 25, 40, 0, false, false, 100, 100);
        assertThat(rules.validateTransfer(t)).isEmpty();
    }

    @Test
    void transferRulesRefuseBadTransfers() {
        assertThat(codes(rules.validateTransfer(new TransferCheck("A", "A", "EAST", "NORTH", 0, 25, 25, 40, 0, false, false, 100, 100)))).containsExactly("BR-TR-01");
        // receiver demand 100, so only the donor rule can fire here
        assertThat(codes(rules.validateTransfer(new TransferCheck("A", "A", "EAST", "NORTH", 26, 25, 25, 100, 0, false, false, 100, 100)))).containsExactly("BR-TR-01");
        assertThat(codes(rules.validateTransfer(new TransferCheck("A", "A", "EAST", "NORTH", 21, 25, 25, 60, 5, false, false, 100, 100)))).containsExactly("BR-TR-01"); // floor 5
        assertThat(codes(rules.validateTransfer(new TransferCheck("A", "A", "EAST", "EAST", 1, 25, 25, 40, 0, false, false, 100, 100)))).containsExactly("BR-TR-01");
        assertThat(codes(rules.validateTransfer(new TransferCheck("A", "A", "EAST", "NORTH", 1, 25, 25, 40, 0, false, false, 100, 101)))).containsExactly("BR-TR-02");
        assertThat(codes(rules.validateTransfer(new TransferCheck("A", "A", "EAST", "NORTH", 1, 25, 25, 40, 0, true, false, 100, 100)))).containsExactly("BR-TR-03");
        assertThat(codes(rules.validateTransfer(new TransferCheck("A", "A", "EAST", "NORTH", 1, 25, 25, 40, 0, false, true, 100, 100)))).containsExactly("BR-TR-03");
        var warn = rules.validateTransfer(new TransferCheck("A", "A", "EAST", "NORTH", 10, 25, 25, 30, 0, false, false, 100, 100));
        assertThat(codes(warn)).containsExactly("BR-TR-04");
        assertThat(warn.get(0).getSeverity()).isEqualTo(Violation.Severity.WARNING);
    }

    @Test
    void workflowGuards() {
        assertThat(codes(rules.checkWorkflow(new WorkflowCheck("APPROVE_ALLOCATION", null, false, true, 0, 0, false)))).containsExactly("BR-WF-02");
        assertThat(codes(rules.checkWorkflow(new WorkflowCheck("APPROVE_ALLOCATION", null, true, false, 0, 0, false)))).containsExactly("BR-WF-02");
        assertThat(codes(rules.checkWorkflow(new WorkflowCheck("APPROVE_ALLOCATION", null, true, true, 2, 0, false)))).containsExactly("BR-WF-02");
        assertThat(rules.checkWorkflow(new WorkflowCheck("APPROVE_ALLOCATION", null, true, true, 0, 0, false))).isEmpty();
        assertThat(codes(rules.checkWorkflow(new WorkflowCheck("FINALIZE_REGION", "NORTH", true, true, 0, 1, false)))).containsExactly("BR-WF-03");
        assertThat(rules.checkWorkflow(new WorkflowCheck("FINALIZE_REGION", "NORTH", true, true, 0, 0, false))).isEmpty();
        assertThat(codes(rules.checkWorkflow(new WorkflowCheck("FINAL", null, true, true, 0, 0, false)))).containsExactly("BR-WF-03");
        assertThat(codes(rules.checkWorkflow(new WorkflowCheck("FINAL", null, true, true, 0, 2, true)))).containsExactly("BR-WF-03");
        assertThat(rules.checkWorkflow(new WorkflowCheck("FINAL", null, true, true, 0, 0, true))).isEmpty();
    }
}
