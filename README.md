![Xploits](docs/images/banner.png)

**English** · [Español](README.es.md)

# Xploits

A [Meteor Client](https://meteorclient.com/) addon for Minecraft **1.21.11**, built for 6b6t and
other anarchy servers. Twelve modules, each switched on separately.

> **New in 0.9.0, experimental: `restock`.** Build with Litematica and `litematica-printer` as you do today: when a block
> the build still needs runs out, `restock` pauses the printer, walks to the nearest chest you marked (or that
> `stash-keeper` remembers), takes what the rest of the build needs, walks back and resumes it. It works with shulker
> boxes too: the ones you carry and the ones in your containers. See
> [`restock`](#restock--fetch-blocks-for-your-litematica-build).

## Why Xploits

- **`crystal-aura++` goes for the kill, and never kills you.** With the opponent at the edge of death it
  finishes him at every risk level, where 0.7.0 dealt nothing there (7 of 9 runs in the 0.8.0 bench; in
  the other two it dealt no damage at all, as Meteor's aura also sometimes does in that scene). It goes
  past your reserve only for a kill, only while you hold a totem and carry a spare, and never below 2
  health in the other cases.
- **It deals more than Meteor's own `crystal-aura` where it counts.** With the opponent coming and going,
  38 damage against 31; with him above you, 297 against 287; below you, 309 against 303; behind cover,
  500 against 490. Where it is behind (while you move, and the open-ground exchange) it says so: see the
  [known issues](docs/known-issues.md).
- **Your own crystals never took you below your reserve in any run of the 0.8.0 bench**: 81 scenarios,
  three runs of each measured one, at every risk level, against opponents that attack, break your
  crystals and block your spots. Meteor's `crystal-aura` went down to 0.1 health in the same bench.
- **`auto-pvp` switches the right combat modules on at the right moment** — crystals, traps, webs,
  surround, hole-filling and the rest — and only turns off the ones it turned on.
- **`fight-recorder` tells you why you died**: a JSON file per fight with the damage split, your totems
  and crystals, and its best guess at the cause. No positions are stored.
- **`auto-travel` flies you somewhere without leaving an arrow pointing at your base** (a decoy pattern
  breaks up your trail), and **`nether-sweep`** combs the Nether to find other people's bases.
- **No module does half a job silently.** If it cannot deliver what it promises, it refuses and tells you
  which setting to change, instead of doing something similar and keeping quiet.

`crystal-aura++` is **measured, not just claimed**: every release is benchmarked in-game against Meteor's
own `crystal-aura`, and the numbers are in
[the table below](#crystal-aura--crystal-aura-with-a-floor-under-your-health).

## Screenshots

![auto-pvp driving crystal-aura++ against the bench's sparring partner, the moment its totem pops](docs/images/fight.jpg)

`auto-pvp` driving `crystal-aura++`, the moment the opponent's totem pops. Top left, the `xploits-pvp` HUD
panel: profile and posture, target and distance, the modules it turned on, your resources and the live fight.

| | |
|---|---|
| ![The Xploits category in Meteor's ClickGUI](docs/images/clickgui.jpg) | ![crystal-aura++'s own settings](docs/images/crystal-aura-pp.jpg) |
| The **Xploits** category, next to Meteor's own in the ClickGUI. | `crystal-aura++`'s own settings: `risk`, `self-budget` and `finishing-blow`. |

![The Xploits console window](docs/images/console.png)

The `console` window: the logo, the game's status (health, armour, what is in your hotbar, which modules are
on) and everything the modules say, with no coordinates. Here, `fight-recorder`'s summary of the fight that
just ended.

All four are taken by the bench in a test world, with no real player or server on screen, and are taken again
for each version (`tools/shots.ps1`).

---

## Before installing

**Copy `xploits-<version>.jar` into your instance's `mods/` folder**, next to Meteor, and **delete
any older `xploits-*.jar`**: the file name carries the version, and with two of them the addon loads
twice. Restart the game. The modules show up in the ClickGUI, under the **Xploits** category. What
changes in each version: [CHANGELOG](CHANGELOG.md).

### What you need installed, and what stops working without it

This addon **relies on other mods on purpose**: when something is already solved, and solved well,
it uses that instead of reimplementing it worse. The price is that you need to have them.

| Mod | Required for | If missing |
|---|---|---|
| **Meteor Client 1.21.11** | Everything | The addon does not load |
| **[Baritone](https://github.com/cabaletta/baritone)** | `auto-travel`, `nether-sweep`, `restock` | `auto-travel` and `nether-sweep` **refuse to launch**, `restock` **refuses to start**, and each says so. The other modules work the same |
| **[Litematica](https://modrinth.com/mod/litematica)** 0.26.14, with its library **malilib** | `restock` | `restock` **refuses to start** and says so. The other modules work the same; Xploits loads without it |
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
| `auto-pvp` | The crystal aura `crystal-module` selects — Meteor's `crystal-aura` by default, Xploits' own `crystal-aura++` with `xploits++` — plus `auto-trap`, `auto-web`, `auto-anvil`, `auto-city`, `surround` and `hole-filler` (or Xploits' own `surround++` in their place, with `shell-module` set to `xploits++`), `anti-anvil`, `anti-bed`, `anti-anchor` | Turns them on and off depending on the situation. **It only turns off the ones it turned on**: if you touch one by hand, it stops touching it. It never touches the aura `crystal-module` did not select |
| `auto-pvp` | Your **Meteor friends list** | Adds your people while it is on, so the five combat modules do not attack them either. When turned off it removes **only the ones it added** |
| `auto-pvp` | `crystal-aura`'s `anti-suicide` setting | It only **reads** it, to know whether it can trust Meteor not to kill you with your own crystal |
| `surround++` | `burrow` | Only with its own `burrow` setting on (off by default): turns it on for one burrow, and Meteor's `burrow` turns itself off after it |
| `auto-travel`, `nether-sweep` | `elytra-fly`, `elytra-replace` | Borrows them during the flight and gives them back **in the state they were in** |
| `auto-travel`, `nether-sweep` | Five **Baritone** settings | Changes them on take-off and restores them on landing. ⚠️ Baritone saves them to disk — as it does `elytraTermsAccepted`, `elytraPredictTerrain`, `elytraNetherSeed` (if set) and its own censoring, which stay changed for good |
| `restock` | Five **Baritone** settings | While it is on: `allowBreak`, `allowPlace` and `allowWaterBucketFall` off, so Baritone breaks and places nothing on the way, `censorCoordinates` and `censorRanCommands` on. When it stops, all five go back to your own values (`baritone-settings`: `MINE`, the default), or the first three to Baritone's defaults (`DEFAULTS`). ⚠️ Baritone saves them to disk; if the game closes while it is on, `restock` puts them back the next time you join a world |
| `restock` | `litematica-printer`'s print mode | Switches it off before a trip or an unpack and back on afterwards, **only if it was printing and is still off** (if you turned it on or off yourself meanwhile, yours wins). If the game closes mid-trip or mid-unpack, it is switched back on at the next world join |
| `restock` | `anti-afk`, `auto-walk`, `auto-replenish`, `inventory-tweaks`, `scaffold`, `air-place`, `nuker`, `highway-builder`, `liquid-filler`, `excavator`, `infinity-miner`, `echest-farmer`, `spawn-proofer`, `timer`, `speed-mine` | It only **reads** them: it will not start while one is on, and stops, naming it, if you turn one on while it is on |

All of this, with the detail of what persists and what can go wrong, in [Security](docs/security.md).

---

## The twelve modules

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
of you —placed crystals, people with swords—, not with a totem counter. Once something is aimed at you it
stays on guard until nothing has been for 3 seconds, so its defensive modules do not go off between two
crystals. The crystal aura `crystal-module` picks stays on for as long as `auto-pvp` is: turned on only once
an opponent was in range, it started cold and missed the first crystals placed against you.

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

**`crystal-module` picks which crystal aura `auto-pvp` drives**: `meteor` (default) drives Meteor's
`crystal-aura` exactly as before; `xploits++` drives Xploits' own `crystal-aura++` instead — see
below. With `xploits++`, running out of totems no longer turns the aura off while its self-budget is
still on: breaking the enemy's crystals is enough to keep you alive without one in hand.

`auto-pvp` also keeps asking for `surround` for a short while after a block of your hole has just been
broken, not only while the hole is still whole: Meteor's own `surround` refills the four sides, never
the block under you, so this closes a gap it used to miss.

**`shell-module` picks the defence `auto-pvp` keeps you in**: `meteor` (default) is Meteor's `surround` and
`hole-filler` as before; `xploits++` is Xploits' own `surround++` in their place — see below. With `xploits++` it
stays on for as long as `auto-pvp` is, like the crystal aura, because it only places where something can hurt you, and
it fills your opponents' holes itself, so `hole-filler` is not used. It stays at `meteor` by default until the lab also
plays an opponent that places crystal bases itself (the lab's attackers never do) and the release bench confirms
`surround++`.

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

### `crystal-aura++` — crystal-aura with a floor under your health

Meteor's `crystal-aura` behaviour — the same placing and breaking rules, the same defaults — plus a
self-damage budget: it places a crystal only if your health (plus absorption) would stay at or above a
reserve were that crystal and every other one that can still hurt you to go off, and it breaks one of
your own crystals only if that leaves you at least 2. The one exception is `finishing-blow`, below. It
counts every crystal that can still hurt you, already placed or on its way to exploding, and works out
the exact damage a hit would deal (Meteor rounds it down).

> **Experimental in 0.8.0.** It kept your health above the reserve in every measured run, but it is still
> being tuned: in some situations it deals less damage than Meteor's `crystal-aura` (see the table and the
> known issues below). The next releases keep improving its attack.

**Use it from `auto-pvp`'s `crystal-module` setting** (`meteor` default | `xploits++`), or turn
`crystal-aura++` on by itself. **It does nothing while Meteor's `crystal-aura` is on** — two auras
would fight over the same crystals — and says so.

**`risk`** sets how much health the budget keeps in reserve: **Balanced** (keeps 3.5, the default and
recommended), **Safe** (keeps 5, experimental), **Aggressive** (keeps 2, experimental) and **Custom**
(the `reserve` setting). Breaking one of your own crystals must still leave you at least 2, whatever
`risk` says.

**What it adds on top of Meteor:**

- Works out the exact damage a placement would deal, instead of rounding it down.
- Between two spots that would deal the target the same damage, picks the one that hurts you less.
- Does not place a crystal the target's own damage cooldown would swallow — only when it is sure.
- Keeps the reserve while you move, by assuming the worst spot you could reach before the crystal
  explodes. Since 0.7.1 it measures how exposed you would really be at each of those spots, instead of
  assuming full exposure, so you get more damage at height differences and behind cover. A spot inside a
  block still counts as fully exposed.
- **Up close, the reserve decides.** With the self-budget on, Meteor's `max-damage` no longer limits your
  own crystals: your reserve does. Other players' crystals keep `max-damage`, and so does everything
  when the budget is off.
- **Never stuck.** It no longer freezes behind one of its own crystals that it may not break.
- **Goes for the kill** (`finishing-blow`, below).

**`finishing-blow`** (on by default, at every `risk` level; it needs `self-budget` on) lets a crystal that
finishes the opponent go past the reserve:

- **To kill him** (he has no totem in either hand, his hands are visible, and one of your own hits has
  confirmed his health), a crystal may take you below your reserve or pop your totem. It does so only
  while you hold a totem **and** carry a spare — never your last one — and only one such crystal is out
  at a time.
- **To only pop him** (he holds a totem, or his hands are hidden), it may take you below the reserve only
  at **Aggressive**, and never below 2 health and never popping you. At every other level the reserve
  holds.
- **On servers that hide other players' health** (2b2t-style plugins) it does nothing: his health is
  never trusted.

**Measured** (the 0.8.0 release bench, 2026-10-01; 100 ms simulated ping; unlimited crystals; natural
health regeneration only where noted; median of 3 runs; the sparring opponents in the table's first rows
never attack, and in the fights at the bottom the opponent attacks back). First rows: damage dealt /
your lowest health across the 3 runs, Balanced against Meteor's `crystal-aura`. Fight rows: result
(wins-draws-losses of 3), net totem pops (pops you dealt minus pops you took) and the time of the first
blow on the opponent, where measured:

| Situation | Meteor | Balanced |
|---|---|---|
| Opponent standing still | 30 / 3.4 | 30 / 3.5 |
| Opponent circling | 20 / 3.7 | 20 / 3.9 |
| Standing still, with regeneration | 40 / 0.2 | 40.8 / 3.5 |
| Circling, with regeneration | 30 / 0.6 | 28.8 / 4.4 |
| Opponent 3 blocks higher | 287 / 18.8 | 297 / 18.8 |
| Opponent 3 blocks lower | 303 / 18.8 | 309 / 18.8 |
| Opponent coming and going | 31 / 0.5 | 37.7 / 3.5 |
| Opponent dodging sideways | 30 / 0.3 | 30.9 / 3.5 |
| You walking in circles | 40 / 0.1 | 30.8 / 4.3 |
| You dodging, opponent circling | 30 / 0.2 | 27 / 4.0 |
| Behind cover | 490 / 18.0 | 500 / 17.9 |
| You jumping under a ceiling, with regeneration | 90 / 0.2 | 71.9 / 6.5 |
| Fight: opponent almost dead, no totem | 3-0-0, net 0, 0.45 s | 2-1-0, net 0, 0.58 s |
| Fight: opponent almost dead, with totem | 3-0-0, net 6, 0.5 s | 3-0-0, net 6 |
| Fight: open-ground exchange | 2-1-0, net 8 | 0-3-0, net 2 |
| Fight: both in a hole | 0-3-0, net 0 | 0-3-0, net 0 |
| Fight: opponent mining your hole | 0-3-0, net 1 | 0-3-0, net 0 |

In every run of every level the reserve held against your own crystals: no death and no totem lost to
one of them (a finishing blow that kills may go past it, as described above). In the fights the
opponent's own crystals can take you lower than the reserve, which only guards against yours. With
Meteor's `crystal-aura` your health went down to 0.1.

**Known issues:**

- **While you move** it is deliberately cautious: at Balanced, walking in circles it deals 31 against
  Meteor's 40 (20 at Safe), dodging 27 against 30 and takes the first totem later, and jumping under a
  ceiling 72 against 90 while keeping you at 6.5 where Meteor went down to 0.2. Sizing that caution
  from how far you really move is planned for a later version.
- **Against an opponent who moves around you** Balanced can take the first totem later than Meteor: it
  refuses crystals that would take you below 3.5.
- **In an open-ground exchange** (both of you attacking, no cover) it still draws where Meteor wins.
  Part of that is the bench: explosions knock your player out of range and it never walks back
  (a fix is planned for a later version).
- **On servers that hide other players' health** there is no finishing blow.
- **A crystal of yours that appears late** (lag) is treated as someone else's and, when it would hurt you
  more than `max-damage`, may stay unbroken.
- **Not measured yet:** several enemies at once.
- **Safe and Aggressive** are experimental: Safe keeps more health and deals clearly less damage;
  Aggressive keeps 2.

**Recommended settings:**

- `auto-pvp` → `crystal-module`: `xploits++` if you want the health floor; `meteor` (the default) if you
  prefer Meteor's damage while this is tuned.
- `crystal-aura++` → `risk`: **Balanced**.
- **Turn Meteor's `crystal-aura` off** while you use `crystal-aura++` (it does nothing while both are on).
- If you stay with Meteor's `crystal-aura`, keep its **`anti-suicide` on**: without it and without totems,
  `auto-pvp` switches it off (breaking included) to protect you.
- **Turn `fight-recorder` on** so a lost fight can be looked into.
- Carry totems, obsidian and crystals in the hotbar: `auto-pvp` does not turn on a module without its
  material.

### `surround++` — a shell worked out, not a pattern

Meteor's `surround` puts four blocks around your feet and, in a real fight, works against you: it turns itself off
whenever your height changes (webs and explosions change it), and with `center` on it pulls you back to the middle of
the block every tick while the hole is broken. And it leaves your head uncovered, which is where hacked clients put
their crystals. `surround++` works out, every tick, where a crystal that can hurt you could go, and covers those spots
first:

- **The most dangerous spot first**, by the exact damage a crystal there would deal you (the calculation
  `crystal-aura++` uses), and only the spots an opponent can reach. A spot counts from 1 damage; one where the
  opponent would still have to place a base first weighs an eighth of one whose base is already there.
- **Crying obsidian where plain obsidian would give them a new base**: a crystal cannot stand on crying obsidian, and
  it takes as long to mine. Keep some in your hotbar (`use-crying-obsidian`); without it, plain obsidian.
- **Anti-city**: a mined wall is refilled the tick it opens, and a crystal they put next to you is broken when its
  blast leaves you 2 health or more (`break-crystals`).
- **Never pins you**: it centres you only when you stick out of your block and something is left open, never while
  you press a key, at most once a second. It never turns itself off when your height changes. While you walk (a movement key held and your feet actually moving, crawling
  through a web included) it only breaks crystals; held still against a wall, it keeps refilling.
- **A better hole**: in the open and threatened, it walks you into a hole one block down within 3 blocks, bedrock walls
  first, if there is room for your whole body and head room over the hole (`move-to-hole`). Your own keys always win.
- **Their holes**: it fills the holes next to your opponents, the nearest to them first (`deny-holes`).
- **Burrow**, off by default (`burrow`): many anticheats kick for it.
- **After a totem pop** it closes the shell completely for 5 seconds, roof included.

`blocks-per-tick` (2 by default) caps how many blocks it places in one tick; lower is safer against anticheats. `reach`
(6) is how far your opponents are assumed to reach. Drive it from `auto-pvp`'s `shell-module`, or turn it on by itself;
with Meteor's `surround` or `self-trap` on at the same time it warns you, since both place in the same cells. It does
not break webs or eat for you yet.

In the lab (2 and 3 hacked attackers, 60 s, 3 runs each) you lost as many totems with it as with Meteor's `surround`
and `self-trap`, with the same or one fewer crystal reaching your head, for about one block more (the crying obsidian it opens
with). It is a like-for-like result, not an
improvement. Its decision costs 0.7-0.9 ms a tick on average.

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

Opens a terminal window with the Xploits logo at the top, the game status below it and the log of
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

### `restock` — fetch blocks for your Litematica build

**Experimental.** Build as you do today, with [Litematica](https://modrinth.com/mod/litematica) and
`litematica-printer`, or by hand. Select the placement in Litematica, mark your chests and turn `restock` on:

- **Mark the containers it may use.** Bind `mark-key`, stand on the ground, look at a chest, trapped or copper chest,
  barrel or placed shulker box and press it: "Marked (N in total)."; press again to unmark (it works even if the container
  is gone). It works with `restock` on or off, and remembers the spot you stood on, per world.
  `.xploits restock chests` lists them by dimension and distance, never by position. With `use-stash-keeper` on
  (the default) it also uses the containers `stash-keeper` remembers.
- **When it goes.** When a block the rest of the build still needs reaches 0 in your inventory, it waits two full
  passes over the build (so the block `litematica-printer` placed a moment ago is counted), then unpacks a shulker
  box you carry that holds it (below) or picks the nearest container that has it, in the same dimension and within
  `max-distance` (64). A source that is no longer a container (the chest was replaced) is skipped, never clicked. It
  goes only for a block Litematica shows missing somewhere in the build (with Litematica's rendering off, or that part
  of the build not loaded, it waits), and only once you stand on the ground: it never leaves while you walk, sneak or
  jump.
- **The trip.** It switches `litematica-printer`'s print mode off, walks there with
  [Baritone](https://github.com/cabaletta/baritone) (Baritone breaks and places nothing on the way), stands still,
  looks at the container and opens it, takes whole stacks of what the rest of the build needs — as much as fits,
  never a shulker box with something in it as a building block (one that holds a block the build needs is carried back
  whole and unpacked, below) — closes it, walks back to where you were and switches the print mode back on. A
  container whose contents changed is noted and the next nearest is tried in the same trip. It never takes or closes
  with an item on your mouse cursor: it waits until you put it down.
- **Nowhere to fetch from:** it says which block and how many are missing, goes nowhere, and the printer keeps
  printing everything else.
- **Shulker boxes you carry** (`use-carried-shulkers`, on). When the block that ran out is inside a box you carry,
  `restock` unpacks that box at the build before it goes to any container, once you stand on the ground as for a
  trip: it switches the printer off, sets the box down next to you, opens it, takes what the build needs while keeping
  one slot free for the box, breaks it at normal speed with your best hotbar tool (never a sword, axe, spear, mace or
  trident, nor a tool with fewer than 10 uses left), picks it up, selects your hotbar slot again and switches the
  printer back on. A box in your hotbar goes first. One in your main inventory is used only while a hotbar slot is
  free, and `restock` moves it there with one Shift-click: the only click it ever makes in your own inventory. With no
  hotbar slot free it says so once and fetches from the containers instead. Your own boxes are always picked up again
  and stay with you; with `use-carried-shulkers` off, only the boxes `restock` took from your containers are
  unpacked. The box is the only block `restock` ever places or breaks.
- **Shulker boxes in your containers.** A container that has the block only inside shulker boxes works too: the trip
  carries the whole box back, the one holding most of it, and `restock` unpacks it as above, with the printer still
  off. A container that has the block loose is chosen first, even if it is farther. A box is carried only while a
  hotbar slot and one more slot are free; with less room `restock` passes that container over, tries it again once
  there is, and says so only if no other container has the block. A box it took from a container goes back there once
  it is empty, on the next trip to that container or on one last trip when the build is done, and never more boxes
  than it took from it. Until then the box stays with you, and every stop says how many you carry (save in the rare
  cases of "`restock` can lose count of a borrowed shulker box" in [known issues](docs/known-issues.md)).
- **Where it sets a box down.** Within your reach and outside the build: on a solid block that does nothing when
  clicked (not a chest, furnace, crafting table, door, lever, bed…), on an empty spot with room above it for the lid,
  never where you stand. It also checks where the broken box will land, because the drop can drift a block or more,
  and refuses a spot with water, lava, fire, a cactus, a hopper or a portal next to it, on ice or slime (the drop
  would slide away), with an edge or a hole beside it, or with a block beside it that is neither a full block nor open
  space (a slab, stairs, a fence, a wall, a pane, a door, scaffolding…). If no spot passes it stops and says so. If
  the server does not accept the box (spawn protection, a claim plugin) it tries two more spots and then stops, with
  the box still in your inventory.
- **If something stops it while a box is out**, `restock` first finishes breaking the box and picking it up (up to
  10 s) and then stops: a stranger coming near, a module it cannot run beside, another module that keeps turning your
  head. Some stops cannot wait and happen at once: you are attacked, your health falls below `min-health`, the server
  sets you back, `auto-pvp` engages, you press a movement key, you die, change dimension, leave the server or turn
  `restock` off. If one of those comes while it is finishing, it stops at once and says both (turning it off or
  leaving says only what is left out). Every stop says how many
  boxes it set down still stand and how far away the nearest is, how far away the dropped box lies or that it is gone,
  when it could not check, and how many boxes it took from your containers you still carry: distances only, never a
  position.
- **It stops and says why** when a player who is not your Meteor friend comes within `player-distance` (48; switch
  `stop-near-players` off to fetch with players around), a player who is not your friend attacks you, your health falls
  below `min-health` (10), the server pulls you back, `auto-pvp` engages, you die or change dimension, **you press a
  movement key during a trip or an unpack**, Baritone finds no path, nothing fits in your inventory, a shulker box
  cannot be moved into your hotbar, set down, broken or picked up, you turn on a module it cannot run beside, or
  something unexpected fails inside it (it stops with a message instead of crashing the game). After a stop during a
  trip or an unpack the printer stays off, and it says so. It **pauses** by itself, and carries on, while a combat
  module rotates, while you eat and while the server lags.
- **It refuses to start** if two Litematica placements overlap (the selected one and any other enabled one) or if
  the installed Litematica has an API it does not recognise; it says so instead of guessing.
- **It never starts by itself.** If it was on when you left, it stays off when you join again.

It was tested on 1.21.11 in the bench, which has no Baritone and no `litematica-printer`; see the
[known issues](docs/known-issues.md) for what only a real server can show.

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
- **The shell**, while `surround++` is on: whether your head is covered, the spots still open and the blocks next to
  you being mined — amber while a block is being mined, or while your head is open with a spot left.
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
| `.xploits restock` · `status` | What `restock` is doing and what the build is short of |
| `.xploits restock chests` · `chests clear` | Your marked containers by dimension and distance, or remove every mark of this world |
| `.xploits language [auto\|es\|en]` | Active language, or sets it |
| `.xploits reload` | Reloads the saved data |

---

## What to know about the two that fly

**They cannot be used at the same time.** Both drive the same Baritone and `#elytra` only takes one
goal: the second would take it from the first, the first would cut out after 45 seconds with its own
restoration —stopping the second's flight halfway— and the second would diagnose a false stall.
Each one checks for the other and **refuses to launch** while the other is flying. They are used in
the same outing: you fly with `auto-travel`, stop it when you arrive, and sweep.

**Nor while `restock` is on.** It drives the same Baritone: neither of the two launches while it is on, and it will
not start while one of them flies.

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
<instance>/meteor-client/xploits/restock/  Marked containers per world; Baritone values and the print mode to give back
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
`censorRanCommands`) before the first goal, and leave it on. `restock` turns it on while it is on and gives
you back your own values when it stops.

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

## Coming soon

- **Building, next, before 1.0.0:** map art, tunnels and highways, and bases.
- **Available now, experimental:** `crystal-aura++` — Meteor's crystal-aura with a self-damage budget
  that keeps a health reserve; `surround++` — a computed defensive shell, from `auto-pvp`'s
  `shell-module`.
- **Next:** the second part of `auto-pvp`'s critical fix, the survival response (eating after a totem
  pop, getting out of webs, leaving a broken hole), and `surround++` as the default once it has been
  tested against attackers that place their own bases. Then an attack mode for `crystal-aura++`,
  `Maximum` — places crystals even when they would do little damage, drops obsidian when there is no
  spot to place on and an easier face-place — and tighter tuning while you move: the reach radius
  sized from how far you really move.
- **Then, a full fight:** self-protection first — notice the instant your hole is being mined and
  patch it at once, and decide what to do if a crystal is already sitting in the gap; respawn anchors,
  both attacking with them and a defence of our own, since Meteor's `anti-anchor` is not in this
  build; smarter webs; beds in the Nether and the End; and all of it tested in every situation — you
  in the open, in a hole, trapped, underground or moving; the opponent above you, below you, standing
  still, dodging or attacking; golden apples; several enemies at once.
- **Later:** more `++` modules wherever Meteor's own fall short, and the fight director tuned against
  recorded real fights.

---

## Upgrading from old versions

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

**Artwork and screenshots:** `java tools/ArtRenderer.java` draws the icon, the banner and GitHub's social preview
from the console's logo; `pwsh -NoProfile -File tools/shots.ps1` takes the screenshots in the bench (a game
window and the console window open while it runs).
