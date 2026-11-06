package za.co.neroland.nerolandcore.data;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.NerolandCoreCommon;
import za.co.neroland.nerolandcore.config.CoreConfig;

/**
 * The shared per-player data-erasure hook (POPIA/GDPR). Every Core-storing system —
 * and every downstream mod that stores player data (NeroEconomy, NeroFactions,
 * NeroSecurity, NeroQuests, NeroEvents) — registers a {@link PlayerDataEraser} here,
 * so a single {@link #erase(MinecraftServer, UUID)} call purges a player across the
 * whole ecosystem. The same registry drives the inactivity {@linkplain
 * #purgeInactive(MinecraftServer) retention sweep}.
 *
 * <p>Erasure does not log the player's identity (a UUID is personal data); only an
 * anonymous count/acknowledgement is logged.
 */
public final class PlayerDataErasure {

    private static final List<PlayerDataEraser> ERASERS = new CopyOnWriteArrayList<>();

    private PlayerDataErasure() {
    }

    /** Register a system's eraser. Call once at init. */
    public static void register(PlayerDataEraser eraser) {
        ERASERS.add(eraser);
    }

    /**
     * Remove a previously registered eraser.
     *
     * <p>Production code never needs this — an eraser registered at init stays for the life of the
     * process. It exists for test teardown, so a suite (notably {@link ErasureConformance}, which
     * temporarily registers a deliberately failing canary) can restore the registry to exactly the
     * state it found. Removing a real system's eraser at runtime would silently break erasure for
     * that system, which is why this is not part of the supported downstream API.
     *
     * @return {@code true} if the eraser was registered and has been removed
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static boolean unregister(PlayerDataEraser eraser) {
        return ERASERS.remove(eraser);
    }

    /** How many erasers are currently registered (diagnostics and conformance reporting only). */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static int registeredCount() {
        return ERASERS.size();
    }

    /**
     * Purge everything stored for {@code player} across every registered system.
     *
     * <p>Every eraser is attempted even if an earlier one fails. A failure is caught as
     * {@link Throwable} rather than {@code RuntimeException} deliberately: an {@link Error}
     * thrown out of one mod's eraser (a {@code NoSuchMethodError} from a version mismatch,
     * for instance) must not abort the remaining erasers and leave a silent partial erasure.
     * Failures are logged with the offending eraser's class so the responsible mod can be
     * identified, and the summary line reports how many systems actually succeeded.
     */
    public static void erase(MinecraftServer server, UUID player) {
        eraseAndReport(server, player);
    }

    /** Attempts every eraser and returns whether all of them succeeded. */
    public static boolean eraseAndReport(MinecraftServer server, UUID player) {
        int failed = 0;
        for (PlayerDataEraser eraser : ERASERS) {
            try {
                eraser.erase(server, player);
            } catch (Throwable t) {
                failed++;
                NerolandCoreCommon.LOGGER.warn(
                        "[Neroland Core] Data eraser '{}' failed during erasure; continuing with the rest. "
                                + "This player's data may be only partially erased.",
                        eraser.getClass().getName(), t);
            }
        }
        int total = ERASERS.size();
        if (failed > 0) {
            NerolandCoreCommon.LOGGER.warn(
                    "[Neroland Core] Player data erasure INCOMPLETE: {} of {} systems failed.", failed, total);
        } else {
            NerolandCoreCommon.LOGGER.info("[Neroland Core] Player data erased on request ({} systems).", total);
        }
        return failed == 0;
    }

    /**
     * Erase data for every player inactive longer than the configured retention
     * period ({@code dataRetentionDays}; 0 disables). @return the number fully purged.
     */
    public static int purgeInactive(MinecraftServer server) {
        return purgeInactiveReport(server).purged();
    }

    public record PurgeResult(int purged, int failed) { }

    public static PurgeResult purgeInactiveReport(MinecraftServer server) {
        int days = CoreConfig.DATA_RETENTION_DAYS.get();
        if (days <= 0) {
            return new PurgeResult(0, 0);
        }
        PlayerActivity activity = PlayerActivity.get(server);
        List<UUID> stale = activity.stalerThan(days);
        int purged = purgeStale(activity, stale, player -> eraseAndReport(server, player),
                () -> SavedDataRecovery.backupNow(server.overworld(), PlayerActivity.TYPE, activity,
                        PlayerActivity.ID.toString()));
        if (purged > 0) {
            NerolandCoreCommon.LOGGER.info("[Neroland Core] Retention sweep purged {} inactive player record(s).",
                    purged);
        }
        return new PurgeResult(purged, stale.size() - purged);
    }

    static int purgeStale(PlayerActivity activity, List<UUID> stale, Predicate<UUID> erase, Runnable backup) {
        int purged = 0;
        boolean restored = false;
        for (UUID player : stale) {
            Long seen = activity.lastSeen(player);
            if (erase.test(player)) {
                purged++;
            } else if (seen != null) {
                // Keep the original inactivity timestamp so the next sweep retries a partial erasure.
                activity.restoreLastSeen(player, seen);
                restored = true;
            }
        }
        if (restored) backup.run();
        return purged;
    }
}
