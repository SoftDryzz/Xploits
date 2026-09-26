**English** · [Español](README.es.md)

# Xploits

A [Meteor Client](https://meteorclient.com/) addon for Minecraft **1.21.11**, built for 6b6t and
other anarchy servers. Nine modules, each switched on separately.

The core idea: **no module does half a job without saying so**. If it cannot deliver what it
promises, it refuses and tells you which setting to change — instead of doing something similar and
keeping quiet.

---

## Before installing

**Copy `xploits-<version>.jar` into your instance's `mods/` folder**, next to Meteor, and **delete
any older `xploits-*.jar`**: the file name carries the version, and with two of them the addon loads
twice. Restart the game. The modules show up in the ClickGUI, under the **Xploits** category. What
changes in each version: [CHANGELOG](CHANGELOG.md).

⚠️ **If you are coming from before 0.4.0**, close any console window you still have open before
starting the game: on the first start of 0.4.0, the addon automatically renames the settings and
files whose names changed in that version (`modules.nbt`, `hud.nbt` and the `xploits/consola/`
folder), backs up every file it touches as `<file>.pre-0.4.0.backup.nbt` before writing it, and
tells you in chat what it did. You lose nothing; if a console window still has the folder open,
whatever could not be moved is retried on the next start: with the old window closed,
`xploits/consola/` is merged into `xploits/console/` (the history goes to `console/history/`,
overwriting nothing; if the same day is in both, both are kept and the old one becomes
`<day>-old.log`) and the old folder is deleted once empty. Meteor macros that type
`.toggle consola` are not migrated: change them to `console`.

### What you need installed, and what stops working without it

This addon **relies on other mods on purpose**: when something is already solved, and solved well,
it uses that instead of reimplementing it worse. The price is that you need to have them.

| Mod | Required for | If missing |
|---|---|---|
| **Meteor Client 1.21.11** | Everything | The addon does not load |
| **[Baritone](https://github.com/cabaletta/baritone)** | `auto-travel`, `nether-sweep` | Both **refuse to launch** and say so. The other seven modules work the same |
| **[Trouser Streak](https://github.com/etianl/Trouser-Streak)** → `NewerNewChunks` | `nether-sweep` | The sweep flies, but **replans ground you had already covered** and leaves no trace for next time. It warns you before take-off |
| **Trouser Streak** → `BaseFinder` | `nether-sweep` | The sweep flies and **finds nothing**: this is what detects portals, skybuilds and builds on the roof. It warns you before take-off |
| **`stash-finder`** (comes with Meteor) | `nether-sweep` | The sweep flies and records no containers. It warns you before take-off |

⚠️ **`nether-sweep`'s three detectors must be switched on, not just installed.** The module warns
you with a toast before take-off if any is missing, **but it does not stop you**: you can fly for an
hour and record nothing.

### Which Meteor modules the addon touches

Worth knowing, because these are **your** modules that the addon turns on, turns off or reads.

| Who | What it touches | How |
|---|---|---|
| `auto-pvp` | `crystal-aura`, `auto-trap`, `auto-web`, `auto-anvil`, `auto-city`, `surround`, `hole-filler`, `anti-anvil`, `anti-bed`, `anti-anchor` | Turns them on and off depending on the situation. **It only turns off the ones it turned on**: if you touch one by hand, it stops touching it |
| `auto-pvp` | Your **Meteor friends list** | Adds your people while it is on, so the five combat modules do not attack them either. When turned off it removes **only the ones it added** |
| `auto-pvp` | `crystal-aura`'s `anti-suicide` setting | It only **reads** it, to know whether it can trust Meteor not to kill you with your own crystal |
| `auto-travel`, `nether-sweep` | `elytra-fly`, `elytra-replace` | Borrows them during the flight and gives them back **in the state they were in** |
| `auto-travel`, `nether-sweep` | Five **Baritone** settings | Changes them on take-off and restores them on landing. ⚠️ Baritone saves them to disk — as it does `elytraTermsAccepted`, `elytraPredictTerrain`, `elytraNetherSeed` (if set) and its own censoring, which stay changed for good |

All of this, with the detail of what persists and what can go wrong, in [Security](docs/security.md).

---

## The nine modules

### `auto-travel` — fly somewhere without leaving an arrow pointing at your base

Flies with elytra using Baritone, along a route with a **decoy pattern** so your trail is not a
straight line pointing at where you live.

**Turning it on does not fly**: it arms the trip and waits. You launch it with `.xploits travel go`.

**The destination is given in three ways** (`destination-mode`):

| Mode | What you ask for | Settings |
|---|---|---|
| `COORDINATES` | A point in the world | `x`, `z` |
| `RELATIVE` | An offset from wherever you are: 5000 and −3000 is "5000 on X and −3000 on Z from here" | `offset-x`, `offset-z` |
| `HIGHWAY` | An axis and how many blocks to fly along it | `axis`, `highway-distance` |

**`RELATIVE` leaves the least trace.** Meteor saves the settings in
`<instance>/meteor-client/modules.nbt`: with `COORDINATES` your destination ends up written there;
with an offset, only how far you move, which does not say from where. If you used coordinates
before, set `x` and `z` to 0: switching mode does not erase what was already saved.

**There are eight highways:** the four straight ones (`X_PLUS`, `X_MINUS`, `Z_PLUS`, `Z_MINUS`) and
the four diagonals (`X_PLUS_Z_PLUS`, `X_PLUS_Z_MINUS`, `X_MINUS_Z_PLUS`, `X_MINUS_Z_MINUS`). On all
of them, `highway-distance` is **blocks flown**: 20 000 along a diagonal advances about 14 142 on X
and as many on Z, and costs the same fireworks as 20 000 straight.

| Pattern | What it does | When |
|---|---|---|
| `STRAIGHT` | Nothing | **On a highway.** There your trail is one among thousands; weaving only burns fireworks and takes you out of the corridor |
| `ZIGZAG` | Weaves side to side often | So whoever sees you from afar cannot draw your heading with a ruler |
| `SWERVE` | The same, with long legs and wide swerves | Against whoever saw you from further away. Costs quite a bit more |
| `SPIRAL` | Straight almost all the way, a spiral at the end | **Protects the arrival**: you do not approach home in a straight line |
| `DECOY` | Aims at a fake spot and corrects halfway | **Protects the departure**: against whoever sees you take off |

**The detour is paid in fireworks.** The spiral is expensive in absolute terms: its length depends
on the radius and the turns, not on the distance. **Lower `spiral-turns` to 0.5** unless you want to
pay for it — 1.5 costs four times as much as 0.5 for only 31 % more detour.

**To found a base**, two trips: first along a highway with `STRAIGHT` as far as your inventory
lasts; then you leave the highway and go to the coordinates with `SPIRAL`. The point where you leave
the highway is the strongest clue you will leave behind: do not do it at a round coordinate, nor
twice at the same spot.

### `nether-sweep` — comb ground to find bases

Flies a rectangle of the **Nether** in lawnmower passes, so the ground passes in front of your
client.

**This module flies, it does not detect.** The finding and recording is done by three mods you
already have:

- **`BaseFinder`** (Trouser Streak) — open portals, builds on the roof, skybuilds.
- **`stash-finder`** (Meteor) — chests, barrels, shulkers, ender chests.
- **`NewerNewChunks`** (Trouser Streak) — records which chunks have reached you. **The sweep reads
  it** and plans only over the gaps, so it does not repeat what you have been piling up for months.

**With those three off you fly for an hour and nothing is recorded.** The module warns you before
take-off, but does not stop you.

Why the Nether: a portal at `(x, z)` is an Overworld base at `(8x, 8z)`. Every block you fly there
covers **64 times** more area.

**Turning it on does not fly**: you launch it with `.xploits sweep go`.

**Three things that will happen to you the first time:**

1. **A small rectangle is rejected.** The long axis has to be longer than `waypoint-margin` — about
   10 chunks with the default (150 blocks). That is correct.
2. **It has to be measured walking, not flying.** The module measures on its own how far away the
   server sends you chunks, but it only accepts samples with the player almost still. **Turn the
   module on and walk around** for a few seconds. The measurement is discarded on launch: to launch
   again, another walk.
3. **On landing it tells you how much it really covered**, not just that it finished. If it delivers
   less than 95 % the warning comes out **loud, with a toast**: that area is not fully combed and
   you have to launch the same rectangle again, which is replanned only over what is missing. **That
   warning is the safety net for two known bugs** — see [Known issues](docs/known-issues.md).

### `auto-pvp` — combat modules that switch on when it is time

**It does not fight.** It turns Meteor's on and off depending on the situation, and only turns off
the ones it turned on: if you touch one by hand, it stops touching it.

It decides on **two axes at once**: what phase the enemy is in (approaching, on the surface,
surrounded, buried, fleeing) and what is aiming at you. The latter with the damage already on top
of you —placed crystals, people with swords—, not with a totem counter.

**It does not attack your people**: your Meteor friends, `kit-requester`'s couriers and your
`auto-tpy` list. And since the five combat modules pick their own target, while it is on it **adds
your people to your Meteor friends list** so they do not either. When turned off it removes only
the ones it added, never one you already had. See [Security](docs/security.md).

**It only turns a module on while you carry, in the hotbar, what that module places**:
`crystal-aura` crystals (without them it is turned on anyway, to break the ones placed against you),
`auto-trap` 8 obsidian, `surround` 4, `hole-filler` and `anti-anvil` 1 each (the four draw from the
same stack), `auto-web` cobwebs, `auto-anvil` anvils, `auto-city` a diamond or netherite pickaxe
and `anti-anchor` any slab. `anti-bed` goes up without anything: it breaks a bed on your head without
string, and needs string to place it; by default it acts only while you are in a hole
(`only-in-hole`). Meteor 1.21.11 ships no `anti-anchor` module (the class is there, but it is not
registered): `auto-pvp` says so once when turned on and does not use it.

**Style profiles change auto-pvp's own values and which of the ten modules it may use** — never the
inner settings of a module it turns on (`crystal-aura`, `surround`...). Three are built in and
always available, plus up to 20 of your own:

| Profile | `target-range` | `approach-distance` | `threat-margin` | Modules |
|---|---|---|---|---|
| `balanced` | 16 | 6 | 12 | all ten |
| `aggressive` | 24 | 4 | 8 | all ten |
| `defensive` | 12 | 6 | 16 | all but `auto-city` and `auto-anvil` |

Which modules a profile allows is the `Modules` setting group's `use-<module>` toggles, one per
managed module, in the `auto-pvp` ClickGUI: untick one and the director skips it (shown in the HUD
panel below, not in chat). **A profile you save keeps whatever they are set to.** Editing a value by
hand — a slider or a `use-*` toggle — marks the active profile as modified (`aggressive*`) until you
`profile save` it. The `next-profile` key cycles through them, built-in first, then yours; it fires
on release, with `auto-pvp` on and not while typing in a text field — with it off, use
`.xploits pvp profile use`.

### `fight-recorder` — record every fight and work out why you died

Watches every fight you are in — with `auto-pvp` on or off — and keeps a JSON file for each one: who
you fought, the damage split, your totems and crystals, the modules you had on and, if you lost, its
best guess at why. **No positions are stored**: distances, counts, booleans and names only.

**It is off by default: turn it on once.** After that it just runs in the background with the rest
of your modules — you do not have to remember to arm it before a fight.

Kept in `<instance>/meteor-client/xploits/pvp/fights/`, one file per fight, the last 50: older ones
are deleted as new ones are written.

- **`death-notice`** (on by default) — one chat line when you die in a recorded fight: how long it
  lasted, who against, and the main probable cause.
- **`live-console`** (on by default) — writes the fight to the Xploits console as it happens (pops,
  big hits, deaths) and its summary when it ends.

Review what it recorded with `.xploits pvp review [n]` (the full breakdown of fight `n`, 1 = most
recent) and `.xploits pvp fights` (the last 10, one line each) — both work with the module off. A
recorded fight also keeps the **style profile active when it opened, and every switch during it**:
`review` shows both, the switches merged in with the module changes, in the order they happened.

### `elytra-replace` — swap the elytra before it breaks

Two independent percentages: at how much to swap the one you are wearing, and the minimum the spare
must have. It picks **the worst one that passes the minimum**, so as not to waste the good one. Works
with or without `elytra-fly`.

### `kit-requester` — request kits

Requests kits from SnifferBuddy in batches and accepts the TPA from the courier who delivers them.

> **The kit bots have been down for a while.** The module works, but do not expect anything to
> arrive while they stay that way.

### `auto-tpy` — accept TPAs

Instantly accepts those from your list and from your Meteor friends. **It never accepts
`/tpahere`**: it only brings people to you, it never moves you.

### `stash-keeper` — remember where you saw things

Notes down the contents of the containers you open and of the shulkers you see. **It moves
nothing.** Later `.xploits find <item>` tells you where it was.

### `console` — see what the addon does in a separate window

Opens a terminal window with the XTO2002 logo at the top, the game status below it and the log of
everything the Xploits modules say. To keep it on the other screen while you play, or to read later
what happened.

- **It is switched on and off like any module.** If you leave it on, it opens by itself when the
  game starts, and it does not close when you leave a world or die.
- **The menu works by numbers:** type the number and press Enter. `1` all, `2` pvp (`auto-pvp` and
  `fight-recorder`), `3` travel, `4` sweep, `5` warnings only, `6` pause, `0` exit.
- **It never shows coordinates.** What carries a position in chat comes out in the window with the
  distance or with nothing. The chat does not change.
- **What it writes stays on disk** while it is on: `meteor-client/xploits/console/history/`, one
  file per day, 30 days at most.
- If the game closes or hangs, the window **stays open** and says so, so you can read the last
  thing that happened.

It needs Windows Terminal, which is the default console in Windows 11.

---

## The auto-pvp HUD panel

`xploits-pvp` is a HUD element — Meteor's HUD editor, group **Xploits** — not a module: place it
like any other element. Auto-pvp off collapses it to one line, `auto-pvp off · profile <name>`;
otherwise it shows, one line per fact and only when there is something to say:

- A **danger line** (red), highest priority first: no totems in a fight, out of resources, a
  watched resource idle for a while without anything spending it, or `crystal-aura` on without
  crystals.
- **`profile · state · posture`** — amber while the posture is `THREATENED`.
- **Target and distance**, or `no target`.
- **Modules**: on (green), released by you (amber), off by the active profile (grey).
- **Resources**: crystals, totems, obsidian — amber below what the modules currently on need.
- **The live fight**, while `fight-recorder` has one open: seconds, your pops, theirs, damage taken.

Settings: `scale`, `shadow`, `background` and `show-fight` (on by default; turns off the fight
line).

**Starscript**: `{xploits.pvp.state}`, `{xploits.pvp.posture}`, `{xploits.pvp.profile}`,
`{xploits.pvp.target}` and `{xploits.pvp.distance}` — the same facts the panel shows, translated
into the active language, never a position.

---

## Commands

| Command | What it does |
|---|---|
| `.xploits status` | Overall status |
| `.xploits find <item>` | Where you saw that item |
| `.xploits stash` | Status of the container index |
| `.xploits pvp` | Phase, posture, your health and the damage aimed at you |
| `.xploits pvp review [n]` | Full breakdown of a recorded fight (1 = most recent) |
| `.xploits pvp fights` | The last 10 recorded fights |
| `.xploits pvp profile` | Active style profile, and whether it is modified |
| `.xploits pvp profile list` | All style profiles, the active one marked |
| `.xploits pvp profile use <name>` | Switches to that profile |
| `.xploits pvp profile save <name>` | Saves the current values under that name |
| `.xploits pvp profile delete <name>` | Deletes one of yours, or resets a built-in to factory values |
| `.xploits pvp profile reset-file` | Moves a corrupt `profiles.json` aside so saving works again |
| `.xploits travel` · `go` · `stop` | Trip status, launch it, stop it |
| `.xploits sweep` · `go` · `stop` | Sweep status, launch it, stop it |
| `.xploits language [auto\|es\|en]` | Active language, or sets it |
| `.xploits reload` | Reloads the saved data |

---

## What to know about the two that fly

**They cannot be used at the same time.** Both drive the same Baritone and `#elytra` only takes one
goal: the second would take it from the first, the first would cut out after 45 seconds with its own
restoration —stopping the second's flight halfway— and the second would diagnose a false stall.
Each one checks for the other and **refuses to launch** while the other is flying. They are used in
the same outing: you fly with `auto-travel`, stop it when you arrive, and sweep.

**Baritone's prefix cannot start with `/`.** With a slash the command goes down the server command
path, which Baritone does not listen to, and on top of that the safety net would swallow every other
slash command. It is rejected on launch and explained.

**Before launching a trip** you need an elytra on and fireworks. `elytra-replace` swaps the one you
are wearing, but does not put one on you. And if you have `elytra-fly` with `chest-swap` on `Always`
or `WaitForGround`, it refuses to launch: turning `elytra-fly` off with that set swaps your elytra
for the chestplate right before take-off. Set `chest-swap` to `Never`.

**On landing**, `elytra-fly` and `elytra-replace` go back **to the state they were in before**, not
to a declared one; if you move them by hand during the flight, what you left wins.

**If a warning says a `#` command was cancelled, it is not a bug**: it is the net doing its job. It
means Baritone is not intercepting its own commands, and that **if you typed them by hand they would
be published in the server chat**.

---

## Where your data is stored

```
<instance>/meteor-client/xploits/        Kit queue and progress
<instance>/meteor-client/xploits/stash/  Container index, per world
<instance>/meteor-client/xploits/console/  Console history, 30 days at most
<instance>/meteor-client/xploits/pvp/fights/  Recorded fights, 50 at most
<instance>/meteor-client/xploits/pvp/profiles.json  Your style profiles
<instance>/meteor-client/modules.nbt     Settings (written by Meteor)
<instance>/meteor-client/friends.nbt     Friends list (written by Meteor)
```

⚠️ **Your coordinates live in more places than you think**: in `modules.nbt` if you set an absolute
destination, in Minecraft logs from before 0.3.1 (since then they are masked, see [Coordinates in
the logs](#coordinates-in-the-logs)) and in Xaero's data. If you share a log to get help with
something, **clean it first**. See [Security](docs/security.md).

---

## Language

The `xploits` module has a `language` setting: `Auto`, `Español` or `English`. On `Auto` it follows
Minecraft's language —any `es_*` gives Spanish, anything else gives English—; with `Español` or
`English` it stays fixed there whatever happens with the game.

`.xploits language` says which one is active right now. `.xploits language auto|es|en` changes it.
The chat, the toasts and the console window change at once; the ClickGUI descriptions, on the next
restart.

⚠️ **With Minecraft in English, the addon starts in English.** If you want Spanish from the start,
run `.xploits language es` the first time.

## Coordinates in the logs

Minecraft copies every chat line into `logs/latest.log`, so any position that shows up in chat ends
up on disk. The `xploits` module's `hide-coordinates-in-log` setting masks them with `***` in that
copy (on screen you still see them): `Off`, `Baritone` (only its lines), `All` (the default) or
`All but Baritone`. It also masks the `Saving region x,z` lines Baritone writes on its own.
`auto-travel` and `nether-sweep` also turn on Baritone's censoring (`censorCoordinates`,
`censorRanCommands`) before the first goal, and leave it on.

The console hides coordinates by default. With its `hide-coordinates` setting off it shows the same
as the chat and saves them to disk (up to 30 days of history).

Logs from before 0.3.1 are not cleaned.

---

## If something does not work

Read **[Known issues](docs/known-issues.md)**: it has the bugs we know exist, with their symptom and
what to do.

The most common:

- **"I turn it on and nothing happens."** `auto-travel` and `nether-sweep` do not fly when turned
  on. When you turn them on they tell you so in chat.
- **"It rejects me and I do not know why."** The message gives the setting, its current value and
  what to set it to. If it sends you to a setting that does not exist under that name, **that one is
  our bug**.

---

## For whoever touches the code

| Document | What for |
|---|---|
| [Architecture](docs/architecture.md) | How it is put together, and what would be portable to another client |
| [Security](docs/security.md) | What each module touches, what persists to disk, what can leak |
| [Known issues](docs/known-issues.md) | Bugs by name |
| [Conventions](docs/conventions.md) | How work is done here and why |
| [Building a client of your own](docs/own-client/) | If this stops being an addon: licences, Meteor's anatomy, Baritone through its API and a roadmap |

**Build:** `./gradlew build` → `build/libs/xploits-<version>.jar`.
