# Changelog

All notable changes to Xploits. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

## [0.6.1] — 2026-09-25

### Fixed

- `fight-recorder`: a fight whose last exchange was a totem pop kept the used totem in its end
  totals, because the server sends the pop before the inventory update. The review then said you
  still had totems, which could blame the wrong cause (double pop, unused totems). The end totals now
  read the inventory for a quarter of a second after the last exchange.
- `.xploits pvp review` left "Modules at start:" blank when no module was on; it now says "none".

## [0.6.0] — 2026-09-25

What is new, in short:

- **Style profiles for `auto-pvp`**: switch between fighting styles with one key.
- **Choose which modules `auto-pvp` may use**: ten on/off toggles in the ClickGUI.
- **A HUD panel** that shows what `auto-pvp` is doing, live.
- **`fight-recorder` records the style** each fight was fought with.

### Added

- **Style profiles.** A profile sets `auto-pvp`'s `target-range`, `approach-distance` and
  `threat-margin`, and which modules it may use. It never changes the settings inside
  crystal-aura, surround or any other module.
  - Three built in, all editable:

    | Profile | target-range | approach-distance | threat-margin | Modules |
    |---|---|---|---|---|
    | `balanced` | 16 | 6 | 12 | all (same as before 0.6.0) |
    | `aggressive` | 24 | 4 | 8 (defends later) | all |
    | `defensive` | 12 | 6 | 16 (defends sooner) | all but `auto-city` and `auto-anvil` |

  - Up to 20 of your own: set things up as you like, then `.xploits pvp profile save <name>`.
  - **`next-profile`** key (in `auto-pvp`'s settings) cycles the profiles in a fight.
  - Changing a value by hand marks the active profile as modified (`aggressive*`) until you save it.
  - Commands, with `auto-pvp` on or off: `.xploits pvp profile` (active one),
    `profile list`, `profile use <name>`, `profile save <name>`, `profile delete <name>` (a built-in
    goes back to its factory values), `profile reset-file`. Names are suggested as you type.
  - Kept in `meteor-client/xploits/pvp/profiles.json`. A damaged file is never overwritten: the
    built-ins keep working and saving is refused until `profile reset-file`.
- **Allowed modules.** A new `Modules` group in `auto-pvp` with one toggle per module it manages
  (`use-crystal-aura`, `use-auto-trap`, `use-auto-web`, `use-auto-anvil`, `use-auto-city`,
  `use-surround`, `use-hole-filler`, `use-anti-anvil`, `use-anti-bed`, `use-anti-anchor`). All on by
  default, which is the behaviour before 0.6.0. Turning `use-crystal-aura` off warns you that you
  lose the automatic breaking of enemy crystals.
- **`xploits-pvp` HUD panel.** Add it from Meteor's HUD editor (group **Xploits**). It shows:
  - a red danger line when something critical is missing (no totems in a fight, out of resources, a
    module idle for lack of a resource, crystal-aura without crystals);
  - profile, phase and posture;
  - target and distance (never a position);
  - modules on (green), released by you (amber) and off by the profile (grey);
  - crystals, totems and obsidian, in amber when short of what the fight needs;
  - the fight in progress from `fight-recorder`: seconds, pops on each side, damage taken.

  Settings: `scale`, `shadow`, `background`, `show-fight`. With `auto-pvp` off it shows one line
  with the active profile.
- **Starscript**: `{xploits.pvp.state}`, `{xploits.pvp.posture}`, `{xploits.pvp.profile}`,
  `{xploits.pvp.target}`, `{xploits.pvp.distance}` for your own text HUD lines.

### Changed

- `auto-pvp` skips a module the profile does not allow, and never keeps a shared resource (for
  example obsidian) reserved for it. The loud "out of resources" warning now counts only the modules
  the profile allows, so a defensive profile does not trigger it every fight. Modules skipped by the
  profile are not announced in chat; the panel shows them.
- `.xploits pvp` status shows the active profile and lists the modules the profile turned off on one
  line.
- `.xploits pvp review` shows the profile a fight started with, and profile and module changes in
  time order (the last eight).

### How to start

Nothing changes until you use it: after updating, every module is still allowed and `auto-pvp`
behaves as in 0.5.0. Add the `xploits-pvp` panel from the HUD editor, bind `next-profile` if you want
a key, and try `.xploits pvp profile use defensive`.

## [0.5.0] — 2026-09-25

### Added

- **`fight-recorder`** — records every fight, with `auto-pvp` on or off, and works out why you died.
  No positions are stored: distances, counts, booleans and names only. Off by default; turn it on
  once and it keeps the last 50 fights in `meteor-client/xploits/pvp/fights/`. `death-notice`
  (default on) says the main probable cause in chat when you die; `live-console` (default on) writes
  the fight to the Xploits console as it happens and its summary when it ends.
  `.xploits pvp review [n]` shows the full breakdown of a recorded fight, `.xploits pvp fights`
  lists the last ten. Both work with the module off.

### Changed

- The README is now in English, with a Spanish copy in README.es.md; developer docs are English.

## [0.4.0] — 2026-09-24

### Fixed

- `stash-keeper`'s "indexed a container" chat line showed only "Xploits" instead of the container it
  indexed.

### Changed

- The code base is now English: packages, types, members, comments, javadoc, test names and
  exception/log messages. Player text — the catalogs, chat, toasts and the console window — stays in
  whichever language `.xploits language` has active. What did change for players is listed below: the
  module name, the setting group names, two setting ids, the `pattern`/`destination-mode` values, the
  console window's title and the console's file names. Technical error details shown inside messages
  (exception text) are now English.
- The `consola` module is now `console`. Its setting groups are English too:
  - `auto-travel`: `Patrón` → `Pattern`, `Vuelo` → `Flight`, `Avisos` → `Notify`.
  - `nether-sweep`: `Pasada` → `Lane`, `Cohetes` → `Fireworks`, `Vuelo` → `Flight`, `Avisos` →
    `Notify`.
  - `auto-travel`'s `quiebro-leg`/`quiebro-offset` settings are now `swerve-leg`/`swerve-offset`.
- Setting values renamed: `pattern` `RECTO` → `STRAIGHT`, `QUIEBRO` → `SWERVE`, `ESPIRAL` →
  `SPIRAL`, `SENUELO` → `DECOY` (`ZIGZAG` unchanged); `destination-mode` `COORDENADAS` →
  `COORDINATES`, `RELATIVO` → `RELATIVE`, `AUTOPISTA` → `HIGHWAY`.
- The console's on-disk files moved to `meteor-client/xploits/console/`: `live.log` (plus
  `live.1.log`), `history/`, `console.lock`/`console.pid`/`console.exit`, `console-errors.log`,
  `size.txt`. `live.log` is now format version 3. The window's title is now "Xploits console".

### Migration

- The rename above is applied automatically, once, the first time 0.4.0 starts: `modules.nbt` (main
  and per profile) is rewritten with the new module, group, setting and value names; `hud.nbt` (main
  and per profile) only has module names renamed (it holds them as plain strings); and the
  `xploits/consola/` folder is moved to `xploits/console/` with its files renamed to match. Every file rewritten is backed up
  first, as `<file>.pre-0.4.0.backup.nbt`, and never overwritten if a backup already exists. A chat
  message is shown when settings files were rewritten or when something could not be migrated (a
  settings file that failed, or a console file still in use). The migration is kept for at least two
  MINOR versions, then removed.
- `config.nbt` is not migrated — Meteor loads it before any addon runs, so a rewrite would be
  discarded. If you had hidden the console module from Meteor's module list under its old name, it
  can reappear once; hide it again under `console` and it stays hidden from then on.

### Notes

- Close any console window still open from 0.3.x before starting 0.4.0. If one is still open, the
  `xploits/consola/` folder is locked on that start and the console may create `xploits/console/`
  alongside it; on a later start, once the old window is closed, the old folder is merged into the new
  one — its history goes into `console/history/` (a day present in both keeps both files, the old one
  as `<day>-old.log`), nothing is overwritten, and `xploits/consola/` is removed once empty. Anything
  still in use is left in place and retried on the next start.
- Meteor macros (`macros.nbt`) that type `.toggle consola` are not migrated: edit them to `console`.

## [0.3.1] — 2026-09-24

### Fixed

- Coordinates no longer reach Minecraft's `latest.log`. Minecraft copies every chat line there, so
  Baritone's goal echoes, Xploits' own launch and status messages and other mods' finds wrote the
  player's position to disk.
  - `auto-travel` and `nether-sweep` turn on Baritone's `censorCoordinates` and `censorRanCommands`
    before the first goal. They stay on after landing: Baritone saves them to disk, and turning them
    off would undo a censor the player already had.
  - A new `hide-coordinates-in-log` setting in the `xploits` module masks coordinates (`***`) in the
    logged copy of chat lines and in Baritone's region-file lines (`Saving region x,z`). The chat on
    screen is unchanged. Values: `Off`, `Baritone`, `All` (default), `All but Baritone`.

### Added

- `consola` has a `hide-coordinates` setting, on by default. Turned off, the window and its history
  show the same text as the chat, positions included, and keep them on disk.

### Notes

- Logs written before this version are not cleaned.
- If another mod replaces how Minecraft logs chat, the game will not start with Xploits: hiding
  coordinates fails closed.

## [0.3.0] — 2026-09-23

### Added

- A language selector: the `xploits` module's `language` setting (`Auto` / `Español` / `English`)
  and `.xploits language auto|es|en` to read or change it. `Auto` follows Minecraft's language —
  any `es_*` gives Spanish, anything else gives English. Chat, toasts and the console window switch
  at once; ClickGUI descriptions after a restart. Every addon text now exists in both languages.

### Changed

- The console's `vivo.log` is format version 2. A console window left open from 0.2.0 must be
  closed and reopened to pick up the new format; the window takes its language at launch and
  follows later changes.
- Numbers in addon text now use the active language's decimal separator (`18,5` in Spanish,
  `18.5` in English).

### Upgrade note

With Minecraft in English, the addon now starts in English by default. If you want Spanish from
the start, set it once with `.xploits language es`.

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

All of them, with causes and pending fixes, in `docs/known-issues.md`.

## [0.1.0]

Unversioned development. Every build before 0.2.0 was called 0.1.0.
