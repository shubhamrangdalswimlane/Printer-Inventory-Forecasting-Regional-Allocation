# Cutover runbook: synthetic data to real data, then go-live

This is the step-by-step plan for Phase 6 (`docs/Phase.md`). Each step names an owner and how to check it.
The **Admin, Go-live** API (`/api/v1/admin/go-live/...`) does the checks the system can do by itself.
`GET /api/v1/admin/go-live/readiness` lists every go-live checklist item with PASS, WARN, FAIL or MANUAL.

## T-5 weeks: UAT on synthetic data (Phase 6a)

1. Generate synthetic data in UAT (**Admin, Synthetic data, Generate**). Run the scripts in
   [../uat/](../uat/README.md) with each role. Log defects. Fix, redeploy, and re-run the failed steps.
2. Load the **real product master**: **Admin, Master data, Import** with sheet `Products`
   (`Product_name`, `Aliases`, `Active`).
3. **Matching report**, for a recent real file from each upstream system (forecast and inventory):
   ```bash
   curl -fsS -H "Authorization: Bearer $TOKEN" -F file=@FORECAST_2026-11.xlsx https://<host>/api/v1/admin/go-live/matching-report
   ```
   For each name in `problems`, either add an alias to the suggested product (**Master data, product, Aliases**)
   or add the product. Repeat until `ok` is `true` for every file (go-live checklist item 8).
4. **Real sales history (24 months)**: download the template (`GET /api/v1/admin/go-live/templates/sales`), fill it in
   from the sales system, then `POST /api/v1/admin/go-live/sales-history` (field `file`). A response of 422 lists every
   problem and nothing is written. Fix the file and post it again. Re-posting a corrected file is safe: values are overwritten.
5. **SFTP onboarding** with each upstream owner: accounts `sftp-forecast` and `sftp-inventory` (and `sftp-sales` if the
   sales feed is used), key exchange, IP allowlist, upload as `.part` then rename, and a test drop in UAT.
   Agree the drop schedule and who follows up on rejected files ([../runbooks/rejected-file.md](../runbooks/rejected-file.md)).
6. Train users ([../training/](../training/README.md)). Get UAT sign-off from the business owner.

## T-3 weeks: prepare production (Phase 6b)

7. Deploy the release candidate to `prod` with `SPRING_PROFILES_ACTIVE=prod`. The profile sets S3 storage, ClamAV,
   strict SFTP host keys, mail on and the synthetic generator off.
8. Keycloak prod realm (`infra/terraform`): real users, roles and `regions` attribute, MFA for Admin and Planner,
   corporate federation tested.
9. Load the product master and aliases from step 2 into prod. Re-run the matching report there.
10. Import the real sales history into prod (step 4).
11. Review the rule parameters with the business owner (**Admin, Rule parameters**). Save them with the sign-off
    reference as the reason, even if the values do not change. This creates version 2, and the readiness check
    treats it as signed off.
12. Run the readiness check. Everything except `golive.reconciliation` and the MANUAL items must be PASS.

## T-2 weeks: parallel run

13. Run one full monthly cycle in PIFA **alongside** the current spreadsheet process. Every role works in both.
14. When both are final, export the spreadsheet's final allocation into the allocation template
    (`GET /api/v1/admin/go-live/templates/allocation`) and post it:
    `POST /api/v1/admin/go-live/cycles/{cycleId}/reconciliation`.
15. Explain or fix every line in `differences`, `onlyInFile` and `onlyInPifa`. Record each explanation in the
    reconciliation log (a spreadsheet kept with the go/no-go pack). Typical causes are a manual override in the old
    process, a different reserve, a cap-at-demand setting, or a product name that was mapped differently.

## T-1 week: go/no-go

16. Purge synthetic data in prod if any exists (**Admin, Synthetic data, Purge**). Readiness `synthetic.data` must be PASS.
17. Go/no-go meeting: walk through the readiness report and the MANUAL items (`docs/Phase.md` section 5).
    Record the decision.

## Go-live day

18. Upstream systems switch their SFTP drops to prod.
19. Watch the first drops: **Admin, Overview, Recent ingestion**, and the alerts in [../runbooks](../runbooks/README.md).
20. Create the first real cycle if it was not auto-created (**Admin, Cycles, Create cycle**).

## Rollback

If go-live must be undone within hypercare:

- **Application**: roll back to the previous image digest with `helm rollback pifa <revision>`. This is safe only when
  the release added no Flyway migration. Otherwise fix forward.
- **Data**: restore the database to the restore point taken just before go-live ([../runbooks/db-restore.md](../runbooks/db-restore.md)).
- **Business**: return to the spreadsheet process for the month. Users were trained on both.

## Hypercare (4 weeks)

- A daily 15-minute check-in with the Forecaster, the Planner and one Regional Manager.
- Each morning, check the readiness report, the ingestion jobs and the stage deadlines.
- Fix priority defects in a weekly patch release.
