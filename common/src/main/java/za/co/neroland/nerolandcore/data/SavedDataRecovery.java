package za.co.neroland.nerolandcore.data;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import za.co.neroland.nerolandcore.NerolandCoreCommon;
import za.co.neroland.nerolandcore.telemetry.NerolandCoreTelemetry;

/**
 * Shared crash-safe accessor for ecosystem {@link SavedData} stores.
 *
 * <p>Vanilla's {@code SavedDataStorage.computeIfAbsent} reads {@code data/<id>.dat} on first access
 * and lets any failure (corrupt, truncated or unreadable file) propagate unchecked. Because these
 * stores are fetched from per-tick and per-command paths, one bad file crashes the server tick loop
 * on <em>every</em> tick — the failure class behind MC-NEROSPACE-H. Every Neroland
 * {@code SavedData.get()} must therefore route through {@link #get}, which degrades to a recovered
 * store rather than a repeating hard crash:</p>
 *
 * <ol>
 *   <li><b>Primary:</b> vanilla storage ({@code computeIfAbsent}).</li>
 *   <li><b>Backup:</b> on a read failure, restore from Core's last-known-good file
 *       ({@code data/<namespace>_<path>_backup.dat} in the same dimension folder), so state rolls
 *       back to the last backup instead of being lost outright.</li>
 *   <li><b>Fresh:</b> if the backup is missing or unreadable too, start with a fresh instance —
 *       degraded but playable, never a hard crash.</li>
 * </ol>
 *
 * <p>Whichever step wins is installed into the storage cache via {@code set} (so later ticks hit the
 * cache instead of re-reading the bad file) and marked dirty (so a clean primary file is rewritten
 * at the next level save). The failure is logged once and reported as a <em>handled</em>
 * (non-fatal) telemetry event through the existing scrubbed pipeline. Recovery metadata is
 * deliberately non-identifying: only the stable store label and the dimension are logged or
 * attached to telemetry.
 *
 * <p><b>Backup writing</b> piggybacks on {@link #get}: at most every {@value #DIRTY_INTERVAL_MS} ms
 * while the store is dirty (state changed) or every {@value #IDLE_INTERVAL_MS} ms otherwise, the
 * store is re-encoded with its own {@link SavedDataType#codec() codec}; the file is only rewritten
 * when the content actually changed (hash compare) or the backup has gone missing, via
 * write-temp-then-atomic-rename so a crash mid-write can never corrupt the backup itself.
 * Periodic backup failures are best-effort and logged at debug. Explicit {@code backupNow}
 * failures propagate so privacy erasure cannot be reported as complete with a stale backup.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> The backup holds exactly the same fields as the vanilla primary
 * file, inside the same world save — so it is a <em>second copy of the same player-keyed rows</em>
 * and an erasure request must reach it. Every Core store therefore calls {@link #backupNow}
 * immediately after purging a player (see {@code ProgressionState.eraseFor} and friends), instead
 * of leaving the erased rows in the backup until the next periodic pass. Downstream mods that adopt
 * this helper <b>must do the same</b> in their {@link PlayerDataEraser}.
 *
 * <p><b>Registry-aware encoding (Core 1.15.0).</b> Backups are encoded and decoded with the
 * level's registry-aware ops ({@code RegistryOps} over NBT), exactly as vanilla's own storage does,
 * so a store holding registry-backed data (item stacks with components, holders) round-trips on 26.x.
 * {@link #backupNow(ServerLevel, SavedDataType, SavedData, String, HolderLookup.Provider)} accepts an
 * explicit registry provider for callers that hold one.
 *
 * <p><b>Silent decode failures (Core 1.15.0).</b> On 26.x vanilla's storage does not throw for an
 * unreadable file or a codec error: it logs, then hands back a fresh (or partially decoded) store,
 * which the first backup pass would then write over the good backup. So the first time each store is
 * fetched from a level's storage, {@link #get} re-reads the primary file itself and decodes it with
 * the store's codec. If that fails, the last-known-good backup is restored (installed and marked
 * dirty, as above) and the failure is logged and reported like any other recovery. With no usable
 * backup, vanilla's store is kept as-is (it is not marked dirty, so the unreadable primary is not
 * overwritten until the store genuinely changes) and the failure is still reported. A primary file
 * that is absent, or not in the {@code {"data": ...}} layout, is not judged.
 *
 * @see PlayerDataErasure
 */
public final class SavedDataRecovery {

    /** While dirty (state changed since the last vanilla save), refresh the backup at most this often. */
    private static final long DIRTY_INTERVAL_MS = 5_000L;
    /** Re-check interval when not dirty (still hash-compared, so unchanged state writes nothing). */
    private static final long IDLE_INTERVAL_MS = 5L * 60_000L;

    /**
     * Throttle + content bookkeeping, one entry per (dimension, store). A concurrent map rather than
     * a plain one: Core is a library and cannot assume every downstream call site is on the server
     * thread. The key is a record rather than a concatenated string because {@link #get} sits on
     * per-tick paths (gate checks) and must not allocate a formatted dimension string per call.
     */
    private static final Map<BackupKey, BackupState> BACKUPS = new ConcurrentHashMap<>();

    /**
     * Stores whose primary file has already been verified, per data-storage instance. Weak keys so an
     * integrated server's storage (singleplayer world switches) is forgotten with it, and a newly
     * opened world is verified afresh.
     */
    private static final Map<Object, Set<String>> VERIFIED = Collections.synchronizedMap(new WeakHashMap<>());

    private SavedDataRecovery() {
    }

    /**
     * Loads a store through vanilla storage, recovering from exceptions and unexpected nulls, and
     * opportunistically refreshing this store's last-known-good backup.
     *
     * @param level    the level whose data storage owns the store (Core's own stores use the overworld)
     * @param type     the vanilla saved-data type
     * @param fallback supplier for a fresh, empty store; must never return {@code null}
     * @param name     stable, non-identifying label for logs/telemetry and the backup file name
     *                 (e.g. {@code "nerolandcore:progression"})
     */
    public static <T extends SavedData> T get(ServerLevel level, SavedDataType<T> type,
            Supplier<T> fallback, String name) {
        if (level == null || type == null || fallback == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("SavedData recovery arguments must be present");
        }
        T instance;
        try {
            instance = SavedDataCompat.computeIfAbsent(level, type);
        } catch (Exception failure) {
            instance = recover(level, type, fallback, name, failure);
        }
        if (instance == null) {
            // computeIfAbsent's generic return is unannotated; a null here would NPE the caller
            // mid-tick just as surely as a thrown exception, so it takes the same recovery path.
            instance = recover(level, type, fallback, name, null);
        } else if (firstAccess(level, name)) {
            // Must run before the first backup pass: vanilla 26.x returns a fresh store for an
            // undecodable file, and backing that up would overwrite the last-known-good copy.
            instance = verifyPrimary(level, type, instance, name);
        }
        maybeBackup(level, type, instance, name, false, null);
        return instance;
    }

    /**
     * Forces an immediate backup refresh (bypassing the interval throttle, still hash-compared).
     *
     * <p><b>Call this straight after erasing a player</b> so the anonymisation reaches the backup
     * file in the same request rather than at the next periodic pass — the backup is a second copy
     * of the same personal data and POPIA/GDPR erasure has to cover it.
     *
     * <p>The write is still skipped when the encoded content is unchanged, but a forced call does
     * re-encode the store, so a bulk operation (the retention sweep erasing many players in one go)
     * pays one encode + write per player per store. That is deliberate: correctness of the erasure
     * outranks the latency of an operator-triggered sweep.
     *
     * @throws IllegalStateException if the backup could not be written
     */
    public static <T extends SavedData> void backupNow(ServerLevel level, SavedDataType<T> type,
            T instance, String name) {
        if (level == null || type == null || instance == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("SavedData backup arguments must be present");
        }
        maybeBackup(level, type, instance, name, true, null);
    }

    /**
     * {@link #backupNow(ServerLevel, SavedDataType, SavedData, String)} with an explicit registry
     * provider (for example {@code server.registryAccess()}) used to encode registry-backed data.
     * Added in Core 1.15.0; the 4-argument form uses the level's own {@code registryAccess()}, which
     * is equivalent on a running server.
     *
     * @param registries registry lookup for encoding; {@code null} falls back to the level's
     */
    public static <T extends SavedData> void backupNow(ServerLevel level, SavedDataType<T> type,
            T instance, String name, HolderLookup.Provider registries) {
        if (level == null || type == null || instance == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("SavedData backup arguments must be present");
        }
        maybeBackup(level, type, instance, name, true, registries);
    }

    // --- Primary verification (26.x silent decode failures) ------------------

    private static boolean firstAccess(ServerLevel level, String name) {
        Object storage = level.getDataStorage();
        synchronized (VERIFIED) {
            return VERIFIED.computeIfAbsent(storage, ignored -> new HashSet<>()).add(name);
        }
    }

    private static <T extends SavedData> T verifyPrimary(ServerLevel level, SavedDataType<T> type, T instance,
            String name) {
        Exception failure;
        try {
            Path primary = primaryFile(level, SavedDataCompat.fileStem(type));
            if (primary == null) {
                return instance; // nothing on disk yet: a genuinely new store
            }
            CompoundTag tag;
            try {
                tag = NbtIo.readCompressed(primary, NbtAccounter.unlimitedHeap());
            } catch (Exception unreadable) {
                failure = new SavedDataDecodeException("primary file is unreadable", unreadable);
                return recoverDecode(level, type, instance, name, failure);
            }
            Tag data = tag.get("data");
            if (data == null) {
                return instance; // not the layout this check understands; do not judge it
            }
            DataResult<T> parsed = SavedDataCompat.codec(type, level).parse(ops(level, null), data);
            if (parsed.error().isEmpty()) {
                return instance;
            }
            // The DataResult message is deliberately dropped: it can quote stored values (UUIDs).
            failure = new SavedDataDecodeException("primary file failed codec decode", null);
        } catch (Exception verificationFailure) {
            // Verification is best-effort: never let the check itself break the load.
            NerolandCoreCommon.LOGGER.debug("[Neroland Core] Could not verify saved data '{}'", name,
                    verificationFailure);
            return instance;
        }
        return recoverDecode(level, type, instance, name, failure);
    }

    private static <T extends SavedData> T recoverDecode(ServerLevel level, SavedDataType<T> type, T vanilla,
            String name, Exception failure) {
        T restored = readBackup(level, type, name, failure);
        if (restored != null) {
            try {
                SavedDataCompat.set(level, type, restored);
                restored.setDirty();
            } catch (Exception installationFailure) {
                failure.addSuppressed(installationFailure);
                restored = null;
            }
        }
        boolean fromBackup = restored != null;
        NerolandCoreCommon.LOGGER.warn(
                "[Neroland Core] Saved data '{}' in {} could not be decoded ({}): {}.",
                name, level.dimension(), failure.getMessage(),
                fromBackup ? "restored the last-known-good backup; a clean file will be rewritten at the next save"
                        : "no usable backup, kept the store vanilla loaded (it may be empty or partial)");
        NerolandCoreTelemetry.breadcrumb("saved_data_recovery", name);
        NerolandCoreTelemetry.captureHandledException(failure, "saved_data_recovery",
                name + (fromBackup ? "|decode_backup_restored" : "|decode_no_backup"));
        return fromBackup ? restored : vanilla;
    }

    /**
     * The vanilla primary file for {@code id} under this level's storage, or {@code null} if none of
     * the known 26.x layouts has it: {@code <dimension folder>/data/<namespace>/<path>.dat}, also
     * probed under {@code dimensions/<dim namespace>/<dim path>/} in case the storage folder resolves
     * to the world root.
     */
    private static Path primaryFile(ServerLevel level, String stem) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path storage = DimensionType.getStorageFolder(level.dimension(), worldRoot);
        Identifier dim = level.dimension().identifier();
        Path[] candidates = {
                storage.resolve("data").resolve(stem + ".dat"),
                worldRoot.resolve("dimensions").resolve(dim.getNamespace()).resolve(dim.getPath())
                        .resolve("data").resolve(stem + ".dat"),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Registry-aware NBT ops: the explicit provider if given, else the level's registry access. */
    private static DynamicOps<Tag> ops(ServerLevel level, HolderLookup.Provider registries) {
        HolderLookup.Provider lookup = registries != null ? registries : level.registryAccess();
        return lookup.createSerializationContext(NbtOps.INSTANCE);
    }

    /** A codec/IO failure detected by {@link #verifyPrimary}; carries no stored values. */
    private static final class SavedDataDecodeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SavedDataDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // --- Recovery -----------------------------------------------------------

    private static <T extends SavedData> T recover(ServerLevel level, SavedDataType<T> type,
            Supplier<T> fallback, String name, Exception failure) {
        T restored = readBackup(level, type, name, failure);
        boolean fromBackup = restored != null;
        T instance = restored;
        if (instance == null) {
            instance = fallback.get();
            if (instance == null) {
                throw new IllegalStateException("SavedData fallback returned null for " + name);
            }
        }
        try {
            // Install into the storage cache (stops the per-tick re-read of the bad file) and mark
            // dirty so the next level save replaces the corrupt primary with a clean one.
            SavedDataCompat.set(level, type, instance);
            instance.setDirty();
        } catch (Exception installationFailure) {
            if (failure != null) {
                failure.addSuppressed(installationFailure);
            } else {
                NerolandCoreCommon.LOGGER.warn(
                        "[Neroland Core] Could not install the recovered saved data '{}'.",
                        name, installationFailure);
            }
        }
        NerolandCoreCommon.LOGGER.warn(
                "[Neroland Core] Recovered saved data '{}' in {}: {}. "
                        + "A clean file will be rewritten at the next save.",
                name, level.dimension(),
                fromBackup ? "restored the last-known-good backup" : "started with a fresh store",
                failure);
        NerolandCoreTelemetry.breadcrumb("saved_data_recovery", name);
        if (failure != null) {
            NerolandCoreTelemetry.captureHandledException(failure, "saved_data_recovery",
                    name + (fromBackup ? "|backup_restored" : "|fresh_start"));
        }
        return instance;
    }

    private static <T extends SavedData> T readBackup(ServerLevel level, SavedDataType<T> type,
            String name, Exception primaryFailure) {
        try {
            Path file = backupFile(level, name);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Tag data = tag.get("data");
            if (data == null) {
                return null;
            }
            return SavedDataCompat.codec(type, level).parse(ops(level, null), data).result().orElse(null);
        } catch (Exception backupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(backupFailure); // backup unreadable too (same failing disk?)
            }
            return null;
        }
    }

    // --- Backup writing -----------------------------------------------------

    private static <T extends SavedData> void maybeBackup(ServerLevel level, SavedDataType<T> type,
            T instance, String name, boolean force, HolderLookup.Provider registries) {
        BackupState state = BACKUPS.computeIfAbsent(new BackupKey(level.dimension(), name),
                ignored -> new BackupState());
        long now = System.currentTimeMillis();
        if (!force) {
            long interval = instance.isDirty() ? DIRTY_INTERVAL_MS : IDLE_INTERVAL_MS;
            if (state.lastAttemptMs != 0L && now - state.lastAttemptMs < interval) {
                return;
            }
        }
        state.lastAttemptMs = now;
        try {
            Path file = backupFile(level, name);
            CompoundTag tag = new CompoundTag();
            tag.put("data", SavedDataCompat.codec(type, level).encodeStart(ops(level, registries), instance).getOrThrow());
            int hash = tag.hashCode();
            if (state.written && state.lastWrittenHash == hash && Files.isRegularFile(file)) {
                return; // unchanged since the last written backup, and that backup is still there
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(tag, tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            state.lastWrittenHash = hash;
            state.written = true;
        } catch (Exception backupFailure) {
            reportBackupFailure(name, force, backupFailure);
        }
    }

    static void reportBackupFailure(String name, boolean force, Exception failure) {
        if (force) throw new IllegalStateException("Could not refresh saved-data backup", failure);
        NerolandCoreCommon.LOGGER.debug("[Neroland Core] Could not write saved-data backup '{}'", name, failure);
    }

    /** One store in one dimension. {@code ResourceKey} hashes by identity, so this is cheap. */
    private record BackupKey(ResourceKey<Level> dimension, String name) {
    }

    /** Mutable bookkeeping for one {@link BackupKey}; only ever touched under that key's entry. */
    private static final class BackupState {
        private volatile long lastAttemptMs;
        private volatile int lastWrittenHash;
        private volatile boolean written;
    }

    /** {@code <dimension folder>/data/<namespace>_<path>_backup.dat} — colon-free for Windows. */
    private static Path backupFile(ServerLevel level, String name) {
        Path dimensionRoot = DimensionType.getStorageFolder(
                level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
        String fileName = name.replace(':', '_').replace('/', '_') + "_backup.dat";
        return dimensionRoot.resolve("data").resolve(fileName);
    }
}
