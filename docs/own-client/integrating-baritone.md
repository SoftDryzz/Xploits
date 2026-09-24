# Integrating Baritone

The addon talks to Baritone by **writing commands into chat**. It is a hack that cost two rounds of
work and a whole safety net. In a client of your own **this should not be done that way**, and this
is the biggest, cheapest improvement in the whole migration.

---

## Why the addon ended up writing to chat

Baritone ships in two forms, and both are on this machine. Checked by opening the jars:

| Jar | Classes in `baritone.api` | What you can do |
|---|---|---|
| `baritone-api-fabric` | **4**: `BaritoneAPI`, `IBaritone`, `IBaritoneProvider`, `Settings` | **Compile against it** |
| `baritone-standalone-fabric` | **1** | Nothing: the rest is obfuscated |

The addon was installed alongside the **standalone** one, so there was no API to compile against: it
would compile and blow up in the game. The only route left was chat.

**And that carries a risk that is not theoretical.** On an anarchy server, if an `#elytra` leaks
into public chat you have just announced to everyone that you are running Baritone, and where.
Read from Baritone's own mixin bytecode, it only cancels the command if its manager recognizes it:
if you changed the prefix or there is no instance bound to your player, **the text goes out to the
server.** That is the whole reason the chat-packet-cancelling net exists.

---

## How to do it right

With the API jar as a compile-time dependency:

```java
IBaritoneProvider provider = BaritoneAPI.getProvider();
IBaritone baritone = provider.getPrimaryBaritone();
Settings settings = BaritoneAPI.getSettings();
```

From there, everything the addon does over chat is done with a call instead:

| What the addon writes | What gets called |
|---|---|
| `#set elytraAutoJump true` | The setting's field in `Settings`, with its type |
| `#goal x z` | The goal manager, with a typed goal |
| `#elytra` | The elytra process, directly |
| `#cancel` | Path control |

**What disappears at once:**

- The whole safety net and its separately subscribed listener.
- The entire command script and prefix validation.
- The `baritone-prefix` setting and the whole class of failure from misconfiguring it.
- The uncertainty of not knowing whether a command arrived: now it is a call, it either compiles or
  it does not.

**And what you also gain:** reading its settings. Today the addon can only **write** them, so it
declares rest values instead of saving the ones that were there. With the API you read the value
first, change it, and restore whatever it was — no declaring anything, and no chance of getting it
wrong.

---

## The three problems you do have to solve

**1. The version.** The API jar here is for an old Minecraft version. For 1.21.11 you need an API
build for that version. **Check this before designing anything on top of it**: if it does not exist
for your version, the problem comes back. Baritone publishes through JitPack, and there are forks
per version.

**2. Only `baritone.api` is supported.** Its own project says so: anything outside it can change
with no notice. Lean on something from inside and you will break on their next release.

**3. That it is installed.** Check with `FabricLoader.isModLoaded("baritone")` and **not** with
Meteor's flag: its `PathManagers` does a `Class.forName("baritone.api.BaritoneAPI")` which **fails
with the obfuscated standalone jar**. On these instances, everything in Meteor that uses Baritone is
silently dead because of exactly that.

---

## What is worth keeping from the addon

Not everything around Baritone was a hack. Three pieces solve real problems that **still exist with
the API**, because they are about Baritone's behavior, not about the transport:

**That it lands at every target.** Its elytra flight means "fly there **and land**": it starts
landing at **48 blocks** from the target — a value pulled from its bytecode, a comparison against
`2304.0d` — and **has no setting that turns it off.** To fly a multi-point route you have to change
its target **before** it gets there. In the addon that is `waypoint-margin`; with the API it will be
the same thing, just via a call.

**That it warns of nothing.** It does not say whether it is going well, whether it is stuck, or
whether it ran out of path. Xploits' stall watch — 30 seconds with no progress, tied to the waypoint
by construction so an index jump does not trigger a false cutoff — is still needed just the same.

**That it swaps the elytra on its own.** It has its own `elytraAutoSwap` and
`elytraMinimumDurability` with different criteria from yours. You have to turn them off if you want
to be the one in charge.

**And the rule that covers all three:** everything you know about Baritone is verified by reading
its bytecode, not its documentation. That does not change with the API — the only thing that changes
is that you no longer have to guess whether the command arrived.

---

## If Baritone does not convince you

It is worth asking the question, because a client of your own can afford what an addon cannot:

**What Baritone gives you for free** is huge: pathfinding, mining, building, and an elytra flight
that actually works. Rewriting that is years of work.

**What it costs you** is a dependency you do not control, obfuscated outside its API, with no
contract on its behavior, that lands when you do not want it to and reports nothing.

**The reasonable stance** is the one the addon already has: use it to fly, and **put your own layer
of judgement on top** — which route, when to cut it short, what to do if it stops responding. You
already have that layer written and tested, and it is the part nobody else has.
