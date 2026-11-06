package za.co.neroland.nerolandcore.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolandcore.NerolandCoreCommon;

/**
 * Last-seen timestamps per player, so the retention sweep can purge data for players
 * who have not logged in for a configurable period. Stored on the overworld via the
 * usual {@link SavedDataType} codec.
 *
 * <p>Privacy (POPIA/GDPR): holds only a UUID and a login epoch-millis — no names,
 * IPs, chat or location. It exists precisely to <em>support</em> data minimisation
 * (drive erasure of stale records), and is itself cleared for a player on erasure.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PlayerActivity extends SavedData {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(NerolandCoreCommon.MOD_ID, "player_activity");

    public static final SavedDataType<PlayerActivity> TYPE =
            SavedDataCompat.type(ID, PlayerActivity::new, codec());

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final Map<UUID, Long> lastSeen = new LinkedHashMap<>();

    public PlayerActivity() {
    }

    /**
     * The one store, on the overworld so it is always loaded. Routed through
     * {@link SavedDataRecovery} so a corrupt {@code player_activity.dat} degrades to the
     * last-known-good backup (or a fresh store) instead of crashing on every login.
     */
    public static PlayerActivity get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, PlayerActivity::new, ID.toString());
    }

    /**
     * POPIA/GDPR erasure entry point registered with {@link PlayerDataErasure} by {@link CoreData}:
     * drop the player's activity record and push the change to the recovery backup in the same
     * request, so the erased row does not survive in the backup file.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        PlayerActivity activity = get(server);
        activity.forget(player);
        SavedDataRecovery.backupNow(server.overworld(), TYPE, activity, ID.toString());
    }

    /** Record that {@code player} was just seen. */
    public void touch(UUID player) {
        lastSeen.put(player, System.currentTimeMillis());
        setDirty();
    }

    /**
     * Whether an activity record exists for {@code player}. Used by subject-access checks and by
     * {@link ErasureConformance} probes; never logged.
     */
    public boolean hasRecord(UUID player) {
        return lastSeen.containsKey(player);
    }

    Long lastSeen(UUID player) {
        return lastSeen.get(player);
    }

    void restoreLastSeen(UUID player, long time) {
        lastSeen.put(player, time);
        setDirty();
    }

    /** UUIDs whose last login is older than {@code days} (empty if {@code days <= 0}). */
    public List<UUID> stalerThan(int days) {
        if (days <= 0) {
            return List.of();
        }
        long threshold = System.currentTimeMillis() - days * MILLIS_PER_DAY;
        List<UUID> stale = new ArrayList<>();
        lastSeen.forEach((uuid, seen) -> {
            if (seen < threshold) {
                stale.add(uuid);
            }
        });
        return stale;
    }

    /** Drop a player's activity record (called as part of erasure). */
    public void forget(UUID player) {
        if (lastSeen.remove(player) != null) {
            setDirty();
        }
    }

    private record Entry(String uuid, long lastSeen) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("uuid").forGetter(Entry::uuid),
                Codec.LONG.fieldOf("last_seen").forGetter(Entry::lastSeen)
        ).apply(inst, Entry::new));
    }

    private static Codec<PlayerActivity> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
                Entry.CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(PlayerActivity::entries)
        ).apply(inst, PlayerActivity::fromEntries));
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        lastSeen.forEach((uuid, seen) -> out.add(new Entry(uuid.toString(), seen)));
        return out;
    }

    private static PlayerActivity fromEntries(List<Entry> entries) {
        PlayerActivity activity = new PlayerActivity();
        for (Entry entry : entries) {
            try {
                activity.lastSeen.put(UUID.fromString(entry.uuid()), entry.lastSeen());
            } catch (IllegalArgumentException ignored) {
                // skip malformed rows
            }
        }
        return activity;
    }
}
