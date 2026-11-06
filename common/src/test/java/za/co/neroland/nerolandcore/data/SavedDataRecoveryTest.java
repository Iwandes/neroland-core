package za.co.neroland.nerolandcore.data;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Argument guards for the shared saved-data recovery helper.
 *
 * <p>The interesting behaviour — recovering a corrupt {@code .dat} from the last-known-good backup
 * — needs a live {@link net.minecraft.server.level.ServerLevel} and its data storage, so it is
 * covered by runtime verification rather than here. What <em>is</em> worth pinning in a plain-JVM
 * test is that a malformed call fails fast at the call site instead of NPE-ing somewhere inside the
 * recovery ladder mid-tick, which is the failure mode the helper exists to prevent.
 */
class SavedDataRecoveryTest {

    @Test
    void aMissingLevelIsRejectedAtTheCallSite() {
        assertThrows(IllegalArgumentException.class, () -> SavedDataRecovery.get(
                null, PlayerActivity.TYPE, PlayerActivity::new, "nerolandcore:player_activity"));
    }

    @Test
    void aMissingBackupTargetIsRejectedAtTheCallSite() {
        assertThrows(IllegalArgumentException.class, () -> SavedDataRecovery.backupNow(
                null, PlayerActivity.TYPE, new PlayerActivity(), "nerolandcore:player_activity"));
    }

    @Test
    void theRegistryAwareBackupOverloadRejectsAMissingLevel() {
        assertThrows(IllegalArgumentException.class, () -> SavedDataRecovery.backupNow(
                null, PlayerActivity.TYPE, new PlayerActivity(), "nerolandcore:player_activity", null));
    }

    @Test
    void forcedBackupFailuresReachTheCallerWhilePeriodicFailuresRemainNonfatal() {
        Exception cause = new java.io.IOException("simulated backup failure");
        var failure = assertThrows(IllegalStateException.class,
                () -> SavedDataRecovery.reportBackupFailure("test", true, cause));
        assertSame(cause, failure.getCause());
        assertDoesNotThrow(() -> SavedDataRecovery.reportBackupFailure("test", false, cause));
    }
}
