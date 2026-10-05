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

**Symptom:** when starting a sweep, a warning says some corner will be lost but that *"The strip is
still crossed -no lane is lost-, but its edges will pass further from the client than planned."*

**What happens.** That is true when the consumed link is short (a narrow last strip) and **false when
it is a whole strip long**, which by construction always exceeds the coverage radius: it leaves up to
two thirds of the strip undelivered at one end, 22 % integrated.

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

**Symptom:** the `sweep.lane-too-short` rejection in Spanish says «agranda el rectángulo por ahí -acerca chunk-x-1 a chunk-x-2, o chunk-z-1 a chunk-z-2, el par que esté más junto-» <!-- es-quote -->
when the rectangle has to **grow**.

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

**Symptom:** when resuming the uncommitted work of `feat/dupe-audit` (root checkout) after this
migration, applying its changes fails.

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

### `crystal-aura++` still deals less damage than Meteor's while you dodge

**Symptom:** while you dodge (with the opponent circling), Balanced deals a little less damage than
Meteor's `crystal-aura` (27 against 30 in the 0.8.0 release bench) and takes the first totem later (4.7 s
against 1.65 s). Walking in circles, Balanced deals 31 against Meteor's 40 in that bench (32 in 0.7.1's); at
Safe it deals 20, where 0.7.0 dealt about 32.

While you move, the self-budget uses the same reach check as 0.7.0; the real-exposure measurement
of 0.7.1 applies when you stand still or move very little.

**What happens.** To keep the reserve while you move, the budget judges each crystal at the worst spot
you could reach before it explodes. That spot includes a jump, and at the start of a fight it assumes a
whole second of movement until it has learned how long its crystals take to land. Every spot around a
moving opponent is close to you, so each crystal hurts you a lot, and the reserve refuses the ones that
would take you below it. Meteor's `crystal-aura` gets there sooner by taking you down to under 1 health.

**Consequence:** less damage than needed; never less safety (the reserve held in every bench run).

**What to do.** If you prefer Meteor's damage there, set `auto-pvp`'s `crystal-module` to `meteor`.
`Aggressive` (experimental) keeps 2 instead of 3.5.

**Pending fix:** a later version — size the reach radius from how far you really move. A first attempt in 0.7.1
made the strafing case worse and was taken out.

### `crystal-aura++` still draws an open-ground exchange where Meteor wins

**Symptom:** when you and the opponent both attack, in the open with no cover, `crystal-aura++` ends the
fight in a draw where Meteor's `crystal-aura` wins.

**What happens.** Meteor places every crystal it can, and the reserve makes `crystal-aura++` skip some of
them. The reserve held in the bench runs. Part of the difference is an artefact of the bench: the
explosions knock our player out of range and it never walks back to re-engage, which a real player does.

**Pending fix:** a later version — keep the bench player in place or make it re-engage, the same for both auras.

**What to do.** If you prefer Meteor's damage in that kind of fight, set `crystal-module` to `meteor`.

### `crystal-aura++` has no finishing blow on servers that hide health

**Symptom:** the aura never goes below your reserve to finish an opponent on a server that hides other
players' health (2b2t-style plugins).

**What happens.** Those plugins send a fixed or random health. `finishing-blow` only trusts an opponent's
health after one of your own hits has shown it moving the way it should, so on those servers it never
does. For the same reason there is no finishing blow before your first landed hit on a target. Against a
plugin that sends random values there is a small chance a hit is read as a match.

**What to do.** Nothing: the normal reserve keeps protecting you.

### A crystal of `crystal-aura++` that appears late is treated as someone else's

**Symptom:** after a lag spike, one of your own crystals stays standing and is not broken.

**What happens.** A crystal that reaches the client long after it was placed is filed as another
player's. Since 0.7.1 it counts against your reserve, but when it would hurt you more than `max-damage`
it is never broken by you and stays standing until the enemy breaks it. It is the same window as the
late-crystal gap below.

**What to do.** Nothing you can set. It costs damage at worst, and the reserve still counts that crystal.

**Pending fix:** recognise a late crystal of ours by where and when we placed it.

### `crystal-aura++` brakes a little more than it needs to after a hit lands

**What happens.** A crystal that has already hit you is still counted for a few ticks, because your
health can reach the client late; with network delay the hit is often already in your health by then,
so it counts twice. The attempt to stop counting it earlier was withdrawn before 0.7.0: it let health go
below the reserve on the bench.

**Consequence:** a few crystals refused that were safe. Never less safety.

**Pending fix:** stop counting a crystal only once the client has really applied the health update that
contains its hit.

### Two narrow gaps in how far `crystal-aura++` assumes you can move

**What happens.** A lag spike that delays one of your crystals by more than about 1.3 s is not
recognised as yours, so it does not widen the caution the way a shorter spike does. And the worst-spot
check looks at eight directions around you, so a crystal exactly between two of them is judged a few
percent closer to safe than it is. Neither showed up on the bench.

**Pending fix:** widen the late-crystal window and aim a point at each crystal's direction.

### `surround++`: what it does not do yet

**Symptom:** stuck in a web inside your shell; a crystal next to you not broken while you have Weakness; an opponent
building his own surround after his hole was filled; `crystal-aura++` holding a placement back while you move inside
your hole.

**What happens.** Breaking webs and eating after a pop belong to the survival work that comes next. Attacking a crystal
under Weakness needs a weapon in hand, and `surround++` does not swap to one (Meteor's crystal aura does). Filling an
opponent's hole does not stop him surrounding himself.

`crystal-aura++` can hold a placement back on the ticks you move inside a hole: its safety check reads the places your
movement could take you before the crystal explodes, and one that would put you inside the hole's wall is read as fully
exposed. A block over your head no longer does this: since 0.8.0 a jump is read where that block stops it, except
while someone is mining that block or when a crystal can blow it away (anything weaker than obsidian, such as stone or
netherrack): then the full jump still counts. A roof can still go without warning between a crystal's placement and
its explosion (an instant mine, a ghost block resolving), and a jump inside that window can then hurt more than was
read.

**What to do.** Keep a sword in hand if you get Weakness; get out of webs by hand.

**Pending fix:** survival (webs, eating), then the offence against surrounded opponents. For the moving-in-a-hole case,
a change in `crystal-aura++`'s safety check with its own bench.

### `restock` counts every layer of the placement

It counts what the whole selected placement still needs, whatever layer range Litematica shows: on a build printed
layer by layer, it may fetch blocks for layers you have not reached yet (it fetches more, never less).

### A shulker box `restock` borrowed stays with you until it is empty

**Symptom:** after a build you still carry a shulker box that came from one of your containers.

**What happens.** `restock` gives a box back to the container it came from only once the box is empty: on the next
trip to that container, or on one last trip when the build is done. A box that still holds blocks the build did not
need stays with you, and every stop says how many you carry. It never gives a container more boxes than it took from
it. The rare exceptions are in "`restock` can lose count of a borrowed shulker box".

**What to do.** Put it back by hand.

### `restock` cannot tell two shulker boxes of one colour and name apart

**Symptom:** you carry a box of your own with the same colour and name as one `restock` borrowed, and after the build
the borrowed one is still with you; or, with `use-carried-shulkers` off, `restock` unpacks your own full box.

**What happens.** It tells boxes apart only by colour and name, and keeps a count, not a memory of each box. Of two
unnamed boxes of one colour it counts the empty ones as yours first, so it never hands a container an empty box of
yours in place of a borrowed one that still holds blocks, and never more boxes than it took (save in the rare cases in
"`restock` can lose count of a borrowed shulker box"). The price is that a borrowed empty box can stay with you. With `use-carried-shulkers` off, a full box of yours can be taken for the one it
borrowed and unpacked.

**What to do.** Name the boxes you keep for yourself (rename them in an anvil): a named box is a different kind from an
unnamed one of the same colour.

### `restock` can lose count of a borrowed shulker box

**Symptom:** after a build you carry a box from one of your containers that `restock` no longer mentions at a stop and
never takes back; or, rarely, an empty box of your own ends up in that container, or a full one of yours is unpacked
with `use-carried-shulkers` off.

**What happens.** `restock` counts borrowed boxes per colour and name; it cannot follow each box. It forgets a borrowed
box when a box of that kind leaves your inventory between two of its container visits (dropped, placed, put in an
ender chest or your offhand), or when the server refuses a give-back later than the second it waits; the box then
counts as yours. The other way round, a box of that kind that reaches your inventory while a container is open (picked
up, or swapped from your offhand), or after a server's late answer and before `restock` opens another container, can
be taken for a borrowed one. A stop in the second it waits for the server can also count a borrowed box you do not
have.

**What to do.** Name the boxes you keep for yourself (a named box is a different kind), leave boxes of the same colour
alone while `restock` has a container open, and check your containers after a build.

### A stop while `restock` unpacks can leave the shulker box beside the build

**Symptom:** `restock` stops and says a shulker box it set down still stands beside the build, or lies on the ground.

**What happens.** If you are attacked, your health falls below `min-health`, the server sets you back, `auto-pvp`
engages, you press a movement key, you die, change dimension or leave the server, you turn `restock` off, or
something unexpected fails inside it while a box is out, it stops at once: the box may stay standing beside the build,
or lie on the ground if it was already broken. The stop says how many boxes stand and how far away the nearest is, or
how far away the dropped one lies, never where. Right after the dig it may say the box stands although it is just
breaking; if the server has not yet answered the box being set down, or it cannot tell whether a box is still there, it
says it could not check. Other stops (a stranger near, a conflicting module, another module that keeps turning your
head) first finish the break and the pick-up, for up to 10 seconds. If the game crashes or you leave the server while a
box stands, nothing remembers it at the next join.

**What to do.** Look around the build for what the stop reported, then break the box and pick it up by hand. The
printer stays off after such a stop: switch it on again when you are done.

### `restock` needs free slots for shulker boxes

**Symptom:** `restock` says a block is only inside shulker boxes and asks you to free a hotbar slot and one more, or
says it moves a box you carry into the hotbar only into a free slot.

**What happens.** It carries a box from a container only while a hotbar slot and one more slot are free. With less room
a container whose block is only inside boxes is passed over (not forgotten: it is tried again once they are free), and
`restock` says so when no other container has the block. It moves a box from your main inventory into the hotbar
only into a free hotbar slot. While it takes from a box it keeps one slot free for the box to come back to, and with
no empty slot at all it unpacks nothing.

**What to do.** Keep a hotbar slot and one more free while you build with shulker boxes.

### `restock` can find no spot to set a shulker box down

**Symptom:** `restock` stops saying there is no free spot next to you, outside the build, to set the box down.

**What happens.** It sets a box down only within reach, outside the enabled parts of the selected placement, on an empty
spot with room above it for the lid, where the broken box's drop is safe (see the next entry). Standing inside a large
build, in a tunnel or on a narrow or icy bridge, every spot near you can be refused. The box stays in your inventory
and the printer stays off.

**What to do.** Step to the edge of the build, or onto open ground with a flat floor that is not ice, and turn
`restock` on again.

### A broken shulker box can roll away, be taken or be refused

**Symptom:** a stop says the shulker box `restock` broke lies a few blocks away, or is no longer on the ground near you,
or that the box could not be set down.

**What happens.** A broken box drops as an item and can drift a block or so; on ice or slime it would slide much
farther, so `restock` sets a box down only where every block around it stops the drop or catches it on a floor that is
not slippery, away from fire, lava, water, cacti, hoppers and portals. It does not look for entities: a hopper
minecart, or a mob that picks items up, near the spot can take the drop. After the box breaks it waits a second for
the drop to come to you, then walks onto it, and stops if the box is not back after 5 seconds. Another player or a
clear-lag plugin can take it first; the stop then says it is gone. Spawn protection or a claim plugin that refuses the
box makes `restock` try three spots and stop, with the box still in your inventory.

**What to do.** Pick up what the stop reports by hand, and keep hopper minecarts and item-collecting mobs away from
where you build.

### A server that answers late can leave a box in your inventory that is not there

**Symptom:** after `restock` took a shulker box from a container, it stops saying a box could not be set down, that it
could not check, and that you still carry a borrowed box, although you never got that box.

**What happens.** When a click moves a box out of a container, your game shows the box in your inventory at once, and
the server answers only if it refuses the click (an anticheat or a plugin can). `restock` keeps the container open for
about a second for that answer: if the box comes back, it is not counted as borrowed. If the server answers later,
your game has already closed the container and ignores the answer: it keeps showing a box the server never gave you
until the next screen you open updates your inventory. `restock` then tries three times to set down a box you do not
have, and stops. Nothing is lost: the box is still in the container. If a box of that kind arrives in your inventory
before you open another container, an empty box of your own can be taken for the borrowed one and go into the
container, and a stop during that second can count a borrowed box you do not have (see "`restock` can lose count of a
borrowed shulker box").

**What to do.** Open a container to update your inventory, check the box is still in the container it came from, and
turn `restock` on again.

### `restock` cannot see a player beyond the server's tracking range

`stop-near-players` reads the players your client knows about, and a server sends them only within its own tracking
range, so a `player-distance` larger than that range cannot be honoured.

### `restock` does not count blocks that no item places directly

Plant bodies such as kelp, cave vines, weeping vines and twisting vines, and potted plants, are not counted, so
`restock` never fetches them.

### `stash-keeper` does not index copper chests

`restock` marks and opens copper chests (all eight variants), but `stash-keeper` still does not index them, so with
`use-stash-keeper` on they are used only when you mark them.

### `restock` pauses counting if Easy Place's placement restriction is switched on while it runs

If it is switched on after `restock` starts, counting pauses at the next recount and says so.

### `restock` may note a late container answer under the next container

If a container answers late, after `restock` gave up on it and moved on, its contents may be noted under the next
one. Only the "what it holds" hint is wrong; nothing is taken from the wrong place.

### `restock`'s counting cost on a large placement is not measured

The per-tick cost of counting a large placement has not been measured on a real build yet.

### `restock`'s refusal of two overlapping placements is proven only by reading the code

The bench has no overlap case yet.

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
| `crystal-aura++` reserves | Safe 5, Balanced 3.5, Aggressive 2 | Measured on the bench against a fake opponent that never attacks; not yet in real fights |
| Target cooldown margin | 0.5 raw damage | A crystal is held only if it cannot beat the target's last hit by this much; the client overshoots that hit by about 3.5 on average |
| Learning how long crystals take to land | 5 samples, 1-tick margin, 1 s until learned, lag spikes up to 5 s | Chosen to never underestimate; the 1 s start is what makes it cautious while you move |

---

## Things that look like failures and are not

**"`crystal-aura++` broke one of my own crystals and left me below the reserve."** By design, breaking one of
your own crystals is checked against the 2-health floor rather than the reserve (the reserve is kept when the crystal
is placed; once it stands, the opponent can set it off anyway), and in 144 traced runs of the bench's
`near-death-totem` fight at Balanced, the real result of every such break, measured on the server, stayed at 5.2 or
above.

**"I turn on `auto-travel` or `nether-sweep` and nothing happens."** They do not fly when turned on:
they arm the trip and wait for their command. When you turn them on they tell you so in chat.

**"A 3×3 chunk sweep is rejected."** Correct. The long axis has to be longer than `waypoint-margin` —
about 10 chunks with the default (150 blocks): below that, the vertices are so close together that they
would be consumed in the same tick, and there would be lanes that are not flown **and that the sweep
would count as combed anyway**.

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
- Whether 6b6t sends the contents of shulkers inside chests: it decides half of the design of `stash-keeper`, and
  whether `restock` can see blocks inside shulker boxes there at all (without them it fetches loose blocks only).
- How `crystal-aura++` does against several enemies at once, or on a real server: the bench plays one
  opponent that attacks back, with golden apples, but no crowd.
- Whether `auto-pvp`'s surround request right after a hole block is broken is fast enough against instant
  mining on a real server.
- Whether `restock` walks well with the real Baritone: the bench walks with a scripted walker. The release checklist
  is [restock-checklist.md](restock-checklist.md).
- Whether `restock` pauses and resumes the real `litematica-printer` in a real build: the bench uses a stand-in print
  mode.
- Whether `restock` behaves on a server reached through ViaFabricPlus.
- Whether `restock`'s container clicks pass the anticheats of 6b6t and other anarchy servers. The bench checks every
  packet against the rules Grim's published checks follow, on a vanilla server where no anticheat runs.
- Whether setting a shulker box down beside the build, digging it and picking it up, and the one shift-click that
  moves a box into your hotbar, pass the anticheats of 6b6t and other anarchy servers. The bench checks these packets
  the same way, on a vanilla server where no anticheat runs.
- Whether a broken shulker box's drop stays where `restock`'s spot rules expect on real terrain: they follow vanilla's
  item physics as read in the game's code, and nobody has watched it on a real server.
