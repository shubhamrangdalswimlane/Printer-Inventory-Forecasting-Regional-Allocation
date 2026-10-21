# UAT scripts

Scripted user-acceptance tests for Phase 6a. Run them in the UAT environment on **synthetic data**
(**Admin, Synthetic data, Generate**). Each script is a table: perform the step, compare the result with
**Expected**, and mark **Pass/Fail**. Log every Fail as a defect with the request id shown in the error message.

| Script | Who | Covers |
|---|---|---|
| [uat-01-forecaster.md](uat-01-forecaster.md) | Forecaster | Upload, validation, edits, publish, recall, versions |
| [uat-02-regional-manager.md](uat-02-regional-manager.md) | each of the 4 Regional Managers | Review, revisions, sales analysis, transfers, finalize |
| [uat-03-planner.md](uat-03-planner.md) | Planner | Reserves, allocation proposal, approval, finalization monitor |
| [uat-04-admin.md](uat-04-admin.md) | Admin | Master data, parameters, audit, cycles, users, go-live tools |
| [uat-05-negative.md](uat-05-negative.md) | QA with each role | Permission boundaries and bad files |

Test users in UAT: one per role plus one Regional Manager per region. Users must sign in with their own
accounts. Shared accounts would make the audit log useless.

The editability matrix (`docs/PRD.md`) must be covered completely. Script 05 walks through every
"not allowed" cell, and the other scripts walk through every "allowed" cell.

Sign-off: when every script passes or every remaining defect has an agreed workaround, the business owner signs the
UAT sign-off sheet (go-live checklist item 1).
