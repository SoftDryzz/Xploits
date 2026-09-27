# Conventions

How work is done in this repo, and why. Almost every one of these rules comes from a concrete failure
that cost a review round.

---

## Language

**The repo is in English.** Code, comments, javadoc, commit messages and documentation are written in
English. See [Versioning](VERSIONING.md) for the commit format.

- **Player text lives only in the catalogs**, `xploits/lang/es.lang` and `xploits/lang/en.lang`, and
  every key exists in both. The code never writes a sentence for the player: it names a key.
- **`README.md` (English) and `README.es.md` (Spanish) change together.** A change to one is made to
  the other in the same commit, section by section.
- **Specs and plans live in `docs/superpowers/` locally and are never committed.** The folder is
  ignored; code comments may cite a spec by name, for whoever has the local copy.

**Meteor setting ids are English too** (`waypoint-margin`, `lane-width`, `spiral-radius`), in both
languages. They are part of Meteor's interface, which the player sees next to the settings of every
other module; they do not change with the chosen language.

**And a rejection message that names a setting has to name it exactly as it appears in the
interface**, in both catalogs. If they diverge, the message sends the player looking for something that
does not exist under that name.

---

## Verify before claiming

**Never claim what a Meteor, Minecraft or Baritone API does without having read it.** Meteor's sources
jar is in the Gradle cache; the remapped Minecraft one, in the project's; Baritone has to be
disassembled with `javap` because it ships obfuscated.

This rule exists because **two false things about Meteor were taken as true** by reasoning instead of
reading. And the third time it was actually read, this turned up: `blocksMovement()` considers a slab
solid, so standing on a slab classified the enemy as buried and turned off the crystal aura in the
middle of the fight.

**Local design notes carry a "Verified API facts" section** with the fact and its consequence. If a
fact is not there, it is not verified.

---

## Pure core and thin adapter

The rule is in [Architecture](architecture.md). In practice:

- If you find yourself writing a decision in the adapter, **it goes to the core**.
- The core does not import `net.minecraft` or `meteordevelopment`. Not one.
- The adapter **measures and executes**; it does not decide.

It has been broken three times and all three cost a round.

---

## Tests: mutation is not optional

**A green test only proves that the test passes.**

Before accepting a protection, **break what it protects on purpose and check that some test goes
red**. If it survives, that test protects nothing.

This is not theory. In this repo:

- A planner test spent **two rounds green** protecting code that could break without it noticing,
  because another fix in the same commit covered the gap.
- The test that checked the area coverage **asserted the right property** and still did not check it:
  the tolerance matched the spacing between lanes, so it only constrained that.
- A mutation passed green because the `sed` never got applied. **Check that you really mutated**
  before accepting the result.

**Compare against values worked out by hand**, not against the same formula the code uses: if they
share the error, the test passes anyway.

---

## In-game bench

A permanent bench of scripted fights, separate from the unit tests: `src/gametest/`, run with
`./gradlew runClientGameTest`. It never ships (Fabric API is on the gametest classpath only, so the
shipped jar does not change), and `./gradlew build` never opens a window or runs it.

- **CHECK** scenarios assert one behaviour and block a release on FAIL.
- **MEASURE** scenarios run a module under test for a fixed time against a scripted sparring partner and
  report numbers (damage dealt, pops, placements per second, and the rest of the metric table) instead of
  a pass or fail. Judging those numbers against the baseline, or against what a change should improve, is
  for whoever reads the report.

`./gradlew runClientGameTest` opens a Minecraft window. It wipes `build/bench` first, all but the Meteor
cache (below), so every report in there is from that run alone; copy a report out of `build/bench` if you
want to keep it, because the next run erases it. There are two profiles:

- **The everyday run** (the default, no flag) plays every CHECK, every Meteor `ca-*` MEASURE (served from
  the cache while it is valid), `defense-attacker`, and crystal-aura++ at Balanced only (`capp-balanced-*`).
  The `capp-*` scenarios at Safe and the `capp-aggressive-*` ones are experimental levels: the everyday run
  lists them as SKIPPED, which is not a failure. With the cache warm it takes about 20 minutes.
- **The full run**, `-Pbench.full`, plays every scenario, about an hour. **A release runs
  `./gradlew runClientGameTest -Pbench.full -Pbench.fresh`**: the full run, with Meteor measured again.
  The everyday run never measures Safe or Aggressive, and `benchVerify` says so with the line
  `bench: everyday run — not valid for a release (use -Pbench.full)`. A full run that served any `ca-*`
  from the cache fails `benchVerify` with
  `bench: full run with cached Meteor results — not valid for a release (add -Pbench.fresh)`: the cache's key cannot see everything that could change Meteor's numbers (below),
  and Meteor's runs are not perfectly identical either (placements per second and the first pop's time can
  move a little), so a release never rests on one frozen sample of them.
- `-Pbench.only=<name,name>` runs only the named scenarios, whichever profile was asked for, for a quick
  check while working on one of them. A partial run cannot prove the scenarios it skipped still pass
  either, and the report itself records which names ran, so it is never mistaken for a full one.
- `-Pbench.fresh` measures every `ca-*` again instead of serving it from the cache (and caches the new
  results).
- `-Pbench.updateBaseline` merges every DONE MEASURE's medians from the run into the committed
  `bench/baseline.json`; a scenario that did not run, or did not finish DONE, keeps its existing entry.

**The Meteor cache.** Meteor's crystal-aura gives the same numbers run after run, so a `ca-*` MEASURE that
finishes DONE keeps its runs in `build/bench/meteor-cache/<scenario>.json` (the build folder: never
committed). Its key is a SHA-256 over what can change Meteor's crystal-aura in the bench, each file by its
sorted path with its bytes:

- the Meteor jar the game loaded, and the Minecraft version;
- every file under `src/gametest/`: the bench itself, its scenarios, arenas and scripts;
- the build files `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` and
  `gradle/libs.versions.toml`, which pin the Fabric API, loader, yarn, Loom, Meteor and Minecraft versions;
- every mixin config under `src/main/resources` (a JSON with a `package` and a mixin list), and every Java
  file under each config's `package` in `src/main/java`, sub-packages included, found from the configs and
  never hard-coded: the addon's mixins reach into Minecraft itself, so into what Meteor sees;
- the scenario's name.

The rest of `src/main` is left out on purpose: crystal-aura++ is off in every `ca-*` run, and hashing it
would re-measure Meteor on every crystal-aura++ change, which would defeat the cache. What it does leave out
(an addon module that stays on during a `ca-*` run, say) is what the release's `-Pbench.fresh` is for.

The next run whose key matches does not play that scenario: it reports it DONE with the cached runs, marked
`"cached": true` with the date it was measured in the JSON report, `cached, measured <date>` in the
markdown, `(cached)` in the client log and `(n cached)` in the summary lines. A change to any input of the
key, a missing file or one that cannot be read is a miss: the scenario is measured again and its file
overwritten. Cached runs count exactly like measured ones for the verdicts, the baseline and the
regressions. CHECKs are never cached, not even `capp-budget-off-parity`, which plays Meteor's aura inside.

**The verdict is `runClientGameTest`'s Gradle result, not the client's exit code.** A client that stops
mid-bench (a closed window, a crash) can still exit 0, so the report is what settles it: `benchVerify`
(which always follows `runClientGameTest`) reads it and prints a `bench: …` summary line. `BUILD
SUCCESSFUL` plus a `bench:` line that says `full run` with no `cached` count means the release gate passed
(a line that says `everyday run` is the everyday profile, and one that says `only …` a partial run).
`benchVerify` fails the build on:

- a missing report, or a report that lists no scenario;
- any scenario that is PENDING, FAIL or ERROR (SKIPPED, a scenario the everyday run does not play, is not
  a failure);
- hygiene not clean (a report line looked like a position);
- a full run that served any `ca-*` from the Meteor cache (release rule `ReleaseGate`, in the bench's pure
  core with its unit tests; `benchVerify` runs its compiled class).

**PENDING** means the client stopped before that scenario finished. Rerun the bench; never release on a
PENDING report. If the window hangs, with no progress in the client log for minutes, kill the Java
process by hand — the same PENDING result follows.

Regressions against the baseline are counted in the summary line but do not fail the build by themselves:
show them to whoever is about to release, before tagging.

**crystal-aura++ against Meteor's crystal-aura.** Each `capp-X` MEASURE is judged against its `ca-X` twin
from the same run (ACCEPT, REJECT, INCOMPLETE, or NOT_APPLICABLE when neither aura placed a crystal in any
run, as against the defender). Still and circler also run as `-regen` pairs, with natural health
regeneration on, closer to a real fight; every other scenario runs without it. The `capp-X` scenarios run
crystal-aura++ at Safe (the `risk` setting's own default is Balanced); `capp-balanced-X` and
`capp-aggressive-X`, for X in still, circler, still-regen and circler-regen, run it at Balanced and
Aggressive against the same `ca-X`.
Four fight situations run last, only with healing on, at every level: `above` (the enemy walks on a
platform 3 blocks above our feet), `below` (in a pit 3 blocks below), `approach` (it walks at us from 8
blocks to 3 and back, with fixed irregular pauses) and `strafe` (it zig-zags 3 blocks to each side, 5 blocks
out); each as `ca-<s>-regen`, `capp-<s>-regen`, `capp-balanced-<s>-regen` and `capp-aggressive-<s>-regen`.
`benchVerify` prints the verdicts as a second `bench: capp: …` line, then one strict recommendation per
level: `bench: capp Safe: YES/NO (n of m applicable)`, then the same for Balanced and Aggressive (with
`; k not applicable` when a pair of that level was not applicable, as the defender pair at Safe). A level
says YES only when every applicable pair of that level is ACCEPT; each level counts only its own pairs. A
pair that did not run counts as INCOMPLETE, so only a full run can say YES about every level. The everyday
run prints only the Balanced line, over all of Balanced's pairs, then `bench: capp Safe, Aggressive: not
measured in this run (use -Pbench.full)`. None of these lines ever fails the build. The report's markdown
also has a risk table: for each `ca-X`, the median damage dealt and min health of Meteor's aura and of
crystal-aura++ at each level, side by side (a dash for a level that did not run).

The CHECK `capp-budget-off-parity` is different: it does block a release. It runs Meteor's crystal-aura and
crystal-aura++ with `self-budget` off in turns on the still arena, 3 runs each, and fails when a median of
damage dealt, sparring pops, self damage, min health or placements per second differs by more than the
metric's noise floor or 15 % of Meteor's, whichever is larger. Without the budget, crystal-aura++ must be
Meteor's aura, so any difference the `capp` pairs show comes from the budget alone. It also checks that the
module shows exactly the settings its unit tests cover (`CrystalSetting`).

`./gradlew benchVerify` alone re-checks an existing `build/bench` report without opening a window, for
example right after a run that already finished.

---

## Reject rather than degrade

When something cannot be done as asked, **it is rejected, saying which setting to change and to what
value**. Something similar is never done silently.

The literal doctrine, written in the code: *"what still works when capped is capped; what does not
is rejected"*.

The case that justifies it: a decoy pattern that did not fit the travel distance returned the straight
route. The player thought it was weaving, adjusted their behavior to a protection that did not exist,
and flew a straight line to their base. **Five different doors led to that same failure** and they
were closed one by one.

**A rejection that does not say how to get unstuck is almost as bad as silence.** The message names
the setting, its current value and what to set it to.

---

## Bias matters: ask which way it errs

When you choose a rounding, a threshold or a default value, ask yourself **what happens if you are
wrong in each direction**. It is almost never symmetric.

- Making a lane too narrow costs flight. Making it too wide leaves strips unseen **marked as combed**.
  Round down.
- Underestimating the distance left looks prudent and is the opposite: it makes the module say "your
  fireworks will last" when they will not.
- Leaving the crystal aura on too long costs a few crystals. Turning it off too early costs the fight.

---

## A failure can never look like a normal result

This is the principle that governs everything else.

- A measurement that does not exist **throws**; it does not return zero.
- A badly covered area **warns loudly with a toast**; it does not come out in an `info` that can be
  turned off.
- A method called "total" **includes everything** it says it includes. That exact failure was fixed
  **twice** in the same branch, one level higher each time.
- "I don't know" is a reason for caution, never for carrying on quietly.

---

## Commits

**No attribution line of any kind.** No `Co-Authored-By`, no `Generated with`, no mention of any tool.
The commit ends at its last line of text.

**The message explains the why, not the what.** The diff already says what changed. What cannot be
reconstructed later is why this was chosen and not the other thing.

`git add` by file name, never `git add -A`.

---

## Workflow

1. **Design first**, in `docs/superpowers/specs/` (local, never committed), with the API facts
   verified.
2. **Plan** in `docs/superpowers/plans/` (local too), split into tasks with their deliverable and
   their tests.
3. **Implementation** task by task, each with its review before moving to the next.
4. **Whole-branch review** at the end, which is the only one that sees the **gaps between tasks**.

Step 4 is not ceremony. On the last branch it found two critical failures that the six per-task
reviews could not see, because each one looked at its own piece and in its own piece nothing was
missing.

---

## What is not done

**What already works is not reimplemented.** Before building something, check whether one of the
installed mods already does it. On the last branch we came close to rewriting a base detector, a
coverage map and a chunk logger — all three already existed, installed and better.

Half of a module's design can be **the list of what it does not do and who does it instead**.
