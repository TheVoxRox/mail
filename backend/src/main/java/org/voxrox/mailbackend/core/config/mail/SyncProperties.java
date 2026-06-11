package org.voxrox.mailbackend.core.config.mail;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record SyncProperties(@Min(1) @DefaultValue("100") int windowSize, @Min(1) @DefaultValue("200") int batchSize,
        @NotNull @DefaultValue("5m") Duration interval, @NotNull @DefaultValue("10s") Duration initialDelay,
        @Min(1) @DefaultValue("50") int defaultPageSize, @Min(1) @DefaultValue("30") int backfillBatchSize,
        @Min(1) @DefaultValue("10000") int localWindowLimit, @Min(1) @DefaultValue("4") int maxConcurrentAccounts,
        @Min(1) @DefaultValue("256") int searchQueryMaxLength, @Min(1) @DefaultValue("200") int apiMaxPageSize,
        @NotNull @DefaultValue("30m") Duration sseTimeout,
        @NotNull @DefaultValue("30s") Duration sseHeartbeatInterval) {
}
