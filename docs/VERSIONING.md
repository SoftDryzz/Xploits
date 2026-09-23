# Versioning

## Scheme

[Semantic Versioning](https://semver.org/) — `MAJOR.MINOR.PATCH`.

Xploits is in **0.x**: modules and settings can still change between releases. It moves to `1.0.0`
when the module set and its settings are declared stable.

While in `0.x`:

| Part | Bumps for | Examples |
|---|---|---|
| **MINOR** `0.X.0` | A new feature, or any incompatible change | A new module, a language selector, renaming a module |
| **PATCH** `0.0.X` | Fixes and tuning that change nothing a player has already set up | A wrong warning, a threshold, a crash fix |

From `1.0.0` on, full SemVer: **MAJOR** for incompatible changes, **MINOR** for compatible features,
**PATCH** for fixes.

### What counts as incompatible

Xploits has no public API. What breaks is **work the player has already done**:

- renaming a module id or a setting id — Meteor stores settings by id and silently drops the old value;
- changing an on-disk format: the stash index, kit-requester progress, the console's `vivo.log`;
- removing or renaming a `.xploits` subcommand;
- moving to another Minecraft or Meteor version.

## Where the version lives

In one place: `mod-version` in `gradle/libs.versions.toml`. It becomes the jar name
(`build/libs/xploits-<version>.jar`) and the `version` in the jar's `fabric.mod.json`.

## Changelog

[`CHANGELOG.md`](../CHANGELOG.md), in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
format. Every branch that changes something a player notices adds its lines under
`## [Unreleased]`, in that same branch. A release turns that section into the version.

## Releasing

Releases are made **directly on `main`**, after the branches they contain are merged.

1. Bump `mod-version` in `gradle/libs.versions.toml`.
2. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [X.Y.Z] — YYYY-MM-DD` and open a new empty
   `## [Unreleased]` above it.
3. Build:
   ```
   ./gradlew build
   ```
   It runs the whole test suite and must be green. **Tag nothing before this passes**: a tag on a
   commit that does not build is a release nobody can reproduce.
4. Commit and tag:
   ```
   git add gradle/libs.versions.toml CHANGELOG.md
   git commit -m "chore(release): vX.Y.Z"
   git tag -a vX.Y.Z -m "Xploits X.Y.Z"
   ```
5. Install. **Close Minecraft first**: a running game locks the jar. For every instance whose `mods`
   folder holds an `xploits-*.jar`:
   - delete **every** `xploits-*.jar` there — the jar name carries the version, so leaving the old
     one loads Xploits twice;
   - copy `build/libs/xploits-X.Y.Z.jar`;
   - check that its SHA-256 equals the built jar's.
6. Nothing is pushed to GitHub unless the owner asks.

## Git conventions

- Commit messages in English, with a prefix: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`,
  `chore:`. The body explains **why**, not what — the diff already says what.
- No attribution trailer of any kind. The commit ends on its last line of text.
- `git add` by file name, never `git add -A`.
- Branches `feat/<english-kebab>`, `fix/…`, `chore/…`, `docs/…`, each in its own worktree under
  `.worktrees/<name>`.
- Branches are merged into `main` with `--no-ff` and a message that summarises the change.
