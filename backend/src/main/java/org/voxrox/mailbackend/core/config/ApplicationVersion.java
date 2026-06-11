package org.voxrox.mailbackend.core.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.MailBackendApplication;

@Component
public class ApplicationVersion {

    private static final String DEVELOPMENT_VERSION = "dev";

    private final String value;

    @Autowired
    public ApplicationVersion(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this(resolveVersion(buildPropertiesProvider.getIfAvailable()));
    }

    public ApplicationVersion(String value) {
        this.value = normalize(value);
    }

    public String value() {
        return value;
    }

    private static String resolveVersion(BuildProperties buildProperties) {
        if (buildProperties != null && hasText(buildProperties.getVersion())) {
            return buildProperties.getVersion();
        }

        Package appPackage = MailBackendApplication.class.getPackage();
        String implementationVersion = appPackage != null ? appPackage.getImplementationVersion() : null;
        if (hasText(implementationVersion)) {
            return implementationVersion;
        }

        return DEVELOPMENT_VERSION;
    }

    private static String normalize(String value) {
        return hasText(value) ? value : DEVELOPMENT_VERSION;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
