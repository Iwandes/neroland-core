package za.co.neroland.nerolandcore.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.nerolandcore.link.LinkAlerts;
import za.co.neroland.nerolandcore.progression.MaterialMilestoneState;
import za.co.neroland.nerolandcore.progression.ProgressionState;
import za.co.neroland.nerolandcore.reputation.ReputationApi;
import za.co.neroland.nerolandcore.reputation.ReputationProvider;

/**
 * POPIA/GDPR conformance for the shared erasure contract, exercised through the reusable
 * {@link ErasureConformance} harness that downstream mods call from their own suites.
 *
 * <p>Plain JVM, no game bootstrap: the four {@code SavedData} stores are constructed directly
 * rather than through {@code get(server)}, because that lookup needs a live
 * {@link net.minecraft.server.MinecraftServer}'s data storage. The erasers registered here mirror
 * the production registrations in {@link CoreData} ({@code ProgressionState::eraseFor} and friends)
 * but bind to the local instances for the same reason; the {@code MinecraftServer} argument is
 * pass-through and unused by these erasers. The currency and reputation erasers are Core's real
 * ones — the harness guarantees they are registered.
 *
 * <p>Privacy note: every test uses a random UUID and never logs it, and one test asserts that the
 * report itself never carries the UUID.
 */
class ErasureConformanceTest {

    private static final Identifier GATE = Identifier.parse("nerolandcore:test_gate");
    private static final Identifier MILESTONE = Identifier.parse("nerolandcore:material_discovered");
    private static final Identifier IRON = Identifier.parse("minecraft:iron");
    private static final Identifier FACTION = Identifier.parse("nerolandcore:test_faction");
    private static final Currency CREDITS = Currency.of(
            Identifier.parse("nerolandcore:credits"), "currency.nerolandcore.credits");

    /** Erasers this test registered, removed again so one test cannot leak into the next. */
    private final List<PlayerDataEraser> registered = new ArrayList<>();

    private void register(PlayerDataEraser eraser) {
        registered.add(eraser);
        PlayerDataErasure.register(eraser);
    }

    @AfterEach
    void unregisterErasers() {
        registered.forEach(PlayerDataErasure::unregister);
        registered.clear();
    }

    // --- Core's own six erasers ---------------------------------------------------------------

    @Test
    void coreSixErasersPurgeEveryPlayerKeyedStore() {
        UUID player = UUID.randomUUID();

        ProgressionState gates = new ProgressionState();
        MaterialMilestoneState milestones = new MaterialMilestoneState();
        PlayerActivity activity = new PlayerActivity();
        LinkAlerts alerts = new LinkAlerts();

        gates.setPlayer(player, GATE, true);
        milestones.setPlayer(player, MILESTONE, IRON, true);
        activity.touch(player);
        alerts.raise(null, player,
                LinkAlert.raise("reactor_low", "core", LinkAlert.Severity.WARN, "Reactor output low"));
        CurrencyApi.deposit(player, CREDITS, 250L);
        ReputationApi.setReputation(player, FACTION, 40);

        // Mirrors CoreData.init()'s four store erasers; currency + reputation are Core's own and the
        // harness registers them itself.
        register((server, uuid) -> gates.forgetPlayer(uuid));
        register((server, uuid) -> milestones.forgetPlayer(uuid));
        register((server, uuid) -> activity.forget(uuid));
        register((server, uuid) -> alerts.forget(uuid));

        ErasureConformance.create()
                .probe("progression_gates", uuid -> !gates.playerGates(uuid).isEmpty())
                .probe("material_milestones", uuid -> !milestones.playerValues(uuid).isEmpty())
                .probe("player_activity", activity::hasRecord)
                .probe("link_alerts", uuid -> !alerts.list(uuid).isEmpty())
                .probe("currency", uuid -> CurrencyApi.getBalance(uuid, CREDITS) != 0L)
                .probe("reputation", uuid -> ReputationApi.getReputation(uuid, FACTION) != 0)
                .verify(null, player);
    }

    @Test
    void erasureTargetsOnlyTheRequestedPlayer() {
        UUID erased = UUID.randomUUID();
        UUID retained = UUID.randomUUID();

        ProgressionState gates = new ProgressionState();
        gates.setPlayer(erased, GATE, true);
        gates.setPlayer(retained, GATE, true);
        gates.setServer(GATE, true);

        register((server, uuid) -> gates.forgetPlayer(uuid));
        PlayerDataErasure.erase(null, erased);

        assertTrue(gates.playerGates(erased).isEmpty());
        assertFalse(gates.playerGates(retained).isEmpty(), "another player's rows must survive");
        assertTrue(gates.isServerOpen(GATE), "shared, non-personal server-scope gates must survive");
    }

    // --- The guarantees the harness asserts -----------------------------------------------------

    @Test
    void aFailingEraserDoesNotPreventTheOnesAfterIt() {
        UUID player = UUID.randomUUID();
        boolean[] ranAfterRuntimeException = {false};
        boolean[] ranAfterError = {false};

        register((server, uuid) -> {
            throw new IllegalStateException("deliberate test failure, safe to ignore");
        });
        register((server, uuid) -> {
            if (player.equals(uuid)) {
                ranAfterRuntimeException[0] = true;
            }
        });
        // PlayerDataErasure catches Throwable, not RuntimeException, precisely so a linkage Error
        // out of a version-mismatched mod cannot silently truncate the fan-out.
        register((server, uuid) -> {
            throw new NoSuchMethodError("deliberate test failure, safe to ignore");
        });
        register((server, uuid) -> {
            if (player.equals(uuid)) {
                ranAfterError[0] = true;
            }
        });

        assertDoesNotThrow(() -> PlayerDataErasure.erase(null, player));
        assertTrue(ranAfterRuntimeException[0], "a RuntimeException must not abort the fan-out");
        assertTrue(ranAfterError[0], "an Error must not abort the fan-out either");
        assertFalse(PlayerDataErasure.eraseAndReport(null, player), "failure must reach the caller");
    }

    @Test
    void successfulErasureIsReportedToTheCaller() {
        boolean[] ran = {false};
        register((server, uuid) -> ran[0] = true);
        assertTrue(PlayerDataErasure.eraseAndReport(null, UUID.randomUUID()));
        assertTrue(ran[0]);
    }

    @Test
    void retentionKeepsFailedPlayersEligibleForRetry() {
        var activity = new PlayerActivity();
        var successful = UUID.randomUUID();
        var failed = UUID.randomUUID();
        var later = UUID.randomUUID();
        activity.touch(successful);
        activity.touch(failed);
        activity.touch(later);
        long previous = activity.lastSeen(failed);
        int[] backups = {0};
        int purged = PlayerDataErasure.purgeStale(activity, List.of(successful, failed, later), player -> {
            activity.forget(player);
            return !player.equals(failed);
        }, () -> backups[0]++);
        assertEquals(2, purged);
        assertFalse(activity.hasRecord(successful));
        assertFalse(activity.hasRecord(later));
        assertEquals(previous, activity.lastSeen(failed));
        assertEquals(1, backups[0]);

        assertThrows(IllegalStateException.class, () -> PlayerDataErasure.purgeStale(activity, List.of(failed), player -> {
            activity.forget(player);
            return false;
        }, () -> { throw new IllegalStateException("backup failed"); }));
        assertEquals(previous, activity.lastSeen(failed));
    }

    @Test
    void aSubsystemThatRetainsDataIsNamedInTheReportAndTheReportCarriesNoUuid() {
        UUID player = UUID.randomUUID();
        Map<UUID, String> leaky = new HashMap<>();
        leaky.put(player, "row");

        // Registered, but forgets nothing — the exact silent-failure the harness exists to catch.
        register((server, uuid) -> { });

        ErasureConformance.Report report = ErasureConformance.create()
                .probe("my_mod:leaky_store", leaky::containsKey)
                .run(null, player);

        assertFalse(report.passed());
        assertEquals(List.of("my_mod:leaky_store"), report.retainedSubsystems());
        assertTrue(report.describe().contains("my_mod:leaky_store"),
                "a failure must name the subsystem that retained data");
        assertFalse(report.describe().contains(player.toString()),
                "the report must never carry the player UUID (it is personal data)");
    }

    @Test
    void verifyThrowsAnActionableAssertionErrorWhenDataSurvives() {
        UUID player = UUID.randomUUID();
        Map<UUID, String> leaky = new HashMap<>();
        leaky.put(player, "row");
        register((server, uuid) -> { });

        AssertionError error = assertThrows(AssertionError.class, () -> ErasureConformance.create()
                .probe("my_mod:leaky_store", leaky::containsKey)
                .verify(null, player));
        assertTrue(error.getMessage().contains("my_mod:leaky_store"));
    }

    @Test
    void aProbeThatNeverHeldDataFailsInsteadOfPassingVacuously() {
        UUID player = UUID.randomUUID();

        ErasureConformance.Report report = ErasureConformance.create()
                .probe("my_mod:never_populated", uuid -> false)
                .run(null, player);

        assertFalse(report.passed(), "a probe with nothing to erase proves nothing and must fail");
        assertTrue(String.join("\n", report.failures()).contains("BEFORE erasure"));
    }

    @Test
    void aRunWithNoProbesProvesNothingAndFails() {
        ErasureConformance.Report report = ErasureConformance.create().run(null, UUID.randomUUID());

        assertFalse(report.passed());
        assertTrue(String.join("\n", report.failures()).contains("no probes"));
    }

    @Test
    void aProbeThatThrowsIsReportedRatherThanSwallowed() {
        UUID player = UUID.randomUUID();

        ErasureConformance.Report report = ErasureConformance.create()
                .probe("my_mod:broken_probe", uuid -> {
                    throw new IllegalStateException("probe blew up");
                })
                .run(null, player);

        assertFalse(report.passed());
        assertTrue(String.join("\n", report.failures()).contains("my_mod:broken_probe"));
    }

    // --- The silent-failure case: a provider that inherits Core's default no-op -----------------

    @Test
    void aCurrencyProviderInheritingTheDefaultNoOpFailsLoudly() {
        CurrencyProvider original = CurrencyApi.provider();
        try {
            CurrencyApi.setProvider(new SilentCurrencyProvider());

            UUID player = UUID.randomUUID();
            Map<UUID, String> store = new HashMap<>();
            store.put(player, "row");
            register((server, uuid) -> store.remove(uuid));

            ErasureConformance.Report report = ErasureConformance.create()
                    .probe("my_mod:rows", store::containsKey)
                    .run(null, player);

            assertFalse(report.passed(),
                    "a provider that cannot be reached by erasure must never report conformance");
            assertTrue(String.join("\n", report.failures()).contains("CurrencyProvider"));
            assertTrue(String.join("\n", report.failures()).contains("forgetPlayer"));
            assertTrue(report.retainedSubsystems().isEmpty(), "the mod's own store did erase");
        } finally {
            CurrencyApi.setProvider(original);
        }
    }

    @Test
    void aReputationProviderInheritingTheDefaultNoOpFailsLoudly() {
        ReputationProvider original = ReputationApi.provider();
        try {
            ReputationApi.setProvider(new SilentReputationProvider());

            UUID player = UUID.randomUUID();
            Map<UUID, String> store = new HashMap<>();
            store.put(player, "row");
            register((server, uuid) -> store.remove(uuid));

            ErasureConformance.Report report = ErasureConformance.create()
                    .probe("my_mod:rows", store::containsKey)
                    .run(null, player);

            assertFalse(report.passed());
            assertTrue(String.join("\n", report.failures()).contains("ReputationProvider"));
        } finally {
            ReputationApi.setProvider(original);
        }
    }

    @Test
    void theHarnessRestoresTheBoundProvidersAfterARun() {
        CurrencyProvider currencyBefore = CurrencyApi.provider();
        ReputationProvider reputationBefore = ReputationApi.provider();

        UUID player = UUID.randomUUID();
        Map<UUID, String> store = new HashMap<>();
        store.put(player, "row");
        register((server, uuid) -> store.remove(uuid));

        ErasureConformance.create().probe("my_mod:rows", store::containsKey).verify(null, player);

        assertSame(currencyBefore, CurrencyApi.provider(), "the recording wrapper must be removed");
        assertSame(reputationBefore, ReputationApi.provider(), "the recording wrapper must be removed");
    }

    /** A provider that persists nothing but, crucially, never overrides {@code forgetPlayer}. */
    private static final class SilentCurrencyProvider implements CurrencyProvider {

        @Override
        public long getBalance(UUID player, Currency currency) {
            return 0L;
        }

        @Override
        public boolean deposit(UUID player, Currency currency, long amount) {
            return true;
        }

        @Override
        public boolean withdraw(UUID player, Currency currency, long amount) {
            return true;
        }
    }

    /** Same, for the reputation seam. */
    private static final class SilentReputationProvider implements ReputationProvider {

        @Override
        public int getReputation(UUID player, Identifier faction) {
            return 0;
        }

        @Override
        public void setReputation(UUID player, Identifier faction, int value) {
            // deliberately stores nothing
        }
    }
}
