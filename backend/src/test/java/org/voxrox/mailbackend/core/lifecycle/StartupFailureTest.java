package org.voxrox.mailbackend.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure.Reason;

class StartupFailureTest {

    @Test
    @DisplayName("the exit codes are the sysexits.h values the desktop client maps")
    void reasonsCarryTheClientContract() {
        // frontend/src/lib/backend/sidecar.ts names these three codes; changing one
        // here without changing it there turns a named failure into "failed to start".
        assertThat(Reason.DATABASE_DAMAGED.exitCode()).isEqualTo(65);
        assertThat(Reason.SCHEMA_MISMATCH.exitCode()).isEqualTo(70);
        assertThat(Reason.STORAGE_UNAVAILABLE.exitCode()).isEqualTo(74);
    }

    @Test
    @DisplayName("exitCodeOf finds the failure under the exceptions Spring wraps it in")
    void exitCodeOfWalksTheCauseChain() {
        StartupFailure failure = new StartupFailure(Reason.DATABASE_DAMAGED, "damaged", null);
        RuntimeException wrapped = new IllegalStateException("context refresh failed",
                new RuntimeException("bean creation failed", failure));

        assertThat(StartupFailure.exitCodeOf(wrapped)).isEqualTo(65);
    }

    @Test
    @DisplayName("any other failed start exits 1")
    void exitCodeOfDefaultsToOne() {
        assertThat(StartupFailure.exitCodeOf(new IllegalStateException("port taken"))).isEqualTo(1);
    }
}
