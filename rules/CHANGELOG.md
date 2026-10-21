# pifa-rules changelog

Every change to a `.drl` file must add an entry here and bump `RulesFacade.RULESET_VERSION`
(see Phase.md quality gates). The version is stamped on allocation snapshots so any past result
can be traced to the exact rules that produced it.

## 0.1.0
- Phase 0 spike: `pifa.rules.allocation.strategy` classification rules (BR-AL-02), run from
  `RulesFacade` in a stateless session. Covered by `RulesFacadeTest`.
