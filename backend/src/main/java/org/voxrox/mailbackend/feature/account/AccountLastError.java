package org.voxrox.mailbackend.feature.account;

import java.util.Map;

public record AccountLastError(AccountLastErrorCode code, Map<String, String> args, String fallbackMessage) {

    public AccountLastError {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public static AccountLastError of(AccountLastErrorCode code, String fallbackMessage) {
        return new AccountLastError(code, Map.of(), fallbackMessage);
    }

    public static AccountLastError of(AccountLastErrorCode code, Map<String, String> args, String fallbackMessage) {
        return new AccountLastError(code, args, fallbackMessage);
    }
}
