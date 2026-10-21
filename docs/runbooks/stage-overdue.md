# Cycle stage overdue (PifaCycleStageOverdue)

## What it means
`pifa_cycle_stage_overdue` > 0: a planning cycle is past its stage deadline (for example regions have not
finalized by the finalize deadline).

## Impact
A business delay, not a technical fault. The plan for the month may be late.

## Diagnose
**Planner, Finalization monitor** shows which regions are open, pending transfers, and allocation errors.

## Fix
- The Planner chases the regional owners (the system also sends deadline reminders).
- Blocking transfers can be cancelled by their creator, or by the Planner on reopen.
- If the deadline itself was wrong, the Planner moves it on the next transition.

## Verify
The cycle reaches FINAL or the deadline is moved; the gauge returns to 0.

## Escalate
The business process owner (Supply Planning lead).
