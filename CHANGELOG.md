<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Refactor Simulator Changelog

## [Unreleased]

## [2026.2.2]

### Fixed

- After Discard or Apply to Disk, the Refactor Simulator window kept the
  previous simulation's checks and related tests on screen, and their
  "Will run" button asked for a license even with Pro active. The window
  now goes back to its empty state.

## [2026.2.1]

### Fixed

- **Running related tests in Gradle projects (Refactor Simulator Pro).**
  The run failed on Gradle projects imported the standard way, where
  IntelliJ creates one module per source set: the isolated copy was built
  from those modules and ended up with sources but no build scripts. The
  whole Gradle build is now mirrored, Gradle reports which project owns the
  test, and only the clicked test class runs.
- The test result dialog now says why a run couldn't start (Gradle's
  message and the end of its error output) and how many tests passed or
  which ones failed, instead of "couldn't match one back to" the file.
- **Rename preview.** The declaration itself now appears renamed in the
  preview and the diff, not only its call sites, and "Total changes" counts
  it. A spot the preview can't rewrite is reported as a conflict instead of
  being skipped silently.
- **Rename dialog.** The name field now has the focus with the current name
  selected, so typing replaces it (as in the IDE's own rename dialog), and
  the current name itself is rejected. Before, typed text could be lost and
  the simulation ran with the unchanged name, so the diff showed no
  differences.

## [2026.2.0]

### Added

- **Maven projects (Refactor Simulator Pro).** Running the related tests in
  the isolated sandbox used to work only for Gradle projects; a Maven
  project now works too. The affected module and the modules that depend
  on it are copied (keeping the directory layout and the parent poms they
  inherit from, with the root pom's `<modules>` trimmed to the copy), the
  simulated files are written over the copy, and Maven runs the tests
  there; the real project is never touched. Maven comes from
  `REFACTOR_SIMULATOR_MAVEN_HOME`, `MAVEN_HOME`/`M2_HOME`, a `mvn` on the
  PATH, or the Maven bundled with IntelliJ IDEA, so no global Maven is
  required. Each test class is reported as passed or failed from its
  Surefire report, with the first failure's message. A module outside the
  project directory is reported as unsupported instead of guessed at.
  A project with any Gradle build file keeps the Gradle path.

### Fixed

- Review/star CTA now links to this plugin's own Marketplace
  reviews page instead of the vendor's generic plugin list.

## [2026.1.2]

### Added

- Review/star CTA: after 5 refactors actually applied to disk via
  "Apply to Disk" (never counted for just Simulate, Discard, or
  opening the tool window -- previewing a refactor isn't the same
  signal of trust as applying it), a one-time notification asks
  whether to rate the plugin on Marketplace, with a permanent "Don't
  ask again" option.

## [2026.1.1]

### Fixed

- Tool window no longer shows the generic platform icon in the sidebar —
  the real Gap Hunter Labs mark is now declared via `icon=` on
  `<toolWindow>`.

## [2026.1.0]

### Changed

- Migrated plugin version from SemVer (`0.3.0`) to `YYYY.Minor.Patch`
  (`2026.1.0`), matching ansible-companion/api-security-companion/
  openapi-companion — required for `<product-descriptor>`'s
  `release-version` to satisfy Marketplace's "matching beginning" rule
  (`verifyPlugin` was rejecting every SemVer-based value tried). See
  `KNOWN_ISSUES.md` for the full trail.

### Fixed

- `RefactorSimulatorLicense.showRegisterDialog()` used
  `ActionUtil.performAction(AnAction, AnActionEvent)`, which
  `verifyPlugin` flagged as an unresolved method against the two oldest
  target IDEs (243, 251) despite its own comment claiming 243+ support.
  Replaced with `ActionUtil.invokeAction(...)`, the same fix already
  live in ansible-companion and api-security-companion — Compatible
  6/6 target IDEs after the change.

## [0.3.0]

### Added

- Refactor Simulator Pro (Freemium): "Will run" on a Related Test now
  actually runs it, isolated in a temp copy of the affected module and
  everything that depends on it (via a new `ModuleSourceRootResolver`),
  using the same Gradle Tooling API runner staged since 0.1.0. Requires
  a license — v0.1/0.2's free tier (Simulate, Diff, Impact Summary,
  Validation Report, related-test *listing*, Apply/Discard) is
  completely unaffected either way.

## [0.2.0]

### Added

- Extract Variable: select an expression (`a + b`) and Simulate Refactor
  now offers extracting it into a new `var`/`val` declared right before
  the enclosing statement, replacing the exact selected occurrence with
  the new name. Same "Simulate Refactor..." entry point as Rename — a
  non-empty selection routes here, a caret on a name still routes to
  Rename, unchanged. Apply to Disk writes the exact text the diff
  showed (no platform refactoring processor involved for this one,
  unlike Rename), so the real edit can never diverge from the preview.
- Works for both Java and Kotlin, same as Rename.

### Known gaps

- Extract Function is not implemented in this release — parameter and
  return-value inference for an arbitrary statement selection is a
  meaningfully harder, still-undesigned problem than Extract Variable's
  single-expression case, and this plugin's whole premise is accuracy
  over speed of shipping. Tracked for a future release, not silently
  dropped.
- Extract Variable doesn't offer "replace all identical occurrences" or
  detect a name collision with an existing local — only the exact
  selected occurrence is replaced. v1 scope cut, same spirit as Rename
  shipping before Extract in the first place.

## [0.1.1]

### Fixed

- `RefactorSimulationRunner` called `RenameProcessor.findUsages()` on an
  externally-held instance — a method the platform marks
  `@ApiStatus.OverrideOnly`. Replaced with `ReferencesSearch.search()`
  (a genuinely public, unrestricted API with no such contract), which
  finds the same references without violating platform API contracts.
  `verifyPlugin` now reports Compatible with zero violations across all
  6 target IDEs.
- Fixed "Apply to Disk" showing a success message while writing nothing
  to disk: `RenameProcessor(...).run()` was incorrectly nested inside a
  manual `WriteCommandAction`, silently neutralizing the refactor.
- Fixed "Apply to Disk" not persisting the rename to the real file on
  disk: the rename ran but only mutated the in-memory `Document`;
  affected documents are now explicitly saved after the refactor
  completes.
- Fixed Show Diff / Apply to Disk / Discard buttons having no
  `ActionListener` wired up at all.
- Fixed simulated diff text being identical to the original (a
  placeholder that was never finished) — the diff now shows the real
  post-rename text via `UsageInfo.getSegment()`.
- Fixed the editor context-menu action resolving the wrong rename
  target (e.g. a method's return type instead of the method itself).

## [0.1.0]

### Added

- Simulate Refactor: an isolated, in-memory sandbox preview of a rename
  refactor, computed with IntelliJ's own `RenameProcessor`/Find Usages —
  never a custom refactoring engine, and never touching the real project
  until Apply to Disk.
- Deterministic Impact Summary (files affected, total changes, imports,
  references) and Validation Report (PSI parsed, imports resolved,
  naming collisions, compilation conflicts, preview generated) — no AI,
  no confidence scores.
- Native side-by-side diff (Original read-only vs. Simulation Result)
  before any write happens.
- Related test file listing, reusing the same reference index the
  simulation already computed — free in v0.1; actually *running* those
  tests in an isolated sandbox is staged for a future paid tier (see
  `future/v0.2-refactor-simulator-pro/`).
- Apply to Disk / Discard as two explicit, separate actions — Discard
  leaves the project with zero footprint.

### Known gaps (tracked for a future release)

- Extract Variable and Extract Function aren't implemented yet — v0.1
  ships Rename only.
- MOVE refactoring isn't planned for v0.1 at all — see KNOWN_ISSUES.md.

[Unreleased]: https://github.com/GapHunterLabs/refactor-simulator/compare/2026.2.2...HEAD
[2026.2.2]: https://github.com/GapHunterLabs/refactor-simulator/compare/2026.2.1...2026.2.2
[2026.2.1]: https://github.com/GapHunterLabs/refactor-simulator/compare/2026.2.0...2026.2.1
[2026.2.0]: https://github.com/GapHunterLabs/refactor-simulator/compare/2026.1.2...2026.2.0
[2026.1.2]: https://github.com/GapHunterLabs/refactor-simulator/compare/2026.1.1...2026.1.2
[2026.1.1]: https://github.com/GapHunterLabs/refactor-simulator/compare/2026.1.0...2026.1.1
[2026.1.0]: https://github.com/GapHunterLabs/refactor-simulator/compare/0.3.0...2026.1.0
[0.3.0]: https://github.com/GapHunterLabs/refactor-simulator/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/GapHunterLabs/refactor-simulator/compare/0.1.1...0.2.0
[0.1.1]: https://github.com/GapHunterLabs/refactor-simulator/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/refactor-simulator/commits/0.1.0
