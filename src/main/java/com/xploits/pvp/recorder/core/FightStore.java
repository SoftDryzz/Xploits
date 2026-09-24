package com.xploits.pvp.recorder.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Saves and loads the last {@value #KEEP} fights as one {@code fight-<startedAt>.json} file each,
 * following {@code StashStore}/{@code ProgressStore}: Gson pretty, a {@code .tmp} file moved in with
 * {@code ATOMIC_MOVE} (falling back when that is not supported), and a file that cannot be understood
 * is reported and never overwritten.
 */
public final class FightStore {
    public static final int KEEP = 50;
    public static final int SCHEMA = FightRecord.SCHEMA;

    private static final Pattern FILE_NAME = Pattern.compile("fight-(\\d+)\\.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path folder;

    public FightStore(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /** Writes {@code f} as {@code fight-<startedAt>.json}; if that name is taken, tries startedAt+1ms and so on. */
    public Path save(FightRecord f) throws IOException {
        Files.createDirectories(folder);

        long stamp = f.startedAt();
        Path target;
        while (true) {
            target = folder.resolve("fight-" + stamp + ".json");
            if (!Files.exists(target)) break;
            stamp++;
        }

        Path tmp = folder.resolve(target.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(FileDto.of(f)));
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Every {@code fight-<digits>.json} file in the folder, newest name first; anything else (incl. {@code .tmp}) is ignored. */
    public List<Path> list() throws IOException {
        if (!Files.exists(folder)) return List.of();

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder,
                p -> FILE_NAME.matcher(p.getFileName().toString()).matches())) {
            for (Path p : stream) files.add(p);
        }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return files;
    }

    /** Loads one fight. A file that is missing, empty, corrupt, of an unsupported schema, or missing a list, throws and is left untouched. */
    public FightRecord load(Path file) throws IOException {
        String json = Files.readString(file);

        FileDto dto;
        try {
            dto = GSON.fromJson(json, FileDto.class);
        } catch (JsonParseException e) {
            throw new IOException(file + " is corrupt; it was not modified: " + e.getMessage(), e);
        }
        if (dto == null) throw new IOException(file + " is empty; it was not modified.");
        if (dto.schema != SCHEMA) {
            throw new IOException(file + " has schema " + dto.schema + ", expected " + SCHEMA + "; it was not modified.");
        }

        try {
            return dto.toRecord();
        } catch (RuntimeException e) {
            throw new IOException(file + " is corrupt; it was not modified: " + e.getMessage(), e);
        }
    }

    /** Deletes every fight beyond the newest {@value #KEEP}, by file name only; content is never read. */
    public List<Path> prune() throws IOException {
        List<Path> files = list();
        if (files.size() <= KEEP) return List.of();

        List<Path> removed = new ArrayList<>();
        for (Path file : files.subList(KEEP, files.size())) {
            Files.deleteIfExists(file);
            removed.add(file);
        }
        return removed;
    }

    private static <T, R> List<R> map(List<T> list, Function<T, R> fn) {
        if (list == null) return null;
        List<R> out = new ArrayList<>(list.size());
        for (T t : list) out.add(fn.apply(t));
        return out;
    }

    // Private DTOs, field names matching the FightRecord component names exactly (spec §FightRecord JSON).

    private static final class FileDto {
        int schema;
        String addonVersion;
        long startedAt;
        long endedAt;
        int durationSeconds;
        String outcome;
        boolean truncated;
        String mode;
        int autoPvpSeconds;
        List<OpponentDto> opponents;
        int maxHostilesNear;
        SelfDto self;
        List<DamageDto> damage;
        int damageEventsDropped;
        List<SampleDto> samples;
        List<String> modulesAtStart;
        List<ModuleChangeDto> moduleChanges;
        List<PhaseChangeDto> phases;

        static FileDto of(FightRecord f) {
            FileDto dto = new FileDto();
            dto.schema = f.schema();
            dto.addonVersion = f.addonVersion();
            dto.startedAt = f.startedAt();
            dto.endedAt = f.endedAt();
            dto.durationSeconds = f.durationSeconds();
            dto.outcome = f.outcome().name();
            dto.truncated = f.truncated();
            dto.mode = f.mode().name();
            dto.autoPvpSeconds = f.autoPvpSeconds();
            dto.opponents = map(f.opponents(), OpponentDto::of);
            dto.maxHostilesNear = f.maxHostilesNear();
            dto.self = SelfDto.of(f.self());
            dto.damage = map(f.damage(), DamageDto::of);
            dto.damageEventsDropped = f.damageEventsDropped();
            dto.samples = map(f.samples(), SampleDto::of);
            dto.modulesAtStart = f.modulesAtStart();
            dto.moduleChanges = map(f.moduleChanges(), ModuleChangeDto::of);
            dto.phases = map(f.phases(), PhaseChangeDto::of);
            return dto;
        }

        FightRecord toRecord() {
            return new FightRecord(schema, addonVersion, startedAt, endedAt, durationSeconds,
                FightOutcome.valueOf(outcome), truncated, FightMode.valueOf(mode), autoPvpSeconds,
                map(opponents, OpponentDto::toOpponent), maxHostilesNear, self == null ? null : self.toSelfTotals(),
                map(damage, DamageDto::toDamageEvent), damageEventsDropped, map(samples, SampleDto::toSample),
                modulesAtStart, map(moduleChanges, ModuleChangeDto::toModuleChange),
                map(phases, PhaseChangeDto::toPhaseChange));
        }
    }

    private static final class OpponentDto {
        String name;
        int pops;
        boolean died;
        int hitsOnYou;
        double damageToYou;
        int hitsByYou;

        static OpponentDto of(FightRecord.Opponent o) {
            OpponentDto dto = new OpponentDto();
            dto.name = o.name();
            dto.pops = o.pops();
            dto.died = o.died();
            dto.hitsOnYou = o.hitsOnYou();
            dto.damageToYou = o.damageToYou();
            dto.hitsByYou = o.hitsByYou();
            return dto;
        }

        FightRecord.Opponent toOpponent() {
            return new FightRecord.Opponent(name, pops, died, hitsOnYou, damageToYou, hitsByYou);
        }
    }

    private static final class SelfDto {
        int pops;
        int totemsStart;
        int totemsEnd;
        boolean offhandTotemEnd;
        double damageTaken;
        int crystalsPlaced;
        int crystalsBroken;
        int attacks;
        int enemyCrystalsNear;

        static SelfDto of(FightRecord.SelfTotals s) {
            SelfDto dto = new SelfDto();
            dto.pops = s.pops();
            dto.totemsStart = s.totemsStart();
            dto.totemsEnd = s.totemsEnd();
            dto.offhandTotemEnd = s.offhandTotemEnd();
            dto.damageTaken = s.damageTaken();
            dto.crystalsPlaced = s.crystalsPlaced();
            dto.crystalsBroken = s.crystalsBroken();
            dto.attacks = s.attacks();
            dto.enemyCrystalsNear = s.enemyCrystalsNear();
            return dto;
        }

        FightRecord.SelfTotals toSelfTotals() {
            return new FightRecord.SelfTotals(pops, totemsStart, totemsEnd, offhandTotemEnd, damageTaken,
                crystalsPlaced, crystalsBroken, attacks, enemyCrystalsNear);
        }
    }

    private static final class DamageDto {
        long tick;
        String kind;
        String by;
        String attacker;
        double before;
        double after;
        boolean lethal;

        static DamageDto of(FightRecord.DamageEvent e) {
            DamageDto dto = new DamageDto();
            dto.tick = e.tick();
            dto.kind = e.kind().name();
            dto.by = e.by().name();
            dto.attacker = e.attacker();
            dto.before = e.before();
            dto.after = e.after();
            dto.lethal = e.lethal();
            return dto;
        }

        FightRecord.DamageEvent toDamageEvent() {
            return new FightRecord.DamageEvent(tick, DamageKind.valueOf(kind), AttackerKind.valueOf(by), attacker,
                before, after, lethal);
        }
    }

    private static final class SampleDto {
        int second;
        double health;
        double incoming;
        int totems;
        boolean offhandTotem;
        int crystals;
        int obsidian;
        int gapples;
        int armor;
        Double nearestHostile;
        int hostilesNear;
        boolean inHole;
        boolean gliding;
        boolean autoPvp;
        int placed;
        int broken;
        int spawnedNear;

        static SampleDto of(FightRecord.Sample s) {
            SampleDto dto = new SampleDto();
            dto.second = s.second();
            dto.health = s.health();
            dto.incoming = s.incoming();
            dto.totems = s.totems();
            dto.offhandTotem = s.offhandTotem();
            dto.crystals = s.crystals();
            dto.obsidian = s.obsidian();
            dto.gapples = s.gapples();
            dto.armor = s.armor();
            dto.nearestHostile = s.nearestHostile();
            dto.hostilesNear = s.hostilesNear();
            dto.inHole = s.inHole();
            dto.gliding = s.gliding();
            dto.autoPvp = s.autoPvp();
            dto.placed = s.placed();
            dto.broken = s.broken();
            dto.spawnedNear = s.spawnedNear();
            return dto;
        }

        FightRecord.Sample toSample() {
            return new FightRecord.Sample(second, health, incoming, totems, offhandTotem, crystals, obsidian,
                gapples, armor, nearestHostile, hostilesNear, inHole, gliding, autoPvp, placed, broken, spawnedNear);
        }
    }

    private static final class ModuleChangeDto {
        int second;
        String module;
        boolean on;

        static ModuleChangeDto of(FightRecord.ModuleChange c) {
            ModuleChangeDto dto = new ModuleChangeDto();
            dto.second = c.second();
            dto.module = c.module();
            dto.on = c.on();
            return dto;
        }

        FightRecord.ModuleChange toModuleChange() {
            return new FightRecord.ModuleChange(second, module, on);
        }
    }

    private static final class PhaseChangeDto {
        int second;
        String state;
        String posture;
        String target;

        static PhaseChangeDto of(FightRecord.PhaseChange c) {
            PhaseChangeDto dto = new PhaseChangeDto();
            dto.second = c.second();
            dto.state = c.state().name();
            dto.posture = c.posture().name();
            dto.target = c.target();
            return dto;
        }

        FightRecord.PhaseChange toPhaseChange() {
            return new FightRecord.PhaseChange(second, CombatState.valueOf(state), CombatPosture.valueOf(posture), target);
        }
    }
}
