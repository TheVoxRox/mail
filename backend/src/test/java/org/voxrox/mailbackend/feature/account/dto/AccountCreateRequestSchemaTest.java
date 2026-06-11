package org.voxrox.mailbackend.feature.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract guard for the generated OpenAPI/Swagger schema of
 * {@code POST /api/v1/accounts}.
 * <p>
 * Springdoc-openapi derives the {@code required} flag of a field from Bean
 * Validation annotations: {@code @NotNull}/{@code @NotBlank} on a record
 * component -> the field is required in the OpenAPI specification. After the
 * server config denormalization (custom provider plan), {@code providerId},
 * {@code imap}, and {@code smtp} must remain optional, otherwise Swagger UI
 * steers clients toward a non-existing contract.
 * <p>
 * This test verifies the annotations directly via reflection — no Spring
 * context boot, no docs endpoint calls. If someone accidentally adds
 * {@code @NotNull} to {@code providerId} in the future, the test catches the
 * contract change before the client does.
 */
@DisplayName("AccountCreateRequest - OpenAPI required-fields contract")
class AccountCreateRequestSchemaTest {

    @Test
    @DisplayName("providerId / imap / smtp have no @NotNull -> Swagger renders them as optional")
    void customProviderFieldsAreOptional() {
        assertThat(hasRequiredAnnotation("providerId"))
                .as("providerId must not be required — custom provider flow must pass with null").isFalse();
        assertThat(hasRequiredAnnotation("imap")).as("imap must not be required — provider flow does not send it")
                .isFalse();
        assertThat(hasRequiredAnnotation("smtp")).as("smtp must not be required — provider flow does not send it")
                .isFalse();
    }

    @Test
    @DisplayName("Account identity fields stay required")
    void identityFieldsRemainRequired() {
        assertThat(hasRequiredAnnotation("accountName")).isTrue();
        assertThat(hasRequiredAnnotation("email")).isTrue();
        assertThat(hasRequiredAnnotation("username")).isTrue();
        assertThat(hasRequiredAnnotation("password")).isTrue();
    }

    @Test
    @DisplayName("MailServerSettings - host/port/useSsl are required (validates the whole custom config)")
    void mailServerSettingsFieldsAreRequired() {
        assertThat(hasRequiredAnnotation(MailServerSettings.class, "host")).isTrue();
        assertThat(hasRequiredAnnotation(MailServerSettings.class, "port")).isTrue();
        assertThat(hasRequiredAnnotation(MailServerSettings.class, "useSsl")).isTrue();
    }

    private static boolean hasRequiredAnnotation(String componentName) {
        return hasRequiredAnnotation(AccountCreateRequest.class, componentName);
    }

    private static boolean hasRequiredAnnotation(Class<?> recordClass, String componentName) {
        RecordComponent[] components = recordClass.getRecordComponents();
        int componentIndex = -1;
        for (int i = 0; i < components.length; i++) {
            if (components[i].getName().equals(componentName)) {
                componentIndex = i;
                break;
            }
        }
        if (componentIndex < 0) {
            throw new AssertionError("Record component not found: " + componentName);
        }

        // Annotations declared on a record component map at the JVM level to the
        // ElementType targets of the accessor method / field / canonical
        // constructor parameter. NotNull/NotBlank do not have RECORD_COMPONENT in
        // @Target, so rc.getAnnotations() is empty — we walk accessor + field +
        // constructor directly.
        try {
            if (containsRequired(recordClass.getDeclaredMethod(componentName).getAnnotations())) {
                return true;
            }
            if (containsRequired(recordClass.getDeclaredField(componentName).getAnnotations())) {
                return true;
            }
            Class<?>[] parameterTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
            if (containsRequired(recordClass.getDeclaredConstructor(parameterTypes).getParameters()[componentIndex]
                    .getAnnotations())) {
                return true;
            }
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            throw new LinkageError("Reflection failed for " + componentName, e);
        }
        return false;
    }

    private static boolean containsRequired(Annotation[] annotations) {
        for (Annotation a : annotations) {
            Class<? extends Annotation> type = a.annotationType();
            if (type.equals(NotNull.class) || type.equals(NotBlank.class)) {
                return true;
            }
        }
        return false;
    }
}
