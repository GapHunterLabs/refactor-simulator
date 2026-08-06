# Known Issues

Unlike nginx-companion's `KNOWN_ISSUES.md` (which documents an
unresolved bug), everything below is a **resolved design decision or
resolved bug backed by measured evidence**, kept here so future work on
this plugin doesn't have to re-derive it.

## `RenameProcessor.findUsages()` violates `@ApiStatus.OverrideOnly` — invisible everywhere except `verifyPlugin` (found and fixed, 2026-07-28)

`./gradlew verifyPlugin` (the strict local gate configured in
`build.gradle.kts` — see the "Isolated test runner" section below for
why this gate exists at all) failed against 5 of the 6 target IDE
versions, all with the identical violation:

```
Invocation of override-only method
com.intellij.refactoring.rename.RenameProcessor.findUsages() is invoked in
dev.gaphunter.refactorsimulator.refactor.RefactorSimulationRunner.simulateRename(...)
This method is marked with @org.jetbrains.annotations.ApiStatus.OverrideOnly
annotation, which indicates that the method must be only overridden but
not invoked by client code.
```

No symptom appeared anywhere else in the entire manual verification
cycle — the plugin loaded, every button worked, the diff was correct,
Apply to Disk saved correctly. `compileKotlin`, `test`, and every
`runIde` pass were all clean. Only `verifyPlugin`'s static bytecode
analysis of API contract annotations caught this — it is a real class of
bug this project's checklist had never exercised until this point.

**Root cause, confirmed directly in bytecode, not inferred from
documentation:** `javap -p -v` against `BaseRefactoringProcessor.class`
(extracted from a real IDE distribution already cached locally by
`verifyPlugin` itself) shows `findUsages` carrying
`Lorg/jetbrains/annotations/ApiStatus$OverrideOnly;` directly in the
constant pool. `RefactorSimulationRunner` instantiated `RenameProcessor`
directly and called `.findUsages()` on it from outside — exactly the
pattern the annotation exists to flag.

**First fix attempted, confirmed NOT to work:** introduced a subclass
(`SimulationFindUsagesProcessor`) whose only job was to call the
inherited `findUsages()` from *inside* its own method, on the
(reasonable-sounding) theory that calling an override-only method from
within a genuine override/subclass context satisfies the contract's
documented intent ("must be only overridden," and this *was* a
subclass). Re-ran `verifyPlugin`: **the exact same violation, against
all 6 IDEs**, now citing the subclass's method as the caller instead.
Confirmed via `WebSearch` against JetBrains's own platform forum
(`platform.jetbrains.com`, "Stricter plugin verification..." thread) —
there is a real, previously-reported case of the identical pattern
(`HaskellReformatAction.super$actionPerformed()` calling an
`OverrideOnly` method via `super`) also triggering this same verifier
rule. **The Plugin Verifier does pure bytecode call-site detection with
no inheritance-relationship analysis at all** — any `INVOKEVIRTUAL`/
`INVOKESPECIAL` targeting an `OverrideOnly`-annotated method is flagged,
full stop, regardless of whether the caller is a subclass calling
`super.foo()` from a real override or an unrelated external class. There
is no sanctioned workaround, suppression mechanism, or per-call-site
annotation to silence this for a legitimate subclass — confirmed by
searching for prior art on JetBrains's own forum, not assumed.

**Actual fix:** dropped `RenameProcessor` entirely for usage detection.
`ReferencesSearch.search(element)` (confirmed via `javap` against a real
platform jar to carry **zero** `@ApiStatus.*` annotations, on the class
or any of its methods) is the platform's genuinely public,
general-purpose "find every reference to this element" API — it doesn't
carry any of `RenameProcessor`'s rename-specific execution machinery,
because it was never meant to run a refactoring, only to search. Each
`PsiReference` result is wrapped in `UsageInfo(PsiReference)` (a plain
public constructor, also confirmed annotation-free), which kept every
downstream consumer — `UsageInfo.getSegment()`, file grouping,
`applyRenameToText()`, and even `RenameUtil.addConflictDescriptions()`
for conflict detection — completely unchanged. Only the *source* of the
`UsageInfo[]` array changed; its shape and everything built on top of it
did not.

**Re-verified end-to-end after the real fix, not just re-compiled:**
`./gradlew test` clean; a full manual `runIde` pass confirmed
`ReferencesSearch` finds the identical 3 references the old mechanism
found (including the Kotlin→Java cross-language call site in
`OrderCheckoutService.kt`), with the diff, Impact Summary, and
Validation Report all unchanged from before; `./gradlew verifyPlugin`
re-run against all 6 target IDEs: **`Compatible`, zero violations,
`BUILD SUCCESSFUL`** — the actual gate that caught this bug is also the
one that proved the fix.

**Lesson:** `@ApiStatus.OverrideOnly` (and by the same logic,
`@ApiStatus.Internal`/`@ApiStatus.Experimental`) should be read as "do
not call this at all as a plugin author," not "call it only from a
subclass" — the annotation's English description sounds like it permits
the latter, but the tool that enforces it does not implement that
distinction. When one of these annotations blocks a real use case, the
correct move is to find a genuinely different, unrestricted API for the
same underlying need (here: `ReferencesSearch` instead of
`RenameProcessor.findUsages()`), not to find a clever way to still call
the restricted method. **This class of bug has no signal at all in
`compileKotlin`, `test`, or `runIde`** — `verifyPlugin` is the only step
in this project's entire verification chain that would ever catch it,
which is why it's now listed as a mandatory step (not just a
pre-Marketplace formality) in this workspace's `AUTOMATION_PLAYBOOK.md`.

## "Apply to Disk" showed a success dialog but wrote nothing to disk (found and fixed, 2026-07-28)

The most serious bug found in manual `runIde` verification: clicking
"Apply to Disk" showed **"Refactor applied to disk."** unconditionally,
but a SHA-256 checksum of every affected file taken immediately before
and after the click came back byte-identical, every time. This is worse
than any prior bug in this file — a silent no-op that actively claims
success is more dangerous than a visible failure, since the user has no
reason to suspect anything is wrong.

**Root cause:** `ApplyToDiskAction.apply()` called
`RenameProcessor(...).run()` from *inside* an explicit
`WriteCommandAction.runWriteCommandAction(project, ..., { ... })` block.
`RenameProcessor.run()` (inherited from `BaseRefactoringProcessor`) is
the platform's own top-level entry point for executing a refactoring —
the exact method IntelliJ's built-in Rename action itself calls directly
from `actionPerformed`, with no outer write-command wrapper — and it
already opens its own write action and undo command internally.
Nesting it inside a second, manually-opened `WriteCommandAction`
produced no visible exception or log entry; the refactor's internal
`performRefactoring()` step simply never took effect on the real
project, while `Messages.showInfoMessage(...)` fired regardless,
immediately after, with no check of what actually happened inside the
write command.

**Fix:** call `RenameProcessor(...).run()` directly on the EDT, with no
outer `WriteCommandAction` wrapper — exactly how the platform's own
Rename refactoring invokes it. Verified: checksum of the real file
changed after Apply to Disk, and the changed content matched the
`AffectedFile.simulatedText` shown in the diff exactly.

**Lesson:** a platform API named `run()` on a class whose job is
literally "run this refactoring" is a strong signal it already manages
its own write-action/command lifecycle — wrapping it in another one is
not "extra safety," it's two lifecycle managers fighting over the same
write action, and the failure mode is silent rather than a thrown
exception. When calling a `BaseRefactoringProcessor` subclass's `run()`
directly (as opposed to only using `findUsages()`/`preprocessUsages()`
for detection, which this plugin also does elsewhere and does not wrap
in a write command either), don't add a manual `WriteCommandAction`
around it. This is also why a checksum-based "did the real file
actually change" check is part of this project's manual verification
plan (see the plan's §6.2) instead of trusting a success dialog alone —
the dialog was wrong every single time until this fix.

## "Apply to Disk" ran the real rename but never saved it to disk (found and fixed, 2026-07-28)

Found immediately after fixing the bug above, in the same verification
pass: with the `WriteCommandAction` nesting removed, `RenameProcessor
.run()` genuinely executed — the editor correctly showed the new name
live at every call site — but a checksum of the file *on disk* still
came back byte-identical to the pre-Apply baseline.

**Root cause:** `RenameProcessor.run()`, like every refactoring in the
platform, only mutates the in-memory `Document` backing each affected
`PsiFile`. It does not synchronously persist that `Document` to the
actual `VirtualFile` on disk — the platform normally relies on the
IDE's own save-on-focus-loss/periodic-autosave to flush unsaved
documents eventually. A `runIde` sandbox driven non-interactively
doesn't reliably hit that autosave trigger on the same timeline a
real, continuously-used IDE session would, so the gap between "rename
ran" and "file is actually saved" became directly observable.

**Fix:** `ApplyToDiskAction.apply()` now explicitly resolves each
`SimulationResult.affectedFiles[].filePath` back to its `VirtualFile`
(`LocalFileSystem.findFileByPath`) and `Document`
(`FileDocumentManager.getDocument`), then calls
`FileDocumentManager.saveDocument(document)` for each one right after
the rename completes. Deliberately scoped to only the files this
refactor touched — not `FileDocumentManager.saveAllDocuments()`, which
would also flush unrelated unsaved editor state open for other reasons
the user hasn't asked this plugin to touch.

**Lesson:** this plugin's entire value proposition is "what you saw in
the diff is exactly what changed" — silently depending on IDE autosave
timing to make that true is not good enough, even though it's exactly
what the platform's own Rename action itself relies on for interactive
use. Any action in this codebase that calls a `BaseRefactoringProcessor`
subclass's `run()` directly (not just Rename — the same gap will apply
to EXTRACT_VARIABLE/EXTRACT_FUNCTION once added) needs this same
explicit save step; it is not something `RenameProcessor` or the
refactoring framework does on your behalf.

## Plugin silently failed to load under Kotlin K2 mode (found and fixed, 2026-07-28)

The very first `runIde` manual verification pass showed the plugin never
loading — no "Loaded custom plugins" log entry at all for
`dev.gaphunter.refactorsimulator`, and no obvious error either, just
silence. The actual cause was buried in the log as:

```
Plugin 'Refactor Simulator' (dev.gaphunter.refactorsimulator) is incompatible with Kotlin in "K2" mode
```

Same gotcha already documented in `highlight-companion`'s own
`plugin.xml` comment: without declaring
`<supportsKotlinPluginMode supportsK1="true" supportsK2="true"/>` under
`<extensions defaultExtensionNs="org.jetbrains.kotlin">`, a plugin that
depends on `org.jetbrains.kotlin` fails to load entirely under K2 (the
default Kotlin plugin mode since 2024.x) — with a log line easy to miss
if you're scanning for "ERROR"/"FATAL" instead of reading the plugin
loading section directly. Fixed by adding the same declaration. Safe to
support both modes here: `RefactorSimulationRunner` only drives
`RenameProcessor`/`RenameUtil`/`findUsages()` — platform refactoring
APIs, never anything internal to the Kotlin plugin itself that would
differ between K1 and K2.

**Lesson for future plugins in this workspace:** grep the sandbox's
`idea.log` for the plugin's own id string (not just "ERROR"/"FATAL")
after every first `runIde` run against a new plugin — a plugin that
depends on `org.jetbrains.kotlin` can fail to load with no exception
raised anywhere, just a quiet compatibility message.

**Second bug found while fixing the first one:** the doc comment added
for the fix above used `--` as a stylistic dash separator inside an XML
`<!-- -->` comment, which XML forbids (a literal `--` inside a comment
is a parse error, distinct from `-->` closing it early). `patchPluginXml`
failed with `JDOMParseException: The string "--" is not permitted within
comments`. Same root-cause shape as the Kotlin nested-comment bug already
documented in `ansible-companion`'s own README (a punctuation character
that happens to collide with the comment syntax of the file it's
written in) — just XML's specific version of it instead of Kotlin's.
Fixed by rewording, not by escaping (XML comments have no escape
mechanism for `--`).

## Wrong rename target resolved from the editor context menu (found and fixed, 2026-07-28)

Manual `runIde` verification against `demo/`: right-clicking the method
name `calcSubtotal` (which the editor's own gutter confirmed had 3
usages) and choosing Simulate Refactor opened the dialog showing
**"Current name: BigDecimal"** — the method's return type, not the
method itself. The resulting simulation reported 4 files / 62 references
affected, consistent with having renamed the widely-used `BigDecimal`
type instead of the one method under the cursor.

**Root cause:** `SimulateRefactorAction` resolved its target from
`CommonDataKeys.PSI_ELEMENT` and walked up with `PsiTreeUtil
.getParentOfType(element, PsiNamedElement::class.java, strict = false)`.
`PSI_ELEMENT` does not reliably reflect "the element under the text
caret" — it can resolve through other UI context (breadcrumbs, project
tree selection, etc.), and even when it does come from the editor, the
element it returns isn't guaranteed to be inside the specific token the
user actually right-clicked on. Both `BigDecimal` (the return-type
reference) and `calcSubtotal` (the method) are reachable as
`PsiNamedElement` ancestors from the same click point, and the walk
picked the wrong one.

**Fix:** resolve the caret's real leaf element directly —
`psiFile.findElementAt(editor.caretModel.offset)` — then walk up
preferring the nearest `PsiNameIdentifierOwner` whose own
`nameIdentifier` *is* that exact leaf (confirms the caret is on the
symbol's own name, not merely somewhere inside its containing
declaration), falling back to the old nearest-named-ancestor behavior
only if the caret isn't on a name identifier at all (e.g. it's on a
return-type reference or a call target — a legitimate, different rename
target in that case).

**Lesson for future actions in this workspace:** never trust
`CommonDataKeys.PSI_ELEMENT` alone for "what did the user click on" in
an editor context-menu action — resolve from `CommonDataKeys.EDITOR` +
`CommonDataKeys.PSI_FILE` + the caret offset instead, and verify the
result actually matches the expected symbol with a real `runIde` click,
not just a compile-time type check (both `BigDecimal` and
`calcSubtotal` type-checked fine as `PsiNamedElement` — the bug was
semantic, not a compiler-catchable one).

## Show Diff / Apply to Disk / Discard buttons had no listeners at all (found and fixed, 2026-07-28)

Manual `runIde` verification: clicking "Show Diff" after a completed
simulation did nothing at all — no diff view opened, no error, no
visible change of any kind. Root cause: `ImpactPanel` created the three
`JButton`s, added them to the layout, enabled/disabled them based on
simulation state — but never called `addActionListener` on any of
them. `SimulationDiffPresenter`, `ApplyToDiskAction`, and
`DiscardAction` all existed and were unit-testable in isolation, but
nothing in the UI layer actually invoked them. Fixed by wiring all
three listeners in `ImpactPanel`'s `init` block, and by exposing
`SandboxSession` from `SimulateRefactorDialog` (it was building a
session internally but only ever returning the `SimulationResult`,
discarding the session `ApplyToDiskAction`/`DiscardAction` need to act
on the correct in-flight sandbox).

**Lesson:** a component compiling cleanly and passing its own unit
tests says nothing about whether it's actually *wired into* the UI that
should call it. This class of bug (dead buttons) has no compiler
signal at all — only clicking every button in a real `runIde` session
catches it.

## Simulated diff text was identical to the original (found and fixed, 2026-07-28)

Found while investigating why "Show Diff" (once wired up per the bug
above) still showed no visible change: `RefactorSimulationRunner` set
`simulatedText = file.text` — the *original* text, unmodified — as a
placeholder during initial implementation that was never finished. Every
simulation's `AffectedFile.simulatedText` was byte-identical to
`originalText`, so even a working diff viewer would have shown "no
differences" for every simulation ever run.

**Why the obvious fix (`RenameUtil.doRename(target, newName, usages,
project, listener)`) was rejected:** that method operates on the real
`PsiElement` tree the `UsageInfo` array points into — calling it would
mutate the actual project, which is exactly what Simulate must never do
before Apply to Disk.

**Actual fix:** `UsageInfo.getSegment()` (confirmed against platform
source, `platform/core-api/.../UsageInfo.java`) exposes the exact
absolute text-range each usage occupies in its containing file — the
same range data the platform's own preview/diff UI relies on internally.
`applyRenameToText()` groups usages by file, replaces each usage's
range with the new name directly on the file's plain text (a
`StringBuilder`, not PSI), processing ranges in descending offset order
so replacing a later range never invalidates the offsets of an
earlier one still pending. This is precise (only touches ranges
`findUsages()` already confirmed are real references — never a naive
`String.replace` that could also match a substring inside an unrelated
comment or string literal) and never constructs or mutates a second PSI
tree, keeping the "never touch anything until Apply" guarantee intact.

**Follow-up not yet done:** no automated test covers
`applyRenameToText()` yet — it needs `BasePlatformTestCase` (real PSI
fixtures to get real `UsageInfo`/`getSegment()` values from), not JUnit
plain. Add `RefactorSimulationRunnerTest` coverage for this specific
method before extending it to EXTRACT_VARIABLE/EXTRACT_FUNCTION.

**Post-fix false alarm, resolved by adding temporary diagnostic
logging (2026-07-28):** after the fix above was compiled and verified
in isolation, a live `runIde` re-test of the full flow still showed
"Contents are identical" in the diff for every affected file. This
looked like a second, deeper bug, but three rounds of `LOG.warn()`
instrumentation in `simulateRename()`/`applyRenameToText()`/
`SimulationDiffPresenter.showDiff()` proved every step of the pipeline
was working correctly — valid per-file `UsageInfo` groups, valid
`getSegment()` ranges (confirmed byte-for-byte against the real files
on disk), and the diff viewer faithfully rendering whatever
`AffectedFile.simulatedText` it was given. The actual log line that
resolved it: `newName param=calcSubtotal` — identical to
`target.name=calcSubtotal`. The dialog's "New name" field still held
its pre-filled default value (`JBTextField(target.name ?: "")`); the
manual test flow reached OK without the field's text actually being
edited to something different. Confirmed on the next attempt: typing
an actually-different name (`calcSubtotaldiag`) produced
`changed=true` for all 3 files with exactly the expected length delta.

**Lesson:** "the diff shows no differences" has two very different
causes that produce identical symptoms — (a) the simulation pipeline
is broken, or (b) the simulation pipeline is working exactly as
designed against a same-as-original new name. Before re-instrumenting
code that already passed a focused review, log the actual input value
(`newName`) alongside the pipeline's internal state — it's the
cheapest possible check and would have shortened this investigation
by two full `runIde` round-trips.

## Isolated test runner: temp-directory reuse is mandatory, not an optimization

**What was measured (2026-07-28, disposable spike, before
`IsolatedTestRunner` was written):**

A naive design — a fresh, UUID-named temp directory per "Simulate
Refactor" invocation — works correctly but is unacceptably slow in
practice:

- Fresh temp dir, cold Gradle daemon: **23.92s** (1st run) /
  **27.17s** (2nd run, different directory each time — every invocation
  paid the cold-start cost because Gradle's daemon fingerprinting is
  keyed by project path, and a new UUID path never matches a
  previously-warmed daemon).
- Same temp directory, reused across 3 successive invocations: **30.84s
  (1st, cold) → 1.07s (2nd) → 0.65s (3rd).**

**Conclusion, now the actual implementation:**
`IsolatedTestRunner` creates its temp directory **once per IDE
session/project** (`ensureSessionTempDir()`) and reuses it across every
subsequent simulation, overwriting only the affected files each time.
Getting this wrong — recreating the directory per invocation — would
make "Run Related Tests" feel broken even though it technically works,
since every click would cost ~25-30s instead of ~1s.

## Partial-module copy: confirmed safe, with one real-world caveat

A second spike confirmed that copying only the minimal module subset
(the module containing the affected code + modules that depend on it,
with a `settings.gradle.kts` generated to `include()` just those
modules) resolves correctly against a 3-module test project — two
related modules with a real dependency edge, one deliberately unrelated
module excluded from the copy. 4/4 tests passed, including one that
crossed the module boundary.

**Caveat, also measured:** if the temp directory doesn't already have a
cached Gradle distribution, the first run pays a one-time cost to
download it (**108s** measured, entirely due to `Downloading
gradle-9.5.1-bin.zip` — not the copy or test-run logic). Once the
distribution is cached globally (`~/.gradle/wrapper/dists`, shared
across projects by Gradle version, not per-project), this cost doesn't
repeat (**8.79s** on the second run). In practice this only affects a
user's very first simulation ever, on a machine that has never run this
exact Gradle version before — not a per-simulation cost.

## Dumb Mode (project indexing) disables Simulate, by design

If the project is indexing (`DumbService.isDumb(project) == true`),
`SimulateRefactorAction` disables itself entirely rather than running a
degraded simulation. `findUsages()` during indexing can return partial
results or throw `IndexNotReadyException`, and a silently-incomplete
impact report — "3 files affected" when the real number is higher
because some files weren't indexed yet — is worse than the action being
temporarily unavailable. This is expected behavior, not a bug: the
action reappears automatically once indexing finishes.

## MOVE refactoring is out of scope for v0.1 — not a gap, a deliberate boundary

Rename, Extract Variable, and Extract Function are the only supported
refactor kinds in v0.1 (`RefactorKind`). MOVE is excluded on purpose,
not deferred by oversight: moving a class/file across packages or
modules can rewrite imports in files that have no direct reference to
the moved symbol itself, and can cross module/classpath boundaries in
ways a single rename or extract never does. That's exactly the scenario
where the isolated-test-runner risk above (partial-module copy resolving
correctly) gets harder to reason about — a MOVE's "minimal module
subset" isn't as clearly bounded as a RENAME's. Revisit for v0.2 once
v0.1's simpler refactor kinds have real usage to learn from.

## Extract Variable's target resolution NPE'd on a real fixture, not a hand-built one (found and fixed, 2026-08-04)

**What happened:** `ExtractVariableTarget.resolve()` walks up from the
selection's start leaf, checking each ancestor's `textRange` against
the selection bounds, stopping when `candidate` becomes `null` (which
was assumed to happen right after the top-level `PsiFile`, the same
way `SimulateRefactorAction`'s existing caret-walk assumes an eventual
`null` parent). A real `BasePlatformTestCase` fixture testing "the
selection doesn't line up with any expression" NPE'd instead of
returning `null`.

**Root cause:** `PsiFile.getParent()` does **not** return `null` — it
returns the file's containing `PsiDirectory`. In a lightweight
`myFixture.configureByText` test fixture specifically, that directory's
own `getTextRange()` returns `null` (directories don't have "text" in
any meaningful sense). The walk kept going past the file, hit that
`null` range, and `range.getStartOffset()` NPE'd.

**Fix:** two changes, not one — (1) `range` is now null-checked before
being read at all (defensive, catches any other PSI element type with
a null range, not just `PsiDirectory`), and (2) the walk explicitly
stops the moment `candidate is PsiFile` — semantically correct anyway,
since nothing above a file boundary could ever meaningfully match a
text-offset-based selection.

**Verified:** `./gradlew test` green, including the exact "no matching
expression" case that originally NPE'd, plus a real leading+trailing
whitespace-trimming case.

**Lesson (generalizes the walk-up-the-tree pattern used twice now in
this plugin, and once in `ansible-companion`'s completion contributor
this same week):** never assume a PSI ancestor chain terminates at
`null` right after the element type you're thinking about (a file, a
statement, a sequence item) — verify what the *actual* next ancestor
is and whether it has the property you're about to read, against a
real fixture, not by reasoning about the platform's object model from
memory.

## Licensing: `PRODUCT_CODE` is a format-valid placeholder, not a real code

`future/v0.2-refactor-simulator-pro/licensing/RefactorSimulatorLicense.kt`
uses `PREFACTORSIM` as `PRODUCT_CODE` — this satisfies JetBrains's
format rules (max 15 chars, starts with `P`, all caps, no digits/symbols)
but is **not** an assigned code. Unlike Ansible Companion (already
assigned a real code by JetBrains after that plugin enrolled in
Marketplace Monetization), Refactor Simulator hasn't applied to
Monetization at all — see that folder's own README for why, and for
the checklist to follow when it's time to actually apply.
