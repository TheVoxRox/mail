package org.voxrox.mailbackend.core.diagnostic;

import com.sun.management.HotSpotDiagnosticMXBean;

import module java.base;
import module java.management;

/**
 * Every thread of the sidecar and where it stands, for the bug a log cannot
 * show: a sync that never finishes, a view stuck on "loading", a lock that is
 * never released.
 *
 * <p>
 * The application runs on virtual threads
 * ({@code spring.threads.virtual.enabled}), which
 * {@link ThreadMXBean#dumpAllThreads} does not see, so the dump comes from
 * {@link HotSpotDiagnosticMXBean#dumpThreads}, the one that does. It can only
 * write to a file it creates itself, so the text goes through a file in a
 * directory of its own under the system temp folder, and both are deleted
 * again. If that fails, the platform threads alone are better than nothing, and
 * the text says which one it is.
 */
final class DiagnosticThreadDump {

    private DiagnosticThreadDump() {
    }

    static String capture() {
        try {
            return allThreads();
        } catch (IOException | RuntimeException e) {
            return "Virtual threads are missing from this dump: " + e.getClass().getSimpleName() + "\n\n"
                    + platformThreads();
        }
    }

    private static String allThreads() throws IOException {
        HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        Path dir = Files.createTempDirectory("voxrox-mail-threads");
        Path file = dir.toAbsolutePath().resolve("threads.txt");
        try {
            bean.dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            return Files.readString(file, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static String platformThreads() {
        StringBuilder text = new StringBuilder();
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            text.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId()).append(' ')
                    .append(info.getThreadState());
            if (info.getLockName() != null) {
                text.append(" on ").append(info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                text.append(" owned by \"").append(info.getLockOwnerName()).append('"');
            }
            text.append('\n');
            for (StackTraceElement frame : info.getStackTrace()) {
                text.append("    at ").append(frame).append('\n');
            }
            text.append('\n');
        }
        return text.toString();
    }
}
