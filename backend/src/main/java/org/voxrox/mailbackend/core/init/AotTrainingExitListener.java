package org.voxrox.mailbackend.core.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Terminates the JVM after Spring startup finishes during a build-time AOT
 * training run. Activated via the env variable {@value #TRAINING_ENV_VAR} =
 * "1". Standard dev and test runs do not set the variable, so this class is a
 * no-op there.
 *
 * <p>
 * Usage: the {@code backend/scripts/generate-aot-cache-windows.ps1} script
 * launches the JVM with {@code -XX:AOTCacheOutput=<file>} (JEP 514, Java 25+)
 * and this env variable. The JVM starts the Spring context up to
 * {@link ApplicationReadyEvent}, this class gracefully closes it, and on exit
 * the JVM writes out the AOT cache. In subsequent production runs the cache is
 * wired in via {@code -XX:AOTCache=app/mail.aot} in jpackage java-options and
 * the JVM loads classes/metadata from it instead of from the jar.
 *
 * <p>
 * Order {@code LOWEST_PRECEDENCE} guarantees that all other
 * {@code ApplicationReadyEvent} listeners (including HandshakeService, which
 * writes session.json) run first — the training cache therefore matches a real
 * production startup up to the point where the user would send the first
 * request.
 */
@Component
public class AotTrainingExitListener {

    static final String TRAINING_ENV_VAR = "MAIL_AOT_TRAINING_RUN";
    private static final Logger log = LoggerFactory.getLogger(AotTrainingExitListener.class);

    private final ApplicationContext context;

    public AotTrainingExitListener(ApplicationContext context) {
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void exitIfTrainingRun() {
        if (!"1".equals(System.getenv(TRAINING_ENV_VAR))) {
            return;
        }
        log.info("{} {}=1 — graceful exit for AOT cache training run.", LogCategory.BOOT, TRAINING_ENV_VAR);
        int exitCode = SpringApplication.exit(context, () -> 0);
        /*
         * SpringApplication.exit returns a status code but does not kill the process
         * itself — it only closes the Spring context. System.exit delivers the code to
         * the shell and triggers shutdown hooks (so the JVM writes the AOT cache when
         * -XX:AOTCacheOutput is set).
         */
        System.exit(exitCode);
    }
}
