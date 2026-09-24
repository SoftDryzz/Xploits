# Meteor anatomy

Where its code lives, what each part does, and what is worth replicating, improving, or ignoring.

---

## Where the code lives

**The repository:** <https://github.com/MeteorDevelopment/meteor-client> (GPL-3.0).

**The sources jar, already downloaded on this machine** by Gradle when the addon is built:

```
~/.gradle/caches/modules-2/files-2.1/meteordevelopment/meteor-client/
    1.21.11-SNAPSHOT/<hash>/meteor-client-1.21.11-SNAPSHOT-sources.jar
```

Unzipping and reading it is the fastest way to settle any doubt. **This repo has a rule written
about exactly that:** never claim what a Meteor API does without having read it there. It has been
skipped twice, and both times cost a whole round of work.

There is also a copy remapped to readable names inside the project:

```
<project>/.gradle/loom-cache/remapped_mods/.../meteor-client-*-sources.jar
```

---

## The map, by subsystem

Everything hangs off `meteordevelopment/meteorclient/`.

### `systems/` — the chassis

What actually defines a client. A `System` is something that persists and saves to disk: modules,
settings, friends, waypoints, macros, accounts, profiles, proxies, HUD.

**`systems/modules/`** is the heart of it: `Module`, `Modules`, `Category`, and the 197 modules
split across six categories (`combat`, `misc`, `movement`, `player`, `render`, `world`).

**What you need to understand about `Module` before replicating it**, because these are decisions
with consequences:

- A module has `onActivate()` / `onDeactivate()` and subscribes itself to the bus.
- **`toggle()` unsubscribes the module BEFORE calling `onDeactivate()`.** Any listener that has to
  survive deactivation subscribes separately. In Xploits this cost a round.
- **`Modules.onGameLeft()` calls `onDeactivate()` but does NOT mark the module inactive**, so it
  comes back on its own on re-entry. Touching other modules there leaves them subscribed twice for
  the rest of the session, because the bus does not deduplicate.

**If you build your own, these three are the ones I'd fix.** How subtle a module's lifecycle is is
the most expensive source of failures Meteor has.

### `settings/` — 36 files

Typed settings (`BoolSetting`, `DoubleSetting`, `EnumSetting`, `StringListSetting`…) with
serialization, defaults, ranges, and conditional visibility.

Two things worth knowing that are not obvious:

- **`sliderRange` is cosmetic only**: it bounds the widget, not the value. What pins the value down
  is `.min()`/`.max()`, and without that the player can type in anything or inherit it from the
  config.
- **A persisted value that fails validation is discarded on load** and the setting goes back to its
  factory value. That is what lets you raise a minimum and have old configs fix themselves.

`Module.settings` is public and `Settings.get(name, type)` returns the typed setting, so **one
module can read another's setting** with no reflection or mixins. Xploits uses this to check that
`CrystalAura`'s `anti-suicide` is still on before trusting it.

### `events/` — 69 files, and `orbit`

The bus is not part of Meteor: it is a separate library, **`meteordevelopment:orbit`**. Worth
reading in full because it is small and defines how everything else behaves.

Three things about it with consequences:

- **It does not deduplicate.** Subscribing the same object twice registers it twice, and
  `unsubscribe` removes **only one** copy.
- **Priority orders, and ties go to subscription order.** `insert()` only moves ahead of strictly
  lower ones.
- **Cancelling stops the loop**: no later listener sees that event.

In a client of your own, **deduplicating on subscribe** is one line and saves a whole class of
failures.

### `mixin/` — 212 files, and the part everyone underestimates

This is the glue with Minecraft. Each mixin hooks a game method to publish an event, change a
behavior, or expose a private field.

**It is the biggest subsystem and the one most likely to break on a Minecraft version bump.** If
your client ever survives an update or dies on one, it will be decided here.

**Advice with a real cost behind it:** every mixin is debt. A client with 50 well-chosen mixins
survives a new version in a weekend; one with 212 takes weeks. Before adding one, check whether the
data can be pulled from a public API instead.

### `gui/` — 134 files

ClickGUI, HUD, widgets, themes. It is the flashiest part and the one that eats the most time.

**It is the last thing I would build.** A client with an ugly but functional GUI and good modules
is useful; one with a beautiful GUI and three modules is not.

### `utils/` — 128 files

The shared stuff, and where a good chunk of Meteor's real value is:

| Package | What it solves |
|---|---|
| `utils/player/` | Inventory, health, incoming damage, holes, rotations |
| `utils/entity/` | Targets, crystal damage calculation, surround blocks |
| `utils/world/` | Blocks, chunks, dimensions |
| `utils/render/` | Colors, text, drawing |

Two gems that Xploits uses and that a client of your own will need too:

- **`PlayerUtils.possibleHealthReductions()`** — the damage that is already aimed at you: placed
  crystals, people with a sword nearby, beds in the Nether, fall damage. It is the data everything
  defensive gets decided from.
- **`DamageUtils.crystalDamage(target, position)`** — how much a crystal placed there would do.

### `renderer/`, `commands/`, `addons/`, `pathing/`, `asm/`

- **`renderer/`** (23) — 3D/2D drawing. Necessary, and smaller than it looks.
- **`commands/`** (58) — the command system, on Brigadier.
- **`addons/`** (3) — the extension point. If you want others to write for your client, this is
  what needs to be designed well from the start.
- **`pathing/`** (6) — an abstraction over pathfinding. **Careful:** its Baritone detection does a
  `Class.forName("baritone.api.BaritoneAPI")`, which **fails with the obfuscated standalone jar**.
  On the instances on this machine, everything in Meteor that uses Baritone is silently dead. See
  [Integrating Baritone](integrating-baritone.md).
- **`asm/`** (6) — bytecode manipulation at load time.

---

## What to replicate, what to improve, what to ignore

**Replicate almost as-is** — these are well solved and there is no reason to reinvent them:

- The `System` model that persists to disk.
- Typed settings with validation and conditional visibility.
- The module / category split.

**Improve** — this is where a client of your own really gains ground:

| What | Why |
|---|---|
| **A module's lifecycle** | Unsubscribing before `onDeactivate()` and not marking a module inactive on world leave are two traps that already cost us rounds |
| **Deduplicating on the bus** | One line; it stops a module's handlers from ending up duplicated for the rest of the session |
| **Fewer mixins** | It is what decides whether you survive the next Minecraft version |
| **Hard ranges by default** | `sliderRange` not pinning the value down is a source of silent failures |
| **Tests** | Meteor practically has none. You have ~680, and that is your real edge |

**Ignore at the start:** the whole GUI, profiles, proxies, accounts, macros. All of that gets added
once the client already does something worth configuring.

---

## Other clients worth reading

Not to copy, but to see how they solved the same problems:

- **Meteor** — the one in front of you, and the best documented internally.
- **Baritone** — not a client, but its split between `api` and implementation is an example of how
  you publish a stable boundary.
- **Fabric API** — to understand what the platform gives you with no mixins. Everything you get
  from here is one mixin you do not have to write.
