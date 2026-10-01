# Changelog

All notable changes to Xploits. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

### Added

- The addon has its own icon, the X of the console's logo, where the mod list shows it (Mod Menu, the
  meteoraddons.com listing) instead of the default one.
- The README opens with the Xploits banner and shows screenshots: `auto-pvp` in a fight with its HUD panel,
  the ClickGUI, `crystal-aura++`'s own settings and the `console` window.
- **`surround++`**, a computed defensive shell: every tick it works out where a crystal that could hurt you would go
  and covers the most dangerous spot first, with crying obsidian where plain obsidian would give the opponent a new
  base; refills a mined wall the tick it opens; breaks an opponent's crystal next to you when its blast leaves you 2
  health or more; walks you into a better hole when you are exposed (your keys always win: while you walk (a movement key held and your feet actually moving, crawling through a web included) it only breaks crystals; held still against a wall, it keeps refilling); fills your opponents' holes;
  closes the shell completely for 5 seconds after a totem pop; `burrow` off by default. It never pins you to the centre
  of the block and never turns itself off when your height changes, the two ways Meteor's `surround` worked against
  you in the earlier worst-case lab runs (it switched itself off up to 43 times in a minute). In the lab (2 and 3 hacked attackers with an honest city, 60 s, 3 runs each) you lost
  as many totems with it as with Meteor's `surround` and `self-trap` (7 in the four fights), with the same or one
  fewer crystals reaching your head, for about one block more (the crying obsidian it opens with).
- `auto-pvp` has a `shell-module` setting: `meteor` (default: Meteor's `surround` and `hole-filler`) or `xploits++`
  (`surround++` in their place, on for as long as `auto-pvp` is). The HUD panel shows a shell line while `surround++`
  is on.

### Fixed

- `auto-pvp` no longer turns its defensive modules on and off several times a second in a crystal fight.
  It judged the danger from the crystals standing at that instant, which come and go with every crystal
  either side places and breaks, so it went from threatened to calm and back again between two crystals.
  Now it goes on guard at once and stands down only after 3 seconds with nothing aimed at you. In the
  bench scene that reproduced it, the defensive modules went from 10 switches in the first second to 2.
  This is the first part of the critical `auto-pvp` fix announced with 0.7.1; the survival response
  (a broken hole, a popped totem, webs, several attackers) comes next.
- `auto-pvp` keeps the crystal aura on for as long as it is on itself, instead of turning it on only once
  an opponent was in range. Turned on late, the aura started cold and missed the first crystals placed
  against you: in the owner's real fights, with the aura already on he won 21 totems to 4 against four
  opponents, and with `auto-pvp` turning it on as they arrived he lost 11 to 0. Without totems and with
  nothing protecting you, it still stays off.
- In a development run (`./gradlew runClient`, the in-game bench) the `console` window opens: it could not
  find its own classes there. The released jar was never affected.
- `crystal-aura++` no longer stops placing while a block is right over your head (your own shell's roof, or an
  opponent trapping your head). Its safety check counts every place you could reach before a crystal explodes, a
  jump included, and under a block that jump was read as a place fully in the open, so every spot looked too
  dangerous. A jump is now read where the block stops it, measured with the game's own collision; while an opponent
  is mining that block, or when it is one a crystal can blow away (anything weaker than obsidian, such as stone), the
  full jump still counts. On the bench, under a low ceiling it now places as often as
  Meteor's aura (median 472 placements in 30 s at Safe, 474 at Balanced and 470 at Aggressive, from 416, 418 and
  453; Meteor's 465), its lowest health never below 17.8. In a new scene where you jump under a ceiling one block up,
  its lowest health in every run stayed above the reserve: 8.6 at Safe (reserve 5), 6.5 at Balanced (3.5) and 4.6 at
  Aggressive (2). In the worst-case lab, two attackers with the city breaking again at once, it was held back on 8
  ticks of the fight's 1200 instead of 1018 to 1162 (with `surround++` or Meteor's `surround`), and placed 64
  crystals instead of 18 to 21, popping them 7 or 8 times instead of 3; in the slower city variant with Meteor's
  `surround` you lost one totem more (5 to 6, from an opponent's hit; the cause is not established).
- `crystal-aura++` could take a second crystal of its own for an opponent's and break it without checking your
  reserve or the 2-health floor. Like Meteor's aura it sends a placement again every tick until the crystal appears;
  when that first crystal was destroyed while a later placement was still on its way, the later one made a second
  crystal, and only Meteor's `max-damage` and `anti-suicide` stood between it and you. On the bench such a break was
  predicted to leave you with 0.2 to 0.5 health; each time it landed within the moment of invulnerability after the
  opponent's previous hit, and nothing got through. Present since 0.7.0, `crystal-aura++`'s first release. Now the
  rest of a placement stays yours for a second after its last attempt: breaking such a crystal must leave you at
  least 2 health, as with every crystal of yours.
- The in-game bench counted some hits from the opponent's own crystals as yours, when the opponent's crystal landed
  on a block where your aura had just tried to place. The one reserve failure of the release bench of 2026-09-30
  (`capp-balanced-near-death-totem`, 2.785 against a reserve of 3.5) was one of those hits, not one of yours. The
  game itself was never affected.

## [0.7.1] — 2026-09-29

### Added

- **`crystal-aura++` finishing blow** — new setting `finishing-blow` (on by default, at every `risk`
  level, needs `self-budget` on). `crystal-aura++` now goes for the kill:
  - If a crystal **kills** the opponent (no totem in either of his hands, his hands visible, and his
    health confirmed by one of your own hits), it may take you below your reserve or pop your totem. It
    does so only while you hold a totem **and** carry a spare, never your last one, and only one such
    crystal at a time.
  - To only **pop** him (he holds a totem, or his hands are hidden), it may drop you below your reserve
    only at `Aggressive`, and never below 2 health and never popping you. At the other levels the reserve
    holds.
  - On servers that hide other players' health (2b2t-style plugins) it does nothing: the opponent's
    health is never trusted until one of your own hits shows it moving the way it should.

### Changed

- The addon's listed author is now `SoftDryzz`.
- With the self-budget on, Meteor's `max-damage` no longer limits your own crystals up close: your reserve
  does. Other players' crystals keep `max-damage`, and so does everything when the budget is off.
- The self-budget now measures how exposed you would really be at each spot you can move to, instead of
  assuming full exposure. It deals more damage where there is a height difference or cover between you
  and the crystal, and still keeps the reserve.
- While you move, the self-budget uses the same reach check as 0.7.0; the real exposure applies when you
  stand still or move very little.
- A spot you could reach that lies inside a block counts as fully exposed (the safer reading), and totem
  detection covers any item that protects from death, not only the totem itself.

### Fixed

- `crystal-aura++` no longer freezes behind one of its own crystals that it may not break: such a crystal
  no longer closes Meteor's placing gate, and while the crystals already standing leave less than the
  reserve, it only uses spots where you would take no damage at all.
- A crystal of yours that reaches the client late is now counted against your reserve, instead of
  escaping the self-budget. One that would hurt you more than `max-damage` is still not broken by you.
- The fast break now uses your totem count as it is at that moment, not the one from a tick earlier.

### Measured

- In the 0.7.1 release bench (28 pairs judged ACCEPT), your health never went below the reserve because of
  your own crystals in any run, with no death and no totem lost to one of them. Behind cover, with the
  opponent below you or coming and going, and walking in circles, Balanced deals about as much damage
  as Meteor's or more; while you dodge, it still deals less.

## [0.7.0] — 2026-09-28

### Added

- **`crystal-aura++`** (experimental) — Meteor's `crystal-aura` behaviour (same placing and breaking rules and
  defaults) plus a self-damage budget that never lets your health (plus absorption) go below a
  reserve. It counts every crystal that can still hurt you, already placed or on its way to
  exploding, and works out the exact damage a hit would deal (Meteor rounds it down). Turn it on by
  itself, or drive it from `auto-pvp`. Does nothing while Meteor's `crystal-aura` is on, and says so.
  - `risk`: `Balanced` (keeps 3.5 health, default and recommended), `Safe` (keeps 5, experimental),
    `Aggressive` (keeps 2, experimental), `Custom` (the `reserve` setting). Breaking one of your own
    crystals must still leave at least 2.
  - Measured with an in-game bench built for this release (the bench itself does not ship): it kept
    your health above the reserve in every run, but deals less damage than Meteor's in some
    situations. Known issues and recommended settings are in the README.
- `auto-pvp`'s **`crystal-module`** setting: `meteor` (default) drives Meteor's `crystal-aura` exactly
  as before; `xploits++` drives `crystal-aura++` instead.

### Changed

- With `crystal-module` set to `xploits++`, running out of totems no longer switches the aura off
  while its self-budget is on: breaking the enemy's crystals is enough to keep you alive without one
  in hand.
- `auto-pvp` keeps asking for `surround` for a short while after a block of your hole has just been
  broken, not only while the hole is still whole: Meteor's own `surround` refills the four sides,
  never the block under you.

## [0.6.2] — 2026-09-26

### Changed

- `console`: the window now opens with the Xploits logo instead of the previous one. When the window is too
  small for it, the one-row fallback says "Xploits" in the logo's purple.

### Fixed

- `auto-pvp` turned on `anti-anvil` and `anti-anchor` as if they needed nothing, but they place
  obsidian and a slab: without them they did nothing. Now each one only goes up while you carry its
  material in the hotbar, and `anti-anvil` shares the obsidian with `hole-filler`, `surround` and
  `auto-trap`. `anti-bed` still goes up without anything: it breaks a bed on your head without
  string, and needs string to place it.
- The "on for a while without spending anything" warning no longer fires for the three `anti-`
  modules, which it cannot judge: `anti-anvil` and `anti-anchor` place only when an anvil or an anchor
  appears, and `anti-bed`'s string stays once placed, so its stack stops moving while it does its job.
- A managed module Meteor does not have (`anti-anchor` in Meteor 1.21.11) is no longer taken as
  `auto-pvp`'s and then reported as turned off by you. It is said once when `auto-pvp` is turned on,
  skipped, and listed as "missing in Meteor" in `.xploits pvp`.

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
- **`consola`** — an external terminal window with a logo, the live game state and a
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
