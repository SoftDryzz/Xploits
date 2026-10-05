# `restock` real-game checklist

What the in-game bench cannot prove, done by hand on real servers before a `restock` release. Tick
each line, note the date and the version, and keep the notes in the release's pull request: counts, reasons and
distances only, never a coordinate, a server address or a player name.

Setup for every section: the release jar in a test instance with Meteor 1.21.11, Litematica 0.26.14 with malilib
0.27.19, `litematica-printer` and Baritone; a small schematic of full blocks (stone, planks), about 5 × 5 × 3, loaded,
placed and selected in Litematica; one stack of its main block carried; `mark-key` bound; `restock` with its defaults.

## 1. Baritone walks

- [ ] Mark a chest holding the main block about 20 blocks away, from a free spot next to it. Turn `restock` on: the
      chat says it is on, how many sources it has, and that Baritone's settings come back with `MINE`.
- [ ] Print until the block runs out: after a moment `restock` says it is going, walks to the chest, stands still,
      opens it, takes stacks, closes it and walks back; Baritone breaks and places nothing on the way.
- [ ] Nothing is clicked while walking (watch the hotbar and the chest screen: it opens only once you stand still).
- [ ] Turn it off mid-trip: Baritone stops, and `allowBreak`, `allowPlace`, `allowWaterBucketFall`,
      `censorCoordinates` and `censorRanCommands` show your own values again.
- [ ] Close the game mid-trip; start again and join any world: the five settings are yours again.
- [ ] Set `baritone-settings` to `DEFAULTS`, run a trip, stop: the three behaviour settings are Baritone's defaults and
      the two censors are yours.
- [ ] Mark a chest while standing on a slab, soul sand, a dirt path or another block lower than a full one, and run a
      trip to it; then let a block run out while you stand on one. Baritone counts your feet one block higher on those
      than Minecraft does: note whether it reaches the spot and comes back, or stops saying it found no path.

## 2. litematica-printer paused and resumed in a real build

- [ ] Printing on: a trip switches the print mode off before walking and back on at the return; the build carries on.
- [ ] Switch the print mode on yourself during a trip: at the return `restock` leaves it as you set it.
- [ ] Printing off when the block runs out: `restock` fetches and leaves the print mode off.
- [ ] Close the game mid-trip; start again and join any world: the print mode is on again.
- [ ] Press forward during a trip: `restock` stops, says the printer stays off, and it does.
- [ ] Walk along the build, or sneak at an edge, while a block runs out: no trip starts while you move; once you stand
      on the ground it goes after a moment, and the printer is paused only then.
- [ ] Turn Litematica's rendering off (its hotkey) with the build half done and a finished block no longer carried:
      no trip starts for blocks the build already holds; turn rendering back on: trips go only for blocks still
      missing.

## 3. On the anarchy servers you play

On each server, 6b6t first:

- [ ] Three trips with no kick and no setback (a setback would stop it, saying the server pulled you back).
- [ ] Nothing lost: count the main block before and after (inventory + chest + build).
- [ ] A Meteor friend walking close does not stop it; a stranger within 48 blocks does.
- [ ] Eating while a trip is due: it waits, then goes.
- [ ] Disconnect mid-trip and join again: it says it was not resumed, stays off, and the print mode and Baritone's
      settings are back.
- [ ] A material no marked chest has: it says which and how many, and the printer keeps printing the rest.

## 4. A server you join through ViaFabricPlus

- [ ] One trip with no kick and no setback; note anything that differs from section 3.

## 5. Large builds, overlaps and vanished chests

- [ ] A large placement (tens of thousands of blocks): note the frame rate while `restock` counts, with it on and off.
- [ ] Two enabled placements that overlap: `restock` refuses to start and says why.
- [ ] A marked chest broken and replaced by another block: the trip skips it and never clicks the new block.
- [ ] A build with shulker boxes, and a marked chest holding a shulker box of the same colour with items in it:
      `restock` leaves that shulker box in the chest.
- [ ] Marking while flying is refused; unmarking a chest that has vanished works.
- [ ] Mark a copper chest (single and double): `restock` opens it and takes.

## 6. Shulker boxes

Extra setup: a pickaxe in the hotbar, a hotbar slot and two more slots free, `use-carried-shulkers` on. For the first
items, a shulker box holding about two stacks of the build's main block, in your hotbar; for the trip items, a marked
chest that holds the main block only inside one shulker box (no loose stack of it) and none of it in your inventory.
On each server, 6b6t first.

- [ ] A real Baritone walk with a box: let the main block run out with the box in your hotbar. `restock` says it is
      unpacking, sets the box down next to you, opens it, takes, breaks it and picks it up; if the drop does not come
      to you by itself it walks onto it, then walks back to where it began; Baritone breaks and places nothing on the
      way. Count the main block before and after (inventory + box + build): nothing lost.
- [ ] A trip that carries a box: let the main block run out with only that chest as a source. The trip walks there,
      takes the whole box, walks back with the printer still off, and the unpack starts once you stand still; the box
      ends empty in your inventory.
- [ ] A borrowed box going back: on the next trip to that chest the empty box is put back into it; or, with the build
      done, `restock` says it is taking the empty box back and that it came back. Count the boxes in the chest before
      and after. An empty box of your own with the same colour and a name is never put into the chest.
- [ ] The place, the dig and the pick-up on a server with a real anticheat: no kick and no setback (a setback would
      stop it, saying the server pulled you back). Note any message the server or a plugin sends about the box, and
      whether the dig takes about as long as your tool should.
- [ ] The one inventory move: with the box in your main inventory and a free hotbar slot, exactly one Shift-click
      moves it into the hotbar, nothing else in your inventory changes, and the server does not flag it. With the
      hotbar full, `restock` says once that a free hotbar slot is needed and fetches from the containers instead.
- [ ] The box landing on real terrain: set it down on open ground (the drop comes back within a second or after a
      short walk); then beside a ledge, a hole, ice or packed ice, water or lava, a fence, a wall and a slab: those
      spots are refused (`restock` uses another one, or stops saying there is no free spot), and the drop never ends in
      water or lava, off an edge or on ice.
- [ ] A stop mid-unpack: get hit by a player during the dig, and it stops at once saying how many boxes stand and how
      far away the nearest is; let a stranger come within `player-distance`, and it finishes the break and the pick-up
      first, then stops; press a movement key, and it stops at once; turn `speed-mine` on, and it finishes the box,
      then stops naming it. Each time the printer stays off, and says so; break and pick up what it reported by hand:
      nothing lost.
- [ ] Close the game while a box stands, start again and join any world: the print mode is on again; the box is still
      where it was.
- [ ] Fill your hotbar with other items: a chest with the block only inside boxes is passed over, and `restock` says
      to free a hotbar slot and one more only when no other container has the block; free them, and it carries the box
      on its next try, within a minute.

Anything that fails stops the release: note what happened (counts and reasons only) and open an issue.
