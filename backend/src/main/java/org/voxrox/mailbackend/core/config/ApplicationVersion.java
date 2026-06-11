package org.voxrox.mailbackend.core.config;

import org.jspecify.annotations.Nullable;
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

    public ApplicationVersion(@Nullable String value) {
        this.value = normalize(value);
    }

    public String value() {
        return value;
    }

    /*
     * Null checks are spelled out inline (no hasText helper) so NullAway can follow
     * the dataflow — it does not look through helper methods.
     */
    private static String resolveVersion(@Nullable BuildProperties buildProperties) {
        if (buildProperties != null) {
            String buildVersion = buildProperties.getVersion();
            if (buildVersion != null && !buildVersion.isBlank()) {
                return buildVersion;
            }
        }

        Package appPackage = MailBackendApplication.class.getPackage();
        String implementationVersion = appPackage != null ? appPackage.getImplementationVersion() : null;
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            return implementationVersion;
        }

        return DEVELOPMENT_VERSION;
    }

    private static String normalize(@Nullable String value) {
        return value != null && !value.isBlank() ? value : DEVELOPMENT_VERSION;
    }
}
