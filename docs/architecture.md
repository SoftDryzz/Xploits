# Architecture

## The rule that governs the whole repo

Every module is split in two, and the boundary is hard:

```
  <module>/core/     Pure core. Does not import net.minecraft or meteordevelopment.
                     All the logic that decides. With unit tests.

  <module>/          Adapter. Talks to Minecraft, Meteor and Baritone.
                     Decides nothing. No tests: verified by playing.
```

**If something can be decided without starting Minecraft, it goes in the core.** This is not purism:
the adapter cannot be tested, and what is not tested breaks without anyone noticing. This rule has been
broken three times in the life of the repo, and all three cost a whole review round.

The case that explains it best: in `nether-sweep`, the calculation of the distance left to fly feeds
the firework projection, which decides whether to cut the flight short. It lived in the adapter, with
no net. Moving it down to the core cost one class and fifteen tests; leaving it up there would have
cost someone running out of fireworks a hundred thousand blocks from home with no test saying a word.

`BoundaryTest` reads the source code and enforces what no core test can see: that every module and
command inherits from the bases, that nobody writes to chat through `ChatUtils` without logging it
separately, that neither the cores nor the console window import anything from the game, and that
player text comes from the language catalogs and the code is in English.

## What is inside

```
com/xploits/                    Module and command registration (XploitsAddon).
com/xploits/commands/           The .xploits command.
com/xploits/mixin/              Coordinate masking in the copy of chat and stdout that goes to latest.log.
com/xploits/shared/chat/        SnifferBuddy's chat protocol, shared.
com/xploits/shared/XploitsModule.java   Base of every module; XploitsCommandBase.java, of every
                                        command. What they say in chat also goes to the console.
com/xploits/shared/             Also: the xploits settings module, the language and the 0.4.0
                                settings migration, on the adapter side.
      .../core/                 Coordinate masks for the log and messages split into chat and log halves.
      .../core/i18n/            Languages, message keys and the catalogs (xploits/lang/es.lang, en.lang).
      .../core/migration/       Renaming of old saved names in modules.nbt and hud.nbt, and of the
                                old consola folder into console.

com/xploits/kitrequester/       Requesting kits and accepting the courier.
      .../core/                 State machine, queue and progress.
      .../inventory/            Emptying shulkers into the ender chest.

com/xploits/autotpy/            Accepting TPAs.
      .../core/                 Acceptance policy.

com/xploits/stash/              Passive container index.
      .../core/                 Index, keys and search.

com/xploits/elytra/             Elytra swapping.
      .../core/                 Swap policy.

com/xploits/pvp/                Directing the combat modules.
      .../core/                 Offensive phase, defensive posture, catalog of directed
                                modules, who is one of ours, and what is written
                                to Meteor's friends list.

com/xploits/travel/             Travel with a decoy pattern.
      .../core/                 Route geometry, the patterns, the Baritone command
                                script, the chat safety net, the stall watch, the firework
                                watch and borrowing other modules.

com/xploits/sweep/              Nether sweep.
      .../core/                 Rectangle, lane planner, prior coverage, tally of what
                                arrives, firework budget, odometer and width probe.

com/xploits/console/            The adapter: the module, the header collector and the sink,
                                which writes from the addon's only thread of its own.
      .../core/                 Everything the console decides: file format, what is drawn
                                and how it degrades, the window's life, the coordinate sentinel.
                                Pure and tested.
      .../window/               The window. Runs in another process, outside the game, with
                                java -cp <mod jar>; that is why it can only touch the JDK,
                                console/core and shared/core.
```

## What would be portable to another client

This matters if one day this stops being a Meteor addon.

**Portable as is — pure Java, without a single client dependency:**

| Piece | What it solves |
|---|---|
| `travel/core/RoutePlanner` | Geometry of the four decoy patterns and their rejections |
| `travel/core/BaritoneScript` | Baritone's exact command vocabulary |
| `travel/core/SafetyNet` | Recognizing whether a text is a command we are directing |
| `travel/core/StallWatch` | Detecting that there is no progress, tied to the waypoint by construction |
| `travel/core/BorrowedModule` | Borrowing another module and returning it to its real state |
| `pvp/core/*` | The whole combat judgement: phases, posture, module ownership |
| `sweep/core/*` | Lane planning, coverage, firework budget |
| `elytra/core/ElytraPolicy` | When to swap the elytra and for which one |
| `stash/core/*` | The container index |
| `shared/core/i18n/*` | The ES/EN catalogs and how a message is looked up |

**Not portable — it is the translation to this particular client:**

The adapters (`NetherSweep.java`, `AutoTravel.java`, `AutoPvp.java`…). Each one does three things:
subscribe to events, translate the game state into the simple values the core understands, and carry
out what the core decides.

**The proportion is no accident.** The adapters are large in lines but thin in decisions. A port to
another client rewrites the adapters and **does not touch the core**, which is where the hours of
thinking and all the scars are.

## The boundary: `CombatSnapshot` as an example

The best example of how the boundary is drawn is `pvp/core/CombatSnapshot`: a record of simple values
—distances, booleans, counts— that the adapter fills in and the core consumes.

No `PlayerEntity`, `BlockState` or `World` crosses that line. That is what lets the whole combat
decision be tested with more than 200 tests without starting the game, and what would make porting it
a matter of changing who fills in the record.

The same goes for `Coverage`, which receives **lines of text already read** instead of paths: the
adapter is the one touching the disk, so the parsing —where the risk of counting as seen a chunk that
was not seen lies— stays under the net.

## Interfaces with third parties

| With whom | How | Risk |
|---|---|---|
| **Meteor** | Normal API. `Module`, `Settings`, `EVENT_BUS` | Meteor unsubscribes a module **before** `onDeactivate()`: any listener that has to survive the restore is subscribed separately |
| **Baritone** | **Chat commands.** Its API is obfuscated and cannot be compiled against | A command Baritone does not intercept **is published in the server chat**. Hence the safety net |
| **Trouser Streak** | Reading its chunk files | We replicate its name sanitizing character by character: if it diverged, we would read a folder that does not exist and treat as unseen what was seen |

**Baritone is the most fragile interface of all.** There is no contract: there are chat commands and a
mixin of its own that may or may not intercept them. Everything we know about it is verified by reading
its bytecode, and is written down in the specs with the exact fact (for example: it starts landing
**48 blocks** from its goal, a value taken from a comparison against `2304.0d`).

## How we verify that something works

Three levels, and all three are needed:

1. **Core unit tests.** ~940 in the repo.
2. **Mutation checking.** A green test only proves that the test passes. Before accepting a
   protection, what it protects is broken on purpose and some test must be seen to shout. In this repo
   **there have been tests green for two rounds protecting code that could break without them
   noticing**.
3. **In-game verification.** The adapters have no tests, and interface failures with Meteor or
   Baritone only show up when playing. The case that proves it: Baritone landed at every waypoint of
   the pattern and none of the eight review rounds saw it, because it was not a flaw in our logic but
   in an assumption about its own.

See [Conventions](conventions.md) for how this is applied in practice.
