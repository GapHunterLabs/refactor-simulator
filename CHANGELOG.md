<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Refactor Simulator Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/GapHunterLabs/refactor-simulator/compare/0.1.1...HEAD
[0.1.1]: https://github.com/GapHunterLabs/refactor-simulator/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/refactor-simulator/commits/0.1.0
