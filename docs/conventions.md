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

**Every MEASURE run.** After T0 and after every tick the server tops the end-crystal stack in hotbar slot 0
back up to `CrystalRefill.FULL` (64), for whichever aura is under test, so a run never runs out of crystals
and ammunition never decides a number (R3-7); the crystals a run's refills added are logged once per run,
never a metric.

**The settle cut (R3-6).** A run can also end before its nominal 30 s: with natural regeneration off (the
scenario's setting and the world's `natural_health_regeneration` rule, read at T0, must agree) and a static
sparring script, once `Settle.SETTLE_TICKS` (60 ticks, 3 s) pass in a row with no end crystal in the world, no
placement or attack packet sent, neither player's health plus absorption changing, no sparring pop, and the
aura under test's own longest timer that could still fire under that bound — Meteor's every setting is reset
to default for the run (only `pause-on-lag` turned off), so its delays and internal timers are fixed at 20
ticks or fewer and the bench feeds 0 for it; crystal-aura++'s is its pending placement's lifetime, which
follows the ping and is fed every tick — nothing left in the run can change, so it ends there with the
numbers it would have had at 30 s, in one log line. A `-regen` scenario (natural regeneration on) can never settle this
way, since it fails the regeneration-off condition alone. `-Pbench.verifySettle` does not cut a settled run
short: it snapshots the metrics at the first settled tick, runs on to the nominal length anyway, and fails
the scenario if the final metrics differ from that snapshot or the run stops looking settled before the end.

**Fight mode (opt-in, task A1).** A scenario can opt into a more realistic crystal fight instead of the
standard loadout: `Arena.fightLoadout()` gives our player netherite Protection IV on the helmet, chestplate
and boots plus Blast Protection IV on the leggings (unbreakable, like the standard loadout; armour breaking
stays out of scope for the bench either way) and `Arena.FIGHT_TOTEMS` (8) totems in total, offhand plus
spare; `Sparring.spawn(..., true)` wears the same armour and gives the sparring the same 8 totems, instead
of refilling its offhand forever — it dies for real once it takes a lethal hit with none left. In fight
mode our own death and the sparring's end the run with a `result` (1 win, -1 loss, 0 draw at the time
limit) instead of ERROR, and add `pops_dealt`, `pops_taken`, `net_pops`, `first_pop_taken_s` and
`min_health_after_own_hit` (our health plus absorption right after each hit from one of our own crystals,
the lowest over the run — what the self-budget's reserve promises even while the opponent is also hitting
us) to the metric table alongside the usual `damage_dealt`, `self_damage`, `min_health` and
`placements_per_s`; these are new `Metrics` keys that only a fight-mode run puts, and the report and
`Acceptance` already tolerate a metric being absent (old scenarios) or present (new) since both read the
metric table by name. 32 ticks after each totem pop of either player — the 1.6 s an enchanted golden apple
takes to eat — the bench applies its effects server-side: Absorption IV, Regeneration II and Resistance I,
identical for both players and both auras (documented simplification: it does not tie up the eater's hands
for those ticks, and it leaves out Fire Resistance, which a real notch apple also gives but which never
matters with no fire in these fights). A pop while effects are already pending re-arms the delay from
itself; a death cancels whatever is pending. A fight-mode run is never static by definition (either player
could still act, or die, until the time limit), so it never takes the settle cut above. The standard
loadout, the sparring's forever-refilling totem and today's death-is-ERROR handling are all still the
default: a scenario that does not opt in keeps 0.7.0's behaviour and numbers exactly.

**The real fights and their rules (0.7.1).** Five fight-mode situations run last, each judged as a
`ca-<s>` / `capp-<s>` pair like the rest: `exchange` and `hole-standoff` (an opponent that attacks back),
`city` (an opponent that mines the wall of our hole and places a crystal in the gap),
and `near-death` and `near-death-totem` (a stationary, non-attacking opponent that starts low, without and
with a totem; a warm-up first lets crystal-aura++ confirm his health with a hit of its own, as it would in
a real fight, and the opponent's hands are shown to the client, which the stepped sparring does not do on
its own). They add the finishing-blow metrics, each present only in a run where it happened:
`finishing_blows` (offense: our marked crystals that exploded by our own attack, whether or not they hurt
us), and the safety counters, matched to our own hit events one by one: `finishing_hits`,
`finishing_pops`, `min_health_after_finishing_hit`, `totems_at_finishing_hit_min`,
`finishing_pop_grade_violations` and `died_with_totem`. Two of the fight rules judge safety: **F3**, every
ordinary own hit leaves us at or above the level's reserve, and **F4**, no run of ours ends dead while it
carried a totem, no finishing hit happens with fewer than 2 totems carried, we are alive after every
finishing hit, and no pop-grade blow (the target holds a totem) leaves us below 2 or pops us. The
offense rules are **F1**, the fight's `result` is no worse than Meteor's, and **F2**, `net_pops` no more
than 1 below it. In every scenario, ordinary hits are judged by **S1-S3** (S3 not applicable while Meteor
never went below the reserve) and finishing hits by **S4**: never popping us, never below 2, never with
fewer than 2 totems carried. A finishing hit is not an ordinary one: it never counts toward S1-S3 or F3.
Before the fights, `cover` (`ca-cover`, `capp-cover` and one per level) puts a low ceiling and a pillar
between us and the crystals, so the self-budget's exposure reading meets real cover.

`./gradlew runClientGameTest` opens a Minecraft window. It wipes `build/bench` first, all but the Meteor
cache (below), so every report in there is from that run alone; copy a report out of `build/bench` if you
want to keep it, because the next run erases it. There are two profiles:

- **The everyday run** (the default, no flag) plays every CHECK, every Meteor `ca-*` MEASURE (served from
  the cache while it is valid), `defense-attacker`, and crystal-aura++ at Balanced only (`capp-balanced-*`).
  The `capp-*` scenarios at Safe and the `capp-aggressive-*` ones are experimental levels: the everyday run
  lists them as SKIPPED, which is not a failure. With the cache warm it should take about 20 minutes (an
  estimate from the scenarios' lengths, not yet timed end to end).
- **The full run**, `-Pbench.full`, plays every scenario, about an hour. **A release runs
  `./gradlew runClientGameTest -Pbench.full -Pbench.fresh`**: the full run, with Meteor measured again.
  The everyday run never measures Safe or Aggressive, and `benchVerify` says so with the line
  `bench: everyday run — not valid for a release (use -Pbench.full)`. A full run that served any `ca-*`
  from the cache fails `benchVerify` with
  `bench: full run with cached Meteor results — not valid for a release (add -Pbench.fresh)`: the cache's
  key cannot see everything that could change Meteor's numbers (below), and Meteor's runs are not perfectly
  identical either (placements per second and the first pop's time can move a little), so a release never
  rests on one frozen sample of them.
- `-Pbench.only=<name,name>` runs only the named scenarios, whichever profile was asked for, for a quick
  check while working on one of them. A partial run cannot prove the scenarios it skipped still pass
  either, and the report itself records which names ran, so it is never mistaken for a full one.
- `-Pbench.fresh` measures every `ca-*` again instead of serving it from the cache (and caches the new
  results). `-Pbench.verifySettle` does the same: it must verify Meteor's settle shortcut too, which a
  served scenario never runs.
- `-Pbench.updateBaseline` merges every DONE MEASURE's medians from the run into the committed
  `bench/baseline.json`; a scenario that did not run, or did not finish DONE, keeps its existing entry.
- `-Pbench.ping=<ms>` overrides the round trip every crystal-aura MEASURE plays over (below, "The simulated
  ping"); `0` restores today's lock-step for those runs too.

**The Meteor cache.** Meteor's crystal-aura gives nearly the same numbers run after run, so a `ca-*`
MEASURE that finishes DONE keeps its runs in `build/bench/meteor-cache/<scenario>.json` (the build folder:
never committed). Its key is a SHA-256 over what can change Meteor's crystal-aura in the bench, each file
by its sorted path with its bytes:

- the Meteor jar the game loaded, and the Minecraft version;
- every file under `src/gametest/`: the bench itself, its scenarios, arenas and scripts;
- the build files `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` and
  `gradle/libs.versions.toml`, which pin the Fabric API, loader, yarn, Loom, Meteor and Minecraft versions;
- every mixin config under `src/main/resources` (a JSON with a `package` and a mixin list), and every Java
  file under each config's `package` in `src/main/java`, sub-packages included, found from the configs and
  never hard-coded: the addon's mixins reach into Minecraft itself, so into what Meteor sees;
- every file under `src/main/java/com/xploits/pvp/recorder`: the recorder is the instrument that measures
  Meteor's `self_damage` and `self_pops`, so a change to how it counts must re-measure Meteor, or the `ca-*`
  numbers and the `capp-*` numbers judged against them would follow two different rules;
- the scenario's name.

The rest of `src/main` is left out on purpose: crystal-aura++ is off in every `ca-*` run, and hashing it
would re-measure Meteor on every crystal-aura++ change, which would defeat the cache. What it does leave out
(another addon module that stays on during a `ca-*` run, say) is what the release's `-Pbench.fresh` is for.

The next run whose key matches does not play that scenario: it reports it DONE with the cached runs, marked
`"cached": true` with the date it was measured in the JSON report, `cached, measured <date>` in the
markdown, `(cached)` in the client log and `(n cached)` in the summary lines. A change to any input of the
key, a missing file or one that cannot be read is a miss: the scenario is measured again and its file
overwritten. Cached runs count exactly like measured ones for the verdicts, the baseline and the
regressions. Only Meteor's own `ca-*` MEASUREs are cached: never a CHECK, not even `capp-budget-off-parity`,
which plays Meteor's aura inside, never a `capp-*`, and never `defense-attacker`.

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
Aggressive against the same `ca-X`. One of the pure safety rules judged inside each verdict, S3 (crystal-aura++'s
`self_damage` measurably lower or its `min_health` measurably higher), is not applicable when Meteor's
`min_health` never went below the pair's level's reserve (Safe 5, Balanced 3.5, Aggressive 2): there is
nothing there for the budget to prevent, so "the same as Meteor" is the best possible outcome, not a
failure; where Meteor does go below the reserve, S3 still demands more safety.
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

**Moving-player scenarios (R3-14).** After the four fight situations above, `self-circle` and `self-strafe`
run the same way (`ca-<s>-regen`, `capp-<s>-regen`, `capp-balanced-<s>-regen`, `capp-aggressive-<s>-regen`,
healing always on) but also move OUR OWN player, with real client movement (the same per-tick packet an
actual player's own movement would send, not a teleport): self-circle walks a circle around our start block
while the sparring (`Still`) stands still; self-strafe zig-zags sideways while the sparring (`Circler`)
circles. Because our own player never stands still, `Settle` treats both as never static, whatever the
sparring's own script says, so they always run their full nominal length. Their log also gives the distance
our player actually walked and how far the server's and the client's own copies of it end up apart (log only,
never a metric): no rubber-banding is expected beyond what the simulated ping's own travel time explains.

**The simulated ping (R3-12).** On the integrated server the server's tick and the client's tick start in the
same millisecond, so a few microseconds of client work (crystal-aura++'s exact-damage raycasts, say) decide
whether a placement is taken this server tick or the next; a real network's latency makes that race irrelevant.
So every crystal-aura MEASURE — every `ca-*` and `capp-*` run, including the inner Meteor and crystal-aura++
runs of the CHECK `capp-budget-off-parity` — plays over a simulated round trip, `PingDelay.BENCH_PING_MS`
(100 ms, 50 ms each way), identical for both auras; every other scenario keeps today's lock-step (0 ms), since
none of them turn on a decision that race can affect. `-Pbench.ping=<ms>` overrides the round trip those runs
play over (`0` restores the lock-step for them too); the Meteor cache's key includes it, so a `ca-*` measured
under one ping is never served to a run asking for another.

The delay is added by a bench-only Fabric mixin (`src/gametest/java/com/xploits/bench/mixin`, its own
`xploits-bench.mixins.json`, on the gametest classpath only — `./gradlew build` checks the shipped jar carries
no bench class), on `ClientConnection.addFlowControlHandler`: that method runs once for the client's own
connection and once for the server's connection to that same player, so delaying both symmetrically gives a
round trip with no special case for any one packet kind — keep-alives included the same as any other packet.

**Sharded runs, several clients at once (task A5).** `bench/parallel.ps1` (PowerShell 7) plays the bench as up
to 4 Minecraft clients at once and merges their reports into one, to cut the wall time on a machine that can
run several clients comfortably: `bench/parallel.ps1 -Shards 4 -Full -Fresh` for a release, the same flags as
always otherwise (`-Full`, `-Fresh`, `-Only`, `-Ping`, `-VerifySettle`, `-UpdateBaseline`). It refuses on a
dirty tree (every shard plays the committed HEAD), runs shard 1 in the current worktree and shards 2..n each
in their own detached worktree `.worktrees/bench-shard-<k>` (created or reset to HEAD; never the main
worktree, never committed or pushed from), copies the Meteor cache into each shard first and the new entries
back after, then hands every shard's report to the `benchMerge` Gradle task. `-Pbench.shard=k/n`
(`1 <= k <= n <= 4`) picks which slice of the selection one client plays: `ShardPlan` (`bench/core`, pure)
partitions the scenarios so a compare group — a Meteor scenario together with every scenario judged against
it — is never split, balanced by an estimated duration (each item's runs times its time limit plus a fixed
per-run overhead for a fresh world's creation and teardown); `n = 1` plays exactly what today's plain run
does. `ReportMerge` (`bench/core`, pure) then turns the `n` shard reports into ONE, indistinguishable in
shape and meaning from a single client's: scenarios in the unsharded order, and the `compare` and
`recommendation` lines worked out by the same code a single run uses. It refuses, with a clear reason,
unless every shard agrees on the commit, the versions and the flags it ran, every `k` of the same `n` is
present exactly once, every scenario appears exactly once, and every shard's own hygiene is clean; if a
shard's client itself fails, `bench/parallel.ps1` keeps the other shards' reports, names the failed one, and
never attempts a merge (a partial merge never passes the gate). `benchVerify` and the release gate then run
on the merged report exactly as they do on a plain run's, and `-Pbench.updateBaseline` is applied once, after
the merge, from the complete report (never by an individual shard, since shards 2..n's own `bench/baseline.json`
lives in a disposable worktree). **A release may use a sharded run only with `-Pbench.full -Pbench.fresh` and
a clean merged gate — the same rule as always, just checked once, on the merged report.**

Each shard's own gradlew process runs with a hidden console (`-Pbench.shard` also caps its heap at `-Xmx3G`,
measured on the owner's machine at roughly 2 GB for a short two-scenario run, so 3 GB is real headroom, not a
guess): closing what looks like an empty leftover console window sends that process CTRL_CLOSE and kills the
shard, so `bench/parallel.ps1` never shows one. Only the Minecraft client's own window (separate from the
console, opened later by the JVM) stays visible for each shard, since the gametest needs it.
**Verified:** the bench always plays through an integrated server, whose one player is always its host, and
vanilla's `isHost` check (`IntegratedServer.isHost`, matched by profile name) makes
`ServerCommonNetworkHandler.baseTick` skip sending the host a keep-alive at all — so the player-list latency
the bench logs is always 0 for it, on the client and on the server, whatever the simulated ping, and that is
not a sign the delay is off: a keep-alive is an ordinary packet to the pipeline, which does not know none will
ever be sent to this player. It only touches a `LocalChannel` (the integrated server's connection; never a
real one) and only while the bench has set a positive delay for the run in progress; each message is re-fired
on the channel's own event-loop executor after a fixed delay with no jitter, which preserves order and drops
nothing.

**Verified: Fabric's own `NetworkSynchronizer` must be disabled for this.** The client-gametest framework
blocks the render thread every frame until every packet it saw sent has been handled on the netty thread
(`waitForPacketHandlers`, a 10 s timeout), so the gametest code can trust the world is settled after
`waitTick()`. The bench's simulated ping deliberately holds a packet inside the pipeline longer than that
synchronizer expects; once its own wait times out, it logs "Detected interfacing with packets at a lower
level" and the next frame crashes the client with "Network synchronizer in invalid state, see earlier log
messages" (seen on a real run: a 100 ms ping in the still arena survived two scenarios, then crashed on the
third once the timeout was actually reached). Fabric's own log line for that error names the fix, and the run
config sets it unconditionally: `-Dfabric.client.gametest.disableNetworkSynchronizer=true`. A scenario with no
simulated ping installs no extra pipeline handler and was never at risk from this either way.

**This disable is JVM-wide, for the whole bench, not just the scenarios that simulate a ping.** Fabric reads
the property into a `static final` once, at class load (`TestSystemProperties.DISABLE_NETWORK_SYNCHRONIZER`),
and one JVM runs every scenario of a profile in one `BenchTest.runTest` call, so there is no way to turn the
synchronizer off only for the runs that install the delay handler and leave it on for the rest. Every other
scenario that reads client state right after a tick — the recorder CHECKs, `panel`, `profile-defensive`,
`autopvp-anti-resources`, `defense-attacker` and the rest — therefore also runs without the synchronizer's
"every sent packet already handled on the netty thread" guarantee, even though none of them install a delay
handler themselves; the everyday and full runs have not shown this cause a flaky result, but it is a real,
permanent change to what every scenario can rely on, not only the four scenarios the ping was designed for.

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
