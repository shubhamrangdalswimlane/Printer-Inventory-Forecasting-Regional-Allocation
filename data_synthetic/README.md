# Synthetic printer data (January 2027 cycle)

Test files for PIFA, covering 30 printer models. Each model has one stock scenario, so every allocation case appears:

| Scenario | Models | Inventory vs forecast | Expected in the allocation |
|---|---|---|---|
| SHORTAGE_SEVERE | 5 | 30–50% | Constrained: 25% baseline split, regions short |
| SHORTAGE_MILD | 5 | 80–95% | Constrained: small shortfall |
| STOCK_OUT | 2 | 0 | Constrained: everyone gets 0 |
| PDF_EXAMPLE | 1 | 100 units for demand 30/30/30/30 | 25/25/25/25 (the one-pager's "Printer Model A") |
| EXACT | 6 | 100% | Sufficient: each region gets exactly its demand |
| SURPLUS_MILD | 6 | 110–130% | Sufficient: some stock left over |
| SURPLUS_LARGE | 4 | 200–300% | Sufficient: a lot of stock left over |
| NO_DEMAND | 1 | stock, but forecast 0 | Sufficient: all stock left over |

In total, 13 models have inventory below the forecast, 6 match it exactly, and 11 have inventory above it.

## Files

| File | What it is | Where it goes |
|---|---|---|
| `PRODUCTS_master.xlsx` | The 30 model names (sheet `Products`) | **Admin → Master data → Import Excel**. Upload this first, so the names are known. |
| `FORECAST_2027-01.xlsx` | Demand per model and region (sheet `Forecast`) | **Forecaster → Upload**, or the SFTP folder `/inbound/forecast`. This creates the 2027-01 cycle. |
| `INVENTORY_2026-10-21.xlsx` | Available stock per model, as of 2026-10-21, for cycle 2027-01 (sheet `Inventory`) | **Planner → Upload**, or the SFTP folder `/inbound/inventory` |
| `SCENARIO_SUMMARY.xlsx` | Forecast, inventory, gap, coverage and expected result per model, colour-coded (red = short, green = exact, blue = surplus) | For people only. Do not upload it. |
| `generate.py` | The script that made the files. It uses a fixed seed, so re-running gives the same numbers. | `python generate.py` (needs `openpyxl`) |

## Notes

- The forecast upload shows warnings for products that are in the master but not in the file (for example older synthetic products). These are warnings only, and those products are treated as 0.
- `DesignJet T650 Plotter` has a forecast of 0 in every region. That's intentional: it's the NO_DEMAND case.
