package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UidEnumerationSchedule} — the throttle that decides how
 * often a QRESYNC folder still pays for the full UID enumeration. Same
 * injectable-clock shape as {@link FolderListCacheTest}.
 */
class UidEnumerationScheduleTest {

    private static final Duration INTERVAL = Duration.ofHours(1);
    private static final Instant T0 = Instant.parse("2026-09-07T10:00:00Z");
    private static final Long ACCOUNT_ID = 42L;
    private static final String FOLDER = "INBOX";

    @Test
    @DisplayName("A folder that has never been enumerated in this process is due")
    void unknownFolderIsDue() {
        UidEnumerationSchedule schedule = new UidEnumerationSchedule(Clock.fixed(T0, ZoneOffset.UTC), INTERVAL);

        assertThat(schedule.isDue(ACCOUNT_ID, FOLDER)).isTrue();
    }

    @Test
    @DisplayName("Within the interval the folder is not due again")
    void recentlyRunFolderIsNotDue() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        UidEnumerationSchedule schedule = new UidEnumerationSchedule(movingClock(now), INTERVAL);

        schedule.markRan(ACCOUNT_ID, FOLDER);
        now.set(T0.plus(Duration.ofMinutes(55)));

        assertThat(schedule.isDue(ACCOUNT_ID, FOLDER)).isFalse();
    }

    /**
     * The boundary is its own test because the comparison is {@code >= interval}:
     * the five-minute sync cycle can land exactly on it, and an off-by-one operator
     * would push the scan a whole cycle later every time.
     */
    @Test
    @DisplayName("Exactly at the interval the folder is due again")
    void folderAtExactIntervalIsDue() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        UidEnumerationSchedule schedule = new UidEnumerationSchedule(movingClock(now), INTERVAL);

        schedule.markRan(ACCOUNT_ID, FOLDER);
        now.set(T0.plus(INTERVAL));

        assertThat(schedule.isDue(ACCOUNT_ID, FOLDER)).isTrue();
    }

    @Test
    @DisplayName("Two folders of one account are throttled independently")
    void foldersOfOneAccountDoNotShareTheirSchedule() {
        UidEnumerationSchedule schedule = new UidEnumerationSchedule(Clock.fixed(T0, ZoneOffset.UTC), INTERVAL);

        schedule.markRan(ACCOUNT_ID, FOLDER);

        assertThat(schedule.isDue(ACCOUNT_ID, FOLDER)).isFalse();
        assertThat(schedule.isDue(ACCOUNT_ID, "Sent")).isTrue();
    }

    @Test
    @DisplayName("The same folder name on two accounts is throttled independently")
    void accountsDoNotShareTheirSchedule() {
        UidEnumerationSchedule schedule = new UidEnumerationSchedule(Clock.fixed(T0, ZoneOffset.UTC), INTERVAL);

        schedule.markRan(ACCOUNT_ID, FOLDER);

        assertThat(schedule.isDue(ACCOUNT_ID, FOLDER)).isFalse();
        assertThat(schedule.isDue(7L, FOLDER)).isTrue();
    }

    private static Clock movingClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public Instant instant() {
                return now.get();
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
    }
}
