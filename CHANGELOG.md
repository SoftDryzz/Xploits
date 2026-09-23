# Changelog

All notable changes to Xploits. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

## [0.2.0] — 2026-09-23

First numbered release. It describes everything Xploits does at this point.

Requires Minecraft 1.21.11 and Meteor Client. `auto-travel` and `nether-sweep` also need Baritone;
`nether-sweep` relies on Trouser Streak's `BaseFinder` and `NewerNewChunks` and on Meteor's
`stash-finder` to detect and record what it flies over.

### Added

- **`auto-travel`** — elytra travel through Baritone along a route with an evasion pattern, so your
  trail is not a straight line pointing home.
  - Five patterns: straight, zigzag, wide dodge, spiral on arrival, decoy on departure.
  - Three ways to set the destination: coordinates, an offset from where you start (leaves the
    least trace on disk), or a highway axis and a distance.
  - Eight highway axes, the four straight ones and the four diagonals; the distance is always
    blocks flown.
  - Baritone is handed the next waypoint before it reaches the current one, so it no longer lands
    at every waypoint of the pattern.
  - A safety net cancels any Baritone command that would otherwise leak into public chat, and says
    so.
  - Arming it does not fly: the trip starts with `.xploits travel go`. It refuses to launch, and
    says which setting to change, whenever it cannot do what it promises.
- **`nether-sweep`** — flies a rectangle of the Nether in mower passes so the terrain loads on your
  client, for base-hunting mods to see. It plans only over chunks you have not covered yet, measures
  the server's view distance to space the passes, projects fireworks before and during the flight,
  and reports how much it really covered when it lands.
- **`auto-pvp`** — turns Meteor's combat modules on and off as the fight changes, judging both the
  enemy's phase and the damage already aimed at you. It only turns off what it turned on, never
  attacks your friends, couriers or TPA list, adds them to Meteor's friends while it runs, and warns
  when a module it directs is on but doing nothing.
- **`elytra-replace`** — swaps the worn elytra before it breaks, choosing the most worn spare that is
  still above your minimum.
- **`kit-requester`** — requests kits from SnifferBuddy in batches and accepts the courier's TPA.
- **`auto-tpy`** — accepts TPA requests from your list and your Meteor friends instantly; never
  accepts `/tpahere`.
- **`stash-keeper`** — remembers what was inside the containers you opened and the shulkers you saw;
  `.xploits find <item>` tells you where.
- **`consola`** — an external terminal window with the XTO2002 logo, the live game state and a
  filterable log of everything the modules say. It never shows coordinates, keeps up to 30 days of
  history on disk while it is on, and stays open when the game closes.
- **`.xploits`** command: `status`, `find`, `stash`, `pvp`, `travel` (`go`, `stop`), `sweep` (`go`,
  `stop`), `reload`.

### Known issues

- `kit-requester`: the kit bots have been down for a while. The module works; nothing will arrive
  until they are back.
- `nether-sweep`: the lane-width probe can over-measure right after a long flight lands, leaving
  unflown strips between passes. Turn the module on after landing and wait a few seconds before the
  measuring walk.
- `nether-sweep`: the consumed-link warning says the band is still crossed; that is false when the
  consumed link spans a whole band. Lower `waypoint-margin` or re-measure if you see it.
- `nether-sweep`: on small areas (long side between 10 and 19 chunks) the first pass can be consumed
  without being flown. Use areas well above 19 chunks.
- For all three, the coverage report on landing is the safety net: below 95 % it warns loudly.
- `auto-travel` / `nether-sweep`: after an abrupt disconnect mid-flight, Baritone's five elytra
  settings can stay at their flight values, which Baritone saves to disk. Check them with
  `#set <name>`.
- `auto-pvp`: `auto-trap`, `auto-city` and `auto-anvil` cannot be turned off by hand while `auto-pvp`
  directs them; turn `auto-pvp` off to keep them to yourself.
- `consola`: not tried in-game yet. It needs Windows Terminal with its default window behaviour.

All of them, with causes and pending fixes, in `docs/problemas-conocidos.md`.

## [0.1.0]

Unversioned development. Every build before 0.2.0 was called 0.1.0.
