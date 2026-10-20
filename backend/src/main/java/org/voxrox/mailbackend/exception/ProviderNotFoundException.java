package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class ProviderNotFoundException extends AppException {

    private ProviderNotFoundException(String detail, String messageKey, Object... args) {
        super(ErrorCode.PROVIDER_NOT_FOUND, detail, HttpStatus.NOT_FOUND, messageKey, args);
    }

    public static ProviderNotFoundException byId(Long providerId) {
        return new ProviderNotFoundException("Provider with ID " + providerId + " was not found.",
                "error.provider.notFoundById", providerId);
    }

    public static ProviderNotFoundException byOAuth2Registration(String registrationId) {
        return new ProviderNotFoundException("Provider for OAuth2 registration '" + registrationId + "' was not found.",
                "error.provider.notFoundByOAuth2Registration", registrationId);
    }
}
