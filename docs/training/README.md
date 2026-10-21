# PIFA quick guides

One page per role. Each covers what you do each month, in order, plus the rules that most often surprise new users.

| Guide | For |
|---|---|
| [forecaster.md](forecaster.md) | Forecaster |
| [regional-manager.md](regional-manager.md) | Regional Managers (North, East, West, South) |
| [planner.md](planner.md) | Planner |
| [admin.md](admin.md) | Admin and support |

## The monthly cycle in one picture

```
FORECAST_DRAFT --publish--> REGIONAL_REVIEW --all regions submit--> PLANNING --approve--> REGIONAL_FINALIZATION --all finalize--> FINAL
  (Forecaster)               (Regional Managers revise)            (Planner allocates)    (RMs transfer and finalize)          (locked)
```

## Things every user should know

- **Sign in** with your company account. Your role and regions come from Keycloak, so ask the Admin if they are wrong.
- **Every change is recorded**: who made it, when, the old value, the new value and the reason.
- **Someone else changed this row?** Two people edited the same line. Reload the grid and apply your change again.
- **Notifications**: the bell shows what needs your action. The same messages also arrive by e-mail.
- **Errors show a request id.** Include it when you contact support, so they can find exactly what happened.
