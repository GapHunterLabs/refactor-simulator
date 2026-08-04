# Refactor Simulator

IntelliJ IDEA (Community + Ultimate) plugin. **Preview every refactoring
before committing it.** Simulates a rename in an isolated, in-memory
sandbox — using IntelliJ's own PSI, Find Usages, and refactoring
processors, never a custom engine — and shows a full impact report,
deterministic validation, and a native side-by-side diff before
anything touches a real file.

## Why it exists

Unlike the other 8 Gap Hunter Labs plugins, this one wasn't built from a
documented backlog of real competitor reviews. A quick evidence check
(2026-07-28) confirmed there's no direct competitor with real,
citable paid-tier complaints in this exact space — refactor previews
already exist natively in IntelliJ, and the closest paid product found
(Parasoft Jtest) is an unrelated enterprise test-impact-analysis suite
that runs tests *after* a change is applied, not a pre-commit
simulation. This is an original idea, not market-validated the way the
other 8 plugins were — treat it accordingly.

## Core principles

- **Never modify project files automatically.** Every filesystem write
  originates from an explicit user action (Apply to Disk), never as a
  side effect of Simulate.
- **Deterministic simulation, no AI.** No confidence scores, no
  probability metrics, no heuristics pretending to be certainty. Every
  number in the Impact Summary and every check in the Validation Report
  comes directly from PSI, Find Usages, and IntelliJ's own refactoring
  APIs — the same real data structures the platform's own preview
  dialog is built on, just exposed as a dedicated workspace instead of
  a one-shot dialog.
- **The original project stays untouched until Apply to Disk.** The
  simulation runs against an in-memory PSI copy
  (`PsiFileFactory.createFileFromText`, `eventSystemEnabled = false`) —
  the real `VirtualFile` and `Document` are never written to during
  Simulate, Show Diff, or a Discard.
- **Every simulation is reproducible.** Running the same simulation
  twice without touching the project produces identical results — no
  hidden state, no caching that silently goes stale.

## What it checks (Validation Report)

Every check is a real, verifiable platform fact, never a guess:

- PSI parsed successfully
- Imports resolved
- Naming collisions (via the same conflict detection IntelliJ's own
  rename dialog uses — `RenameUtil.addConflictDescriptions` — computed
  without ever popping the platform's native conflicts dialog on top of
  the plugin's own UI)
- Compilation conflicts
- Preview generated

## Usage

**Rename:** right-click a symbol → **Simulate Refactor...** (or find it
under the **Refactor** menu). Enter the new name — invalid identifiers
and reserved keywords are rejected before simulation even runs, via
the same `LanguageNamesValidation` the platform's own rename dialog
uses.

**Extract Variable:** select an expression in the editor first, then
**Simulate Refactor...** — the same entry point detects the selection
and offers extracting it into a new `var`/`val` instead. Works for
both Java and Kotlin.

The **Refactor Simulator** tool window (right-hand side) shows:

- A green status banner confirming no changes have been written to disk.
- **Impact Summary**: files affected, total changes, imports, references.
- **Validation Report**: the deterministic checks above.
- **Related Tests**: every test file that references the affected code
  (always visible, computed for free from the same reference index the
  simulation already built).
- **Show Diff / Apply to Disk / Discard.**

**Free in v0.1:** simulation, the full Impact Summary, the Validation
Report, the native diff, listing related test files, and Apply/Discard
— all of it, no paywall on any of the above.

**Staged for a future paid tier, not yet available:** actually
*executing* the related tests in an isolated sandbox (rather than just
listing them). The code for this exists and is verified working (see
`future/v0.2-refactor-simulator-pro/`), but it isn't wired into this
release — Refactor Simulator hasn't applied to JetBrains Marketplace
Monetization yet, and won't until the free tier has real usage and two
other plugins already ahead in that queue are resolved. See that
folder's own README for the full reasoning.

## Why built this way

- **The sandbox stops at `findUsages()` + conflict detection, never
  reimplements the refactoring engine.** `RefactorSimulationRunner`
  drives the platform's real `RenameProcessor` up to the exact point
  its own native preview dialog is built on, and never calls
  `performRefactoring()` against the real project — that only happens
  from `ApplyToDiskAction`, after explicit user confirmation.
- **Conflict detection without the native dialog.** `RenameProcessor
  .preprocessUsages()` pops IntelliJ's own conflicts dialog whenever
  conflicts exist outside of unit-test mode — which would have shown a
  second, native dialog stacked on top of this plugin's own simulation
  UI. Instead, `RefactorSimulationRunner` calls
  `RenameUtil.addConflictDescriptions()` directly (the same call that
  dialog is built from) to get the same conflict data without ever
  triggering the dialog.
- **Isolated test execution, confirmed by a real spike before writing
  the class.** Before `IsolatedTestRunner` was written, a disposable
  spike measured the real cost of copying a project subset to a temp
  directory and running `test` via the Gradle Tooling API: ~24-30s on a
  cold Gradle daemon, but **under 1.1s once the same temp directory is
  reused across invocations** — the single highest-leverage design
  decision in that class. See `KNOWN_ISSUES.md` for the full numbers.
- **`DumbService.isDumb()` gates the action, not just a "try and catch
  the exception" approach.** `findUsages()` during project indexing can
  return partial results or throw `IndexNotReadyException` — a
  silently-incomplete impact report is worse than declining the action
  for a few seconds.

## Enterprise / Team Licensing

Need enterprise features, custom refactoring rules, or team licensing?
Contact us at **kennyj.diazm@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

`demo/` is a real, two-module Java + Kotlin project
(`acmecorp-order-management`: `order-core` pricing logic, `order-api`
checkout service depending on it) — used for manual `runIde`
verification, not "Hello World."

## License

Apache-2.0. See `LICENSE`.
