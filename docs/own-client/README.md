# Building a client of your own

Documentation for going from "a Meteor addon" to "a client of your own." Start here.

| Document | What it solves |
|---|---|
| This one | The frame: what it really implies, and the licenses, which condition everything else |
| [Meteor anatomy](meteor-anatomy.md) | Where its code lives, what each part does, and how much work each one is |
| [Integrating Baritone](integrating-baritone.md) | How to do it right, and why in a client of your own it is done **much better** than in the addon |
| [Roadmap](roadmap.md) | In what order to build it, and what carries over from Xploits as-is |

---

## First: the licenses

This is not paperwork. It decides what you can do with your client, and it is worth knowing
**before** writing a line of code, not after.

### Meteor Client — GPL-3.0

Verified in its repository. **And the first thing to understand: the obligations trigger on
distribution, not on use.**

GPL-3.0 says so explicitly: you can run and modify the work **with no condition at all** as long as
you do not transmit it to anyone.

**Private use — no obligation whatsoever.** Forking Meteor, modifying it, and using it yourselves
creates no duty: no publishing sources, no licensing under any terms, no declaring anything. A
private repository is not distributing either.

**The moment it leaves your hands**, it does:

- **With the sources**, for whatever you hand over.
- **Under GPL-3.0**, the same license.
- **Not obfuscated.**
- **Clearly stating** that it uses Meteor code.

Three nuances that tend to surprise people:

- **Distributing does not require publishing to the world.** If you hand it to three people, those
  three have a right to the code; nobody else. No public repository is required.
- **It is GPL, not AGPL.** AGPL triggers on serving over a network; GPL does not. Playing on a
  server with your client is not distributing it.
- **There is no retroactive effect.** If a year from now you decide to share it, you comply then.
  Nothing you do now in private stops you.

**What this means for the project.** If this is going to stay private, the license **has no vote**
in the fork-vs-own-client decision: choose for technical reasons. If it is ever going to leave your
hands, then yes: a client derived from Meteor has to be open, and if the intent was something closed
or paid, the path is not to derive from Meteor but to write your own on Fabric without looking at its
code.

And watch out for the middle path, which is the trap **for that case**: reading its code "for
inspiration" and rewriting it from memory is exactly what GPL considers a derivative work. The clean
line is **using its public ideas** (that a client has modules, settings, and an event bus is
something anyone knows) versus **reproducing its implementation**.

**The advice that holds in both cases:** keep your code separate from the derived one. Not because
of the license, but because the day you want to share something, being able to say "this is mine and
this comes from Meteor" turns a problem into a formality. The pure-cores architecture already does
this.

*(This is how the license reads, not legal advice. If money is ever involved, have someone who knows
look at it.)*

### Baritone — LGPL-3.0, and this one is good news

LGPL is more permissive for this case: **you can link against `baritone.api` without your code
inheriting the license.** What does inherit it is Baritone itself, if you modify it.

Its own project says that **only `baritone.api` is supported**; anything outside it can change with
no notice. See [Integrating Baritone](integrating-baritone.md).

### Yours

Xploits' pure cores are your own code and you can license them however you want — **as long as they
do not end up linked inside a work derived from Meteor**, in which case the whole thing goes under
GPL-3.0.

---

## How much work it is, no sugarcoating

These are Meteor 1.21.11's code files, counted from its sources jar:

| Part | Files | What it is |
|---|---|---|
| **Mixins** | **212** | The part people underestimate. It is what hooks into Minecraft |
| Modules | 197 | The features themselves, across six categories |
| GUI | 134 | ClickGUI, HUD, widgets, themes |
| Utils | 128 | Everything shared: inventory, player, entities, render |
| Events | 69 | The bus's vocabulary |
| Commands | 58 | The client's command system |
| Settings | 36 | Setting types and their serialization |
| Renderer | 23 | 3D and 2D drawing |

**The honest conclusion:** replicating all of Meteor is a project of years. A useful client of your
own does not replicate it — **it picks the part that matters and uses the rest as-is.**

And the part that matters to you, you already have written: the combat judgement, route geometry,
the sweep, the container index. That is the hard part and the one nobody else has. What is missing
is the chassis.

---

## The three ways to do it

**1. Fork Meteor.** You start from its code and modify it. You have the 197 modules and 212 mixins
from minute one; in exchange, you drag along its architecture and its decisions — and GPL-3.0 **the
day you distribute it**, not before. It is the fastest path to something usable and the one that
leaves the least freedom.

**2. A client of your own on Fabric, with Meteor as a conceptual reference.** You write your own
chassis — modules, settings, event bus, GUI — and port your cores on top. Much more work, but the
result is yours and you can fix the things Meteor got wrong. **It is the path Xploits' architecture
already anticipates**: the cores do not touch Meteor precisely for this reason.

**3. Stay an addon and improve the addon.** This is not giving up: it is recognizing that 90% of the
value you have is in the cores, and that Meteor's chassis works. It is the option with the best
result-to-effort ratio, and the one that leaves option 2 open for later.

**My honest recommendation: option 2, but in pieces and with no rush** — and in the meantime, keep
going with option 3. Start with the minimal chassis (modules + settings + bus + an ugly but
functional GUI), port **one** core on top, and check that the whole thing holds up before porting
the rest. If halfway through it turns out not to be worth it, you have not lost the cores: they keep
working in the addon.

What I do **not** recommend is starting by replicating the GUI or the render system. It is the
flashiest part, the longest one, and the one that contributes least to what makes your work special.

---

## What makes a client "professional"

It is not the features. It is four boring things almost no small client has:

1. **That it builds from scratch with one command**, on another machine, with no manual steps.
2. **That it has tests for what it decides.** You already have this: ~640. That is very rare in this
   space.
3. **That it says what it does and what it does not.** A failure that looks like a normal result is
   what kills a user's trust, and it does not come back.
4. **That it survives a Minecraft version.** This is what really separates a living client from an
   abandoned one, and it depends almost entirely on how many mixins you have and how fragile they
   are.

All four are in the [Roadmap](roadmap.md).
