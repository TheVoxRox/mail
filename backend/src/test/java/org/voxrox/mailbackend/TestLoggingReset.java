package org.voxrox.mailbackend;

import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Hands the logging system to every application a test starts, as Spring Boot
 * did up to 4.1.1.
 * <p>
 * An integration test names its own log file ({@code logging.file.name} under
 * its own data directory), and some read it back. Logback is configured by
 * whichever application finds it unconfigured, and until 4.1.1 the test
 * framework handed it back between test classes: switching to the next class's
 * context paused the previous one, and the pause stopped that context's logging
 * lifecycle, which cleaned the logging system up. Boot 4.1.2 made the lifecycle
 * unpauseable (spring-boot#52088, so that a resumed context keeps its JDK
 * logging bridge), which leaves logging configured for the paused context's
 * file; the next test's application finds it configured and leaves it, and that
 * test's log is never written. Cleaning up before
 * {@code LoggingApplicationListener} handles the starting event gives each new
 * application the configuration its own properties name. A resumed context is
 * not started again and logs as the last started one left it.
 * <p>
 * Registered in the test resources' {@code META-INF/spring.factories}, so it is
 * on no classpath but the tests'.
 */
public class TestLoggingReset implements ApplicationListener<ApplicationStartingEvent>, Ordered {

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        LoggingSystem.get(event.getSpringApplication().getClassLoader()).cleanUp();
    }

    @Override
    public int getOrder() {
        // Ahead of LoggingApplicationListener (HIGHEST_PRECEDENCE + 20), which
        // prepares the logging system on this same event.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
