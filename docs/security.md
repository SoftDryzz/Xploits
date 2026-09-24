# Security

What each module touches, what stays written down and what can leak. On an anarchy server the location
of your base is the only irreplaceable thing: everything here is ordered by what it costs to get it
wrong.

---

## 1. What can reach the server chat

**The risk:** `auto-travel` and `nether-sweep` direct Baritone **by writing commands in the chat**,
because its API is obfuscated and cannot be compiled against. If Baritone does not intercept one, that
text **goes out to the server**: you have just announced to everyone that you are using Baritone and
where you are heading.

**Why trusting Baritone is not enough.** Read in its bytecode, its mixin cancels the command only if
its manager recognizes it. Two real gaps remain:

1. The prefix does not match (you changed it in Baritone, or turned off prefix control).
2. There is no Baritone instance bound to your player — there its mixin **returns without cancelling**.

**The net.** While a module is directing, it cancels by itself every outgoing chat packet that starts
with the prefix. It cancels **below** where Baritone cancels, so:

- If Baritone accepts the command, no packet ever exists and the net does not even notice.
- If it does not accept it, the packet is born and **dies inside your client**.

**If you ever get the warning that the net cancelled something, read it**: it is not a failure, it is
the proof that Baritone is not intercepting and that any command you type by hand **would** be
published.

**Where that net lives and why.** In a listener subscribed separately from the module, not in an
`@EventHandler` of its own. Meteor unsubscribes a module **before** calling its `onDeactivate()`, and
the restore —six commands in a row— is sent right there. A net mounted on the module itself would be
dead exactly at the moment of heaviest traffic. It took a whole round to find this out.

**The prefix cannot start with `/`.** With a slash the command goes down the server's command path,
which Baritone does not listen to, and the net would also swallow every other slash command for as
long as the flight lasted — including those of `auto-tpy` and `kit-requester`. It is rejected at
launch.

---

## 2. Your coordinates, and where they live without you thinking about it

| Place | What it keeps | Who writes it |
|---|---|---|
| `logs/latest.log` | **Every chat line**, Baritone's goals included. Since 0.3.1 coordinates are masked (`hide-coordinates-in-log`, `All` by default); older logs are not cleaned | Minecraft |
| `meteor-client/modules.nbt` | The destination if you give it in absolute coordinates | Meteor |
| `meteor-client/xploits/console/` | The console history (up to 30 days); positions only with its `hide-coordinates` turned off | Xploits |
| `xaero/` | Hundreds of MB of mapped terrain | Xaero |
| `TrouserStreak/NewChunks/` | Which chunks have reached you, per server and dimension | Trouser Streak |

**If you share a log so someone can help you debug something, clean it first.** It is the most
indiscreet file you have and the easiest one to send.

**Relative destination instead of absolute.** `auto-travel` accepts the destination as an offset from
where you are. Besides being more convenient, it keeps your coordinates out of Meteor's configuration:
an offset without knowing from where is worth nothing.

**And what the module cannot protect:** this looks after the trace of your flight. It does not look
after you leaving a tunnel, breaking blocks in plain sight, the base being visible from the air, or
loading chunks where you should not.

---

## 3. What the modules write outside themselves

### Baritone settings — saved to disk

`auto-travel` and `nether-sweep` write five Baritone settings on takeoff and give them back on landing.
**Baritone saves them to disk**, so a failure here survives a restart. Before the first goal they also
turn on `censorCoordinates` and `censorRanCommands`, and leave them on: turning them off would undo a
censor you may already have had.

Preparation also sets `elytraTermsAccepted` to true, `elytraPredictTerrain` to false and, when a seed
is configured, `elytraNetherSeed` — none of the three is part of the five restored on landing, so they
stay at those values until you change them by hand.

The resting values are **Baritone's real factory defaults**, read from its bytecode — not values that
seemed reasonable to us. Two of the ones used at first were wrong (`elytraConserveFireworks` is `false`
by default, not `true`; `elytraFireworkSpeed` is `1.2`, not `1`) and would have left every manual flight
of the player slower forever, with no way to trace it back to the addon.

**Known limit:** if you disconnect with the player already null, the restore commands are not sent and
those settings keep their flight values.

### Your Meteor friends list — written by `auto-pvp`

The five combat modules pick their own target, and the only social filter they respect is the friends
list. So `auto-pvp`, while it is on, **puts your people there** —`kit-requester` couriers and the
`auto-tpy` list— so that those modules do not attack them either.

**It writes to configuration that is yours and saved to disk**, so:

- It keeps track of which names **it** put there, and when cleaning up it **only removes those**. A
  friend you already had is never touched.
- If you remove by hand one it put there, it does not put it back.
- **If the client crashes with the module on**, what it put there stays in `friends.nbt` and the next
  session **does not remove it**: it cannot prove it was its own. A `.friends list` every now and then.

**An attack path worth knowing:** with `trust-unknown-couriers` on in `kit-requester`, anyone who
imitates the kit-ready message and sends a TPA is added by themselves to your courier list. That is
why **`auto-pvp` does not sync any courier while that setting is on** — neither the learned ones nor
the ones you wrote, because after a restart they are indistinguishable.

Residual hole: if you turn it on, learn someone and turn it off again, that name can no longer be told
apart from yours. Go over `known-couriers` before turning it off.

### Borrowed Meteor modules

`auto-travel` and `nether-sweep` turn off `elytra-fly` and turn on `elytra-replace` during the flight.
They give them back **in the state they had**, not in a declared one, and **a manual change during the
flight wins** over what was noted.

During the teardown when leaving the world **no module is touched**: Meteor does not mark them as
inactive there and its event bus does not deduplicate, so turning one on would leave it subscribed
**twice** for the rest of the session, with all its handlers running twice. The give-back is applied
on the first tick after joining again.

---

## 4. What the module never does on your behalf

- **`auto-pvp` does not perform combat actions.** It only turns Meteor modules on and off.
- **`auto-tpy` never accepts `/tpahere`.** It only brings people to you; it never moves you.
- **`stash-keeper` moves nothing.** It only looks.
- **`nether-sweep` detects nothing.** It only makes the terrain pass in front of the client.
- **No module flees, or stops flying because someone is nearby.** A sweep is an hour away from home
  with all your gear on, and the module does not manage that risk. Sweeping is something you do with
  what you are willing to lose.

---

## 5. The underlying principle

**A failure can never look like a normal result.**

That is where almost all the odd decisions in the code come from: why a pattern that does not fit is
rejected instead of flying straight, why a badly covered area warns loudly instead of saying
"finished", why a measurement that does not exist throws an exception instead of returning zero.

The case that illustrates it best: if the sweep spaces the lanes badly, strips are left unseen and the
area is marked as combed. **That is never discovered** — you simply do not come back, and the base you
were looking for was there.
