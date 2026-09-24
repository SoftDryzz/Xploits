# Known issues

Failures we **know** exist, with their symptom and what to do. If something happens to you and it is
not here, it is a new failure and worth writing down.

---

## Confirmed, not closed

### `nether-sweep` can measure the lane width too wide

**Symptom:** the sweep announces a large lane width (more than 20 chunks) and on landing the coverage
comes out below 95 %, with a loud warning.

**What happens.** The module discards samples taken while moving, but it looks at the speed of the tick
in which the chunk **arrives** — and the premise of the filter is precisely that the arrival tick is not
the queuing one. On landing after a long flight, the accumulated queue drains **with the player already
stopped**: those samples pass the filter and are the most inflated of all.

**Consequence:** lanes spaced further apart than the server covers, with strips between them that never
arrive.

**What to do.** Turn the module on **after** landing and wait a few seconds before doing the measuring
lap, so the flight's queue has drained. And **always look at the coverage message on landing**: it is
the net that catches this.

**Pending fix:** require the speed to stay below the cap for ~40 ticks before accepting samples,
instead of looking only at the last tick.

### The consumed-link warning claims more than it can

**Symptom:** when starting a sweep, a warning says some corner will be lost but that *"the band is
crossed anyway"*.

**What happens.** That is true when the consumed link is short (a narrow last band) and **false when it
is a whole band long**, which by construction always exceeds the coverage radius: it leaves up to two
thirds of the band undelivered at one end, 22 % integrated.

**When it hits you:** with small lane widths (a loaded server) or with a high `waypoint-margin`.

**What to do.** If you see that warning, lower `waypoint-margin` or measure the width again. And once
more: the coverage message on landing is the net.

**Pending fix:** reject when the link exceeds the coverage radius, and only warn below it.

### The first lane of a short sweep can be consumed without being flown

**Symptom:** a small-area sweep (long axis between 10 and 19 chunks) finishes suspiciously fast.

**What happens.** The start of the first lane is reached from the approach, in any direction, so it
can be released while already inside the lane. The check that stops a lane from being consumed without
being flown does not cover that case.

**What to do.** Use areas with the long axis well above 19 chunks.

### Baritone settings are not restored if you disconnect badly

**Symptom:** after an abrupt disconnection during a flight, your manual `#elytra` flights behave
differently.

**What happens.** If the disconnection arrives with the player already null, the restore commands are
not sent and the five settings keep their flight values — which Baritone saves to disk.

**What to do.** Go over `elytraAutoSwap`, `elytraAutoJump`, `elytraAllowEmergencyLand`,
`elytraConserveFireworks` and `elytraFireworkSpeed` with `#set <name>`.

### Three combat modules cannot be turned off by hand while `auto-pvp` directs them

**Symptom:** you turn off `auto-trap`, `auto-city` or `auto-anvil` and they turn themselves back on.

**What happens.** Those three turn themselves off by design when they finish their job, so the director
cannot tell "it turned itself off" from "the player turned it off" and takes them again. With
`surround` it could be closed, because its self-disable is measurable from outside.

**What to do.** Turn `auto-pvp` off while you want them for yourself.

### The short-lane rejection, in Spanish, says to bring the corners closer when they must be moved apart

**Symptom:** the `sweep.lane-too-short` rejection in Spanish says «agranda el rectángulo por ahí <!-- es-quote -->
-acerca chunk-x-1 a chunk-x-2, o chunk-z-1 a chunk-z-2-» when the rectangle has to **grow**.

**What happens.** The English text says the right thing -*move ... apart*, move that pair of chunks
apart-; the Spanish one was left with «acerca» ("bring closer"), which is the opposite of what is
needed to enlarge the area.

**What to do.** Ignore the verb and move apart the pair of chunks that is closer together, do not bring
them closer. The Spanish text is still to be fixed.

### `config.nbt` is not migrated: a hidden module can reappear once

**Symptom:** after updating to 0.4.0, a module you had hidden from the ClickGUI (for example `console`)
shows up in the list again once, even though `modules.nbt` was migrated correctly.

**What happens.** The 0.4.0 migration renames `modules.nbt`, each profile's `modules.nbt` and
`hud.nbt`, but leaves `config.nbt` out on purpose: Meteor loads it in `Systems.init()`, before the addon
starts, and rewrites it whole when the game closes, so anything the migration wrote there would be lost
anyway. `config.nbt` is where Meteor stores which modules are hidden from the ClickGUI, by name; if that
name was the old one (`consola`), it no longer matches the renamed module (`console`) and the module is
no longer hidden.

**What to do.** Hide it again once under its new name; from then on `config.nbt` stores it correctly
and it does not happen again.

### Meteor macros with `.toggle consola` are not migrated

**Symptom:** after updating to 0.4.0, a Meteor macro that turned the console on or off stops doing
anything.

**What happens.** Macros are saved in `macros.nbt` as the text they type, and the 0.4.0 migration does
not touch that file: a macro that types `.toggle consola` still names the old module, which is now
called `console`.

**What to do.** Edit the macro so it types `.toggle console`.

### Resuming `feat/dupe-audit` will clash with the English code

**Symptom:** when resuming the uncommitted work of `feat/dupe-audit` (root checkout, on `e902c52`) after
this migration, applying its changes fails.

**What happens.** That work touches `XploitsAddon.java` with a hunk written against the Spanish version
of the file; after the rename to English that hunk no longer applies cleanly and has to be redone by
hand against the current `XploitsAddon.java`. Also, `DupeAuditCommand` extends Meteor's `Command`
directly instead of the common base (`XploitsCommandBase`, formerly `ComandoBase`), so its messages do
not go through the console: it has to be moved to `XploitsCommandBase` for the console to see what it
says.

**What to do.** When resuming that branch: resolve the `XploitsAddon.java` conflict by hand and change
`DupeAuditCommand` to extend `XploitsCommandBase`.

### The console needs Windows Terminal with its default window settings

The window asks for its size with a sequence Windows Terminal honors. If you set it to open in tabs
(`windowingBehavior`), that sequence can resize your other window or do nothing. With the classic
conhost, selecting text freezes the window for as long as the selection lasts; the game does not
notice, because they only talk through files.

---

## Unmeasured calibrations

Numbers chosen with a reasoning but with no real flight data behind them. If you notice one getting in
the way, it is a candidate for adjusting.

| What | Value | Why it was chosen |
|---|---|---|
| Firework sampling | every 1,000 blocks | It cannot be per tick: the rate would come from dividing one tick of flight by one firework |
| Expiry of the measured consumption | factor ×1 over its own evidence | It can expire early at the start of the flight and take long on long flights |
| Coverage floor | 95 % | First value with no data behind it. The first hour of real flight will probably move it |
| Area cap | 4,000,000 chunks | Crossing of three bounds: how long it would take to fly, not getting in the way of normal use, and what it costs to cover |

---

## Things that look like failures and are not

**"I turn on `auto-travel` or `nether-sweep` and nothing happens."** They do not fly when turned on:
they arm the trip and wait for their command. When you turn them on they tell you so in chat.

**"A 3×3 chunk sweep is rejected."** Correct. A long axis of about 19 chunks is needed: below that, the
vertices are so close together that they would be consumed in the same tick, and there would be lanes
that are not flown **and that the sweep would count as combed anyway**.

**"`DECOY` is rejected in highway mode."** Correct. Aiming 30° off takes you out of the corridor, and
that draws more attention than going straight.

**"A warning says a `#` command was cancelled."** That is the net working. It means Baritone is not
intercepting its own commands and that, if you typed them by hand, they would be published in the
server chat.

**"`auto-pvp` turns nothing on even though someone is right in front of me."** Look at `.xploits pvp`:
you are probably short of resources (obsidian, crystals) or the target is one of yours. The command
says so.

**"I cannot start a sweep because I am travelling."** Correct: both direct the same Baritone and
`#elytra` only accepts one goal. Stop the first one.

**"Some `auto-travel` rejections in Spanish end in '..'."** It is on purpose; it was left as it is
worded. It only affects Spanish: in English those same reasons were trimmed so the sentence ends with a
single period.

**"Saving `language.txt` fails and then the language goes back to the previous one."** The player is
warned at the moment that it could not be saved, but if the save fails the process restarts with
whatever was in the file the last time it was written successfully: after a restart the file's previous
language wins and the choice made in the ClickGUI is reverted.

**"I close the console with the X and it says 'with the X or from outside'."** Windows does not let a
program run anything when you close its window with the X; this was checked. So the game cannot tell
the X from a window killed from the task manager, and the warning says both instead of pretending.
Quitting with `0` can be told apart.

---

## Not verified in game

Things that are implemented and reasoned through but that **nobody has seen working yet**:

- Whether 6b6t allows sweeping the Nether without limiting it or kicking for flying long.
- Whether the elytra swap kicks in properly while gliding fast.
- Whether `auto-web` buries itself when placing cobweb under the enemy.
- Whether the server sends chunks at the rate the lane planning assumes.
- Whether 6b6t sends the contents of shulkers inside chests (it decides half of the design of
  `stash-keeper`).
