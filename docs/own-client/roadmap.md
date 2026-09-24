# Roadmap

In what order to build it, what carries over from Xploits as-is, and what makes a client
professional instead of a pile of features.

---

## What is already done and carries over untouched

These are plain Java: they import neither `net.minecraft` nor `meteordevelopment`. They compile in
any project.

| Piece | What it solves | Tests |
|---|---|---|
| `pvp/core/` | The whole combat judgement: offensive phase, defensive posture, module ownership, who is one of ours | ~170 |
| `sweep/core/` | Lane planning, prior coverage, tally of what arrives, firework budget | ~200 |
| `travel/core/` | Geometry of the four patterns, the Baritone script, stall watch, module borrowing | ~180 |
| `elytra/core/` | When to swap the elytra and for which one | |
| `stash/core/` | Container index and search | |
| `kitrequester/core/`, `autotpy/core/` | Kit state machine, TPA policy | |

**About 640 tests in total.** This is the hard part, the one that cost the rounds, and the one
nobody else has. Everything else in the addon is a translation to Meteor and gets rewritten.

**What has to be rewritten for each module** is its adapter: subscribing to events, translating the
game state into the simple values the core understands, and carrying out what the core decides.
They are large in lines and thin in decisions.

---

## The order

### Phase 0 — Decide the path (before writing anything)

Read [the licenses](README.md#first-the-licenses) and decide: **fork of Meteor** (fast, GPL-3.0,
you inherit its architecture) or **client of your own on Fabric** (slow, free, the cores plug in
directly).

And check whether a **Baritone API jar for 1.21.11** exists. If it does not, [what that document
says](integrating-baritone.md) changes and has to be planned differently.

### Phase 1 — The minimal chassis

The minimum for a module to exist and be turned on:

1. **Event bus.** `orbit` is small and tested. If you write your own, **deduplicate on subscribe**:
   it is one line and avoids a whole class of failures.
2. **`Module` with a clear lifecycle.** And fix Meteor's two traps from the start: unsubscribing
   happens after `onDeactivate()`, not before, and leaving the world does not leave modules in a
   half state.
3. **Typed settings**, with real range validation and not just the slider's cosmetic one.
4. **Persistence.** One file per system. Whatever fails validation on load goes to its factory
   value.

**Criterion to move on:** a test module that turns on, saves a setting, recovers it on restart, and
reacts to a tick.

### Phase 2 — One core on top, the hardest part

**Port `pvp/core/` first**, and not alphabetically: it is the one that depends most on the world —
health, incoming damage, blocks, targets — so it is the one that will tell you whether your
core/adapter boundary is drawn correctly.

If `CombatSnapshot` can be filled in without dirtying the core, the design holds. If not, better to
find out here than after porting six modules.

You will need the equivalent of `PlayerUtils.possibleHealthReductions()`. It is in Meteor and is
worth reading to see how it is computed.

**Criterion to move on:** combat decides the same as in the addon, with the same tests green.

### Phase 3 — Mixins, just enough

This is where it is decided whether your client survives the next Minecraft version.

**For every mixin, ask first whether the data can be pulled from Fabric API or a public API.** Every
one you avoid is time you will not spend on the next update.

Meteor has 212. A client with 50 well-chosen ones survives a new version in a weekend.

### Phase 4 — Baritone through its API

See [Integrating Baritone](integrating-baritone.md). The chat network, the command script, and the
prefix disappear; and you can **read** its settings, not only write them.

**Criterion to move on:** a full-pattern trip with not a single line written to chat.

### Phase 5 — The rest of the cores

`sweep`, `travel`, `elytra`, `stash`. With the path already cleared, they are adapters.

### Phase 6 — GUI

**Last.** A list of modules with checkboxes and fields is enough for the client to be useful. The
pretty part comes after, once there is something worth configuring.

---

## The four things that make it professional

They are not features. They are what separates a living client from an abandoned one.

### 1. That it builds from scratch with one command

On a clean machine, `./gradlew build` and out comes the jar. No manual steps, no "first copy this."
If you have to explain how to build it, it is not finished.

### 2. That it has tests for what it decides

**You already have this, and it is your real edge.** Meteor practically has none. A client that can
change its combat judgement and know in thirty seconds whether it broke something plays in a
different league.

And the part that really matters: **verify by mutation**. A green test only proves the test passes.
In this repo there have been green tests for two rounds while protecting code that could break
without anyone noticing.

### 3. That it says what it does and what it does not

This is the principle that governs all of Xploits: **a failure can never look like a normal
result.**

A module that cannot do what it promises refuses and says what to fix. A measurement that does not
exist throws instead of returning zero. A poorly covered area warns loudly instead of saying
"done."

This is not cosmetic: it is what makes a user trust the client. Trust is lost once and does not come
back.

### 4. That it survives a Minecraft version

This depends almost entirely on how many mixins you have and how fragile they are. A client that
takes three months to update loses its users in the first month.

And a rule that is already written in [Conventions](../conventions.md) and that doubles here:
**do not reimplement what already works.** On the addon's latest branch, a base detector, a coverage
map, and a chunk log were nearly rewritten — all three already existed, installed, and better.

---

## The advice that matters most

**Start with the minimal chassis and a single core**, and check that the whole thing holds up before
porting the rest. If halfway through it turns out not to be worth it, you have lost nothing: the
cores keep working in the addon, which stays useful.

What would sink the project is starting with the GUI or with replicating Meteor's 197 modules. It is
the flashiest part, the longest one, and the one that has the least to do with what makes your work
special.
