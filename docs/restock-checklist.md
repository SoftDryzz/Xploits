# `restock` real-game checklist

What the in-game bench cannot prove (restock spec §6), done by hand on real servers before a `restock` release. Tick
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

## 2. litematica-printer paused and resumed in a real build

- [ ] Printing on: a trip switches the print mode off before walking and back on at the return; the build carries on.
- [ ] Switch the print mode on yourself during a trip: at the return `restock` leaves it as you set it.
- [ ] Printing off when the block runs out: `restock` fetches and leaves the print mode off.
- [ ] Close the game mid-trip; start again and join any world: the print mode is on again.
- [ ] Press forward during a trip: `restock` stops, says the printer stays off, and it does.

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
- [ ] Two overlapping placements, with the enclosing box left off: `restock` refuses to start and says why.
- [ ] A marked chest broken and replaced by another block: the trip skips it and never clicks the new block.
- [ ] Marking while flying is refused; unmarking a chest that has vanished works.

Anything that fails stops the release: note what happened (counts and reasons only) and open an issue.
