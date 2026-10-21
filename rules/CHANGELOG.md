# pifa-rules changelog

Every change to a `.drl` file must add an entry here and bump `RulesFacade.RULESET_VERSION`
(see Phase.md quality gates). The version is stamped on results so any past result can be traced
to the exact rules that produced it.

## 0.3.0
- Phase 2: new package `pifa.rules.forecast` (BR-VA-05 reason required above threshold, BR-VA-06 revision
  above 100%, BR-FC-MAX quantity limit, BR-FC-SPIKE forecast far above sales) and `pifa.rules.workflow`
  (BR-WF-01 publish guard). Run via `RulesFacade.validateForecast` and `checkPublish`.
  Covered by `ForecastRulesTest`.
- `RulesFacade` now loads DRL files explicitly (`DRL_FILES`) instead of scanning the classpath, which
  did not work inside a Spring Boot fat jar. `DrlRegistrationTest` fails if a DRL file is not listed.

## 0.2.0
- Phase 1: new package `pifa.rules.ingestion` (PRODUCT_BLANK / UNKNOWN / DUPLICATE, QTY_INVALID,
  QTY_SPIKE, MONTH_MISMATCH, DATE_INVALID). Run via `RulesFacade.validateIngestion`.
  Covered by `IngestionRulesTest`.

## 0.1.0
- Phase 0 spike: `pifa.rules.allocation.strategy` classification rules (BR-AL-02), run from
  `RulesFacade` in a stateless session. Covered by `RulesFacadeTest`.
