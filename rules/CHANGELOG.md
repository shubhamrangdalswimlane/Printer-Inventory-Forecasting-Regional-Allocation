# pifa-rules changelog

Every change to a `.drl` file must add an entry here and bump `RulesFacade.RULESET_VERSION`
(see Phase.md quality gates). The version is stamped on results so any past result can be traced
to the exact rules that produced it.

## 0.2.0
- Phase 1: new package `pifa.rules.ingestion` (PRODUCT_BLANK / UNKNOWN / DUPLICATE, QTY_INVALID,
  QTY_SPIKE, MONTH_MISMATCH, DATE_INVALID). Run via `RulesFacade.validateIngestion`.
  Covered by `IngestionRulesTest`.

## 0.1.0
- Phase 0 spike: `pifa.rules.allocation.strategy` classification rules (BR-AL-02), run from
  `RulesFacade` in a stateless session. Covered by `RulesFacadeTest`.
