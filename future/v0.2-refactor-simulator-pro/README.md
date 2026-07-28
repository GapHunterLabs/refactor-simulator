# v0.2 — Run Related Tests (on hold, paid tier)

**Business model (decided 2026-07-28, see the plan and CONSTITUTION.md
§4.1's Fase 4 entry):** v0.1 (Simulate, Diff, Impact Summary, Validation
Report, listing related tests, Apply/Discard) stays free forever. The
only gated capability is actually *running* those related tests in an
isolated sandbox — the most expensive feature to build and the most
differentiated versus anything else on Marketplace.

## Why this is staged here, not in `src/main`

Unlike Ansible Companion (already enrolled in Marketplace Monetization,
FREEMIUM, with a real assigned `[REDACTED-PRODUCT-CODE]` product code as of
2026-07-23), **Refactor Simulator has not applied to Monetization at
all.** Two reasons this waits:

1. `CONSTITUTION.md` §4.1 already has two active phases ahead of this
   one — Fase 2 (API Security Companion → Freemium, listing uploaded,
   in moderation as of 2026-07-28) and Fase 3 (Highlight Companion,
   evaluated only if real usage shows demand). Applying to Monetization
   for a third plugin in parallel competes for JetBrains moderation
   attention against work already in flight, and risks repeating the
   "checklist half-done" mistake already documented from Ansible's own
   history.
2. Refactor Simulator itself has no market validation the way the other
   8 plugins do — no competitor-with-real-complaints anchor (see the
   plan's evidence check, 2026-07-28: no paid competitor found doing
   this exact thing). Committing to a paid tier before the free tier has
   any real usage would be premature.

## What's built here

| File | What it does |
|---|---|
| `licensing/RefactorSimulatorLicense.kt` | Verbatim port of Ansible Companion's `CheckLicense.kt` (itself JetBrains's own reference implementation, `marketplace-makemecoffee-plugin`). `PRODUCT_CODE` is a **format-valid placeholder** (`PREFACTORSIM`), not a real assigned code — see the file's own doc comment. |

`testimpact/IsolatedTestRunner.kt` and `testimpact/RelatedTestFinder.kt`
already live in `src/main` (not staged here) — **listing** related test
files is free in v0.1 per the plan's gate design (§3.2), only
**executing** them is the Pro feature. The runner itself doesn't need a
license check gate wired into its own code; the gate lives at the call
site (`ui/ValidationPanel.kt`'s "Will run" button, disabled unless
`RefactorSimulatorLicense.isLicensed() == true`).

## What's still pending before reactivating this

1. Real usage/adoption signal on the free v0.1 tier — no reason to build
   pricing/trial infrastructure before knowing anyone wants this plugin
   at all.
2. Fases 2-3 of `CONSTITUTION.md` §4.1 resolved (API Security Companion's
   Freemium application approved/rejected; Highlight Companion evaluated).
3. Apply to Marketplace Monetization (FREEMIUM), get the real assigned
   product code, replace `PREFACTORSIM`.
4. Re-read JetBrains's Approval Guidelines current at that time (the
   2026-07-28 platform research this plan is based on may be out of date
   by then) — don't assume it's unchanged.
5. Wire the license check into `ValidationPanel`'s "Will run" button and
   `IsolatedTestRunner`'s entry point.
6. Add `<product-descriptor code="..." release-date="..." release-version="..." optional="true"/>`
   to `plugin.xml` — only in the same release that actually ships this,
   never before (same discipline already applied to Ansible Companion).
7. Full `verifyPlugin` (6 IDEs) + manual `runIde` pass before publishing.

## To reactivate

1. Confirm steps 1-4 above are actually done, not assumed.
2. Move `licensing/RefactorSimulatorLicense.kt` to
   `src/main/kotlin/dev/gaphunter/refactorsimulator/licensing/`.
3. Wire the license check into `ui/ValidationPanel.kt` and
   `testimpact/IsolatedTestRunner.kt`'s call site.
4. Add `<product-descriptor>` to `plugin.xml` with the real code.
5. `./gradlew test buildPlugin verifyPlugin`, then a manual `runIde` pass.
