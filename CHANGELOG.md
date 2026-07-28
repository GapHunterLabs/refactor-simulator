<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Refactor Simulator Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/kendjm/refactor-simulator/commits/main
