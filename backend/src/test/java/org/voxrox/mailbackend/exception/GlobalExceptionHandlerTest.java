package org.voxrox.mailbackend.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * No Spring context — the handler is a POJO and can be instantiated directly.
 * Verifies the ProblemDetail structure (status, type, instance, errorCode,
 * timestamp).
 */
class GlobalExceptionHandlerTest {

    private static final String REQUEST_URI = "/api/v1/test";
    private static final URI ERROR_TYPE_BASE = URI.create("https://mailbackend.local/errors/");

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        handler = new GlobalExceptionHandler(messageSource);
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(REQUEST_URI);
        when(request.getLocale()).thenReturn(Locale.forLanguageTag("cs"));
    }

    /**
     * Verifies the common properties of every ProblemDetail returned by the
     * handler.
     */
    private void assertCommonFields(ProblemDetail problem, HttpStatus expectedStatus, String expectedErrorCode) {
        assertThat(problem.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(problem.getInstance()).isEqualTo(URI.create(REQUEST_URI));
        assertThat(problem.getType()).isNotNull();
        assertThat(problem.getProperties()).containsKey("errorCode");
        assertThat(problem.getProperties().get("errorCode")).isEqualTo(expectedErrorCode);
        assertThat(problem.getProperties()).containsKey("timestamp");
        assertThat(problem.getProperties().get("timestamp")).isInstanceOf(Instant.class);
    }

    @Nested
    @DisplayName("handleAppException")
    class HandleAppExceptionTests {

        @Test
        @DisplayName("AccountNotFoundException -> 404, correct errorCode and detail")
        void accountNotFound_returns404WithCorrectFields() {
            // given -- a concrete AppException subclass
            var ex = new AccountNotFoundException(42L);

            ProblemDetail problem = handler.handleAppException(ex, request);

            assertCommonFields(problem, HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND.name());
            assertThat(problem.getDetail()).contains("42");
            assertThat(problem.getProperties().get("messageKey")).isEqualTo("error.account.notFound");
            assertThat(problem.getType()).isEqualTo(ERROR_TYPE_BASE.resolve("account_not_found"));
        }

        @Test
        @DisplayName("Accept-Language en -> localized detail in English")
        void acceptLanguageEnglish_returnsLocalizedDetail() {
            when(request.getLocale()).thenReturn(Locale.ENGLISH);
            var ex = new AccountNotFoundException(42L);

            ProblemDetail problem = handler.handleAppException(ex, request);

            assertCommonFields(problem, HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND.name());
            assertThat(problem.getDetail()).isEqualTo("E-mail account with ID 42 was not found.");
            assertThat(problem.getProperties().get("messageKey")).isEqualTo("error.account.notFound");
        }

        @Test
        @DisplayName("MailConnectionException -> 503, errorCode MAIL_CONNECTION_ERROR")
        void mailConnectionError_returns503() {
            var ex = new MailConnectionException("IMAP timeout");

            ProblemDetail problem = handler.handleAppException(ex, request);

            assertCommonFields(problem, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.MAIL_CONNECTION_ERROR.name());
            assertThat(problem.getDetail()).isEqualTo("Spojení s poštovním serverem selhalo: IMAP timeout");
            assertThat(problem.getProperties().get("messageKey")).isEqualTo("error.mail.connectionFailed");
            assertThat(problem.getType()).isEqualTo(ERROR_TYPE_BASE.resolve("mail_connection_error"));
        }
    }

    @Nested
    @DisplayName("handleValidation")
    class HandleValidationTests {

        @Test
        @DisplayName("MethodArgumentNotValidException -> 400, detail contains field errors")
        void validationErrors_returns400WithFieldDetails() {
            // given -- mock BindingResult with two field errors
            FieldError emailError = new FieldError("dto", "email", "must not be blank");
            FieldError nameError = new FieldError("dto", "name", "is too short");

            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(emailError, nameError));

            ProblemDetail problem = handler.handleValidation(ex, request);

            assertCommonFields(problem, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name());
            assertThat(problem.getDetail()).contains("email").contains("name").contains("must not be blank")
                    .contains("is too short");
            assertThat((List<?>) problem.getProperties().get("violations")).hasSize(2);
            assertThat(problem.getType()).isEqualTo(ERROR_TYPE_BASE.resolve("validation_error"));
        }

        @Test
        @DisplayName("Single field error -> detail contains one error")
        void singleFieldError_returns400() {
            FieldError error = new FieldError("dto", "password", "is required");
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(error));

            ProblemDetail problem = handler.handleValidation(ex, request);

            assertCommonFields(problem, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name());
            assertThat(problem.getDetail()).contains("password: is required");
            assertThat((List<?>) problem.getProperties().get("violations")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("handleConstraintViolation")
    class HandleConstraintViolationTests {

        @SuppressWarnings("unchecked")
        private ConstraintViolation<Object> mockViolation(String path, String message) {
            ConstraintViolation<Object> v = mock(ConstraintViolation.class);
            Path p = mock(Path.class);
            when(p.toString()).thenReturn(path);
            when(v.getPropertyPath()).thenReturn(p);
            when(v.getMessage()).thenReturn(message);
            return v;
        }

        @Test
        @DisplayName("ConstraintViolationException -> 400, detail contains path and message")
        void violation_returns400WithPathAndMessage() {
            var v1 = mockViolation("getAccountById.id", "must be greater than 0");
            var v2 = mockViolation("resolveProvider.email", "must not be blank");
            var ex = new ConstraintViolationException(Set.of(v1, v2));

            ProblemDetail problem = handler.handleConstraintViolation(ex, request);

            assertCommonFields(problem, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name());
            assertThat(problem.getDetail()).contains("getAccountById.id").contains("must be greater than 0")
                    .contains("resolveProvider.email").contains("must not be blank");
            assertThat((List<?>) problem.getProperties().get("violations")).hasSize(2);
            assertThat(problem.getType()).isEqualTo(ERROR_TYPE_BASE.resolve("validation_error"));
        }
    }

    @Nested
    @DisplayName("handleNotReadable")
    class HandleNotReadableTests {

        @Test
        @DisplayName("HttpMessageNotReadableException -> 400, errorCode BAD_REQUEST")
        void malformedJson_returns400() {
            var ex = new HttpMessageNotReadableException("JSON parse error", (Throwable) null, null);

            ProblemDetail problem = handler.handleNotReadable(ex, request);

            assertCommonFields(problem, HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.name());
            assertThat(problem.getDetail()).contains("Nečitelný formát");
            assertThat(problem.getType()).isEqualTo(ERROR_TYPE_BASE.resolve("bad_request"));
        }
    }

    @Nested
    @DisplayName("handleNoResource")
    class HandleNoResourceTests {

        @Test
        @DisplayName("NoResourceFoundException -> 404, errorCode RESOURCE_NOT_FOUND")
        void missingStaticResource_returns404() {
            // given - e.g. GET /favicon.ico with no static file
            var ex = new NoResourceFoundException(HttpMethod.GET, "/favicon.ico", "no static resource");

            ProblemDetail problem = handler.handleNoResource(ex, request);

            assertCommonFields(problem, HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.name());
            assertThat(problem.getDetail()).contains("nebyl nalezen");
            assertThat(problem.getType()).isEqualTo(ERROR_TYPE_BASE.resolve("not_found"));
        }
    }

    @Nested
    @DisplayName("handleGeneric")
    class HandleGenericTests {

        @Test
        @DisplayName("Unexpected exception -> 500, errorCode INTERNAL_ERROR")
        void unexpectedException_returns500() {
            var ex = new NullPointerException("something broke");

            ProblemDetail problem = handler.handleGeneric(ex, request);

            assertCommonFields(problem, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name());
            assertThat(problem.getDetail()).contains("vnitřní chybě serveru");
            assertThat(problem.getType()).isEqualTo(ERROR_TYPE_BASE.resolve("internal_error"));
        }

        @Test
        @DisplayName("RuntimeException -> 500, instance matches request URI")
        void runtimeException_setsInstanceUri() {
            var ex = new RuntimeException("unexpected");

            ProblemDetail problem = handler.handleGeneric(ex, request);

            assertThat(problem.getStatus()).isEqualTo(500);
            assertThat(problem.getInstance()).isEqualTo(URI.create(REQUEST_URI));
        }
    }
}
