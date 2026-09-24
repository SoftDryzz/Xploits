# Conventions

How work is done in this repo, and why. Almost every one of these rules comes from a concrete failure
that cost a review round.

---

## Language

**The repo is in English.** Code, comments, javadoc, commit messages and documentation are written in
English. See [Versioning](VERSIONING.md) for the commit format.

- **Player text lives only in the catalogs**, `xploits/lang/es.lang` and `xploits/lang/en.lang`, and
  every key exists in both. The code never writes a sentence for the player: it names a key.
- **`README.md` (English) and `README.es.md` (Spanish) change together.** A change to one is made to
  the other in the same commit, section by section.
- **Specs and plans live in `docs/superpowers/` locally and are never committed.** The folder is
  ignored; code comments may cite a spec by name, for whoever has the local copy.

**Meteor setting ids are English too** (`waypoint-margin`, `lane-width`, `spiral-radius`), in both
languages. They are part of Meteor's interface, which the player sees next to the settings of every
other module; they do not change with the chosen language.

**And a rejection message that names a setting has to name it exactly as it appears in the
interface**, in both catalogs. If they diverge, the message sends the player looking for something that
does not exist under that name.

---

## Verify before claiming

**Never claim what a Meteor, Minecraft or Baritone API does without having read it.** Meteor's sources
jar is in the Gradle cache; the remapped Minecraft one, in the project's; Baritone has to be
disassembled with `javap` because it ships obfuscated.

This rule exists because **two false things about Meteor were taken as true** by reasoning instead of
reading. And the third time it was actually read, this turned up: `blocksMovement()` considers a slab
solid, so standing on a slab classified the enemy as buried and turned off the crystal aura in the
middle of the fight.

**Specs carry a "Verified API facts" section** with the fact and its consequence. If a fact is not
there, it is not verified.

---

## Pure core and thin adapter

The rule is in [Architecture](architecture.md). In practice:

- If you find yourself writing a decision in the adapter, **it goes to the core**.
- The core does not import `net.minecraft` or `meteordevelopment`. Not one.
- The adapter **measures and executes**; it does not decide.

It has been broken three times and all three cost a round.

---

## Tests: mutation is not optional

**A green test only proves that the test passes.**

Before accepting a protection, **break what it protects on purpose and check that some test goes
red**. If it survives, that test protects nothing.

This is not theory. In this repo:

- A planner test spent **two rounds green** protecting code that could break without it noticing,
  because another fix in the same commit covered the gap.
- The test that checked the area coverage **asserted the right property** and still did not check it:
  the tolerance matched the spacing between lanes, so it only constrained that.
- A mutation passed green because the `sed` never got applied. **Check that you really mutated**
  before accepting the result.

**Compare against values worked out by hand**, not against the same formula the code uses: if they
share the error, the test passes anyway.

---

## Reject rather than degrade

When something cannot be done as asked, **it is rejected, saying which setting to change and to what
value**. Something similar is never done silently.

The literal doctrine, written in the code: *"what still works when capped is capped; what does not is rejected"*.

The case that justifies it: a decoy pattern that did not fit the travel distance returned the straight
route. The player thought it was weaving, adjusted their behavior to a protection that did not exist,
and flew a straight line to their base. **Five different doors led to that same failure** and they
were closed one by one.

**A rejection that does not say how to get unstuck is almost as bad as silence.** The message names
the setting, its current value and what to set it to.

---

## Bias matters: ask which way it errs

When you choose a rounding, a threshold or a default value, ask yourself **what happens if you are
wrong in each direction**. It is almost never symmetric.

- Making a lane too narrow costs flight. Making it too wide leaves strips unseen **marked as combed**.
  Round down.
- Underestimating the distance left looks prudent and is the opposite: it makes the module say "your
  fireworks will last" when they will not.
- Leaving the crystal aura on too long costs a few crystals. Turning it off too early costs the fight.

---

## A failure can never look like a normal result

This is the principle that governs everything else.

- A measurement that does not exist **throws**; it does not return zero.
- A badly covered area **warns loudly with a toast**; it does not come out in an `info` that can be
  turned off.
- A method called "total" **includes everything** it says it includes. That exact failure was fixed
  **twice** in the same branch, one level higher each time.
- "I don't know" is a reason for caution, never for carrying on quietly.

---

## Commits

**No attribution line of any kind.** No `Co-Authored-By`, no `Generated with`, no mention of any tool.
The commit ends at its last line of text.

**The message explains the why, not the what.** The diff already says what changed. What cannot be
reconstructed later is why this was chosen and not the other thing.

`git add` by file name, never `git add -A`.

---

## Workflow

1. **Design first**, in `docs/superpowers/specs/` (local, never committed), with the API facts
   verified.
2. **Plan** in `docs/superpowers/plans/` (local too), split into tasks with their deliverable and
   their tests.
3. **Implementation** task by task, each with its review before moving to the next.
4. **Whole-branch review** at the end, which is the only one that sees the **gaps between tasks**.

Step 4 is not ceremony. On the last branch it found two critical failures that the six per-task
reviews could not see, because each one looked at its own piece and in its own piece nothing was
missing.

---

## What is not done

**What already works is not reimplemented.** Before building something, check whether one of the
installed mods already does it. On the last branch we came close to rewriting a base detector, a
coverage map and a chunk logger — all three already existed, installed and better.

Half of a module's design can be **the list of what it does not do and who does it instead**.
