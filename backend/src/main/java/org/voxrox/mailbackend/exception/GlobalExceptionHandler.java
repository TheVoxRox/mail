package org.voxrox.mailbackend.exception;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Global exception handler — returns RFC 9457 ProblemDetail (Spring Boot 4
 * standard). ProblemDetail is serialized natively by Spring; no custom
 * ErrorResponse is needed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI ERROR_TYPE_BASE = URI.create("https://mailbackend.local/errors/");

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(AppException.class)
    public ProblemDetail handleAppException(AppException ex, HttpServletRequest request) {
        log.warn("{} URI: {}, Code: {}, Message: {}", LogCategory.ERROR, request.getRequestURI(), ex.getCode(),
                ex.getMessage());

        String detail = resolveMessage(ex.getMessageKey(), ex.getMessageArgs(), ex.getMessage(), request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), detail);
        problem.setType(ERROR_TYPE_BASE.resolve(ex.getCode().name().toLowerCase(Locale.ROOT)));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ex.getCode().name());
        if (ex.getMessageKey() != null) {
            problem.setProperty("messageKey", ex.getMessageKey());
            problem.setProperty("messageArgs", ex.getMessageArgs());
        }
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ObjectError> errors = new ArrayList<>();
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        if (fieldErrors != null) {
            errors.addAll(fieldErrors);
        }
        List<ObjectError> globalErrors = ex.getBindingResult().getGlobalErrors();
        if (globalErrors != null) {
            errors.addAll(globalErrors);
        }
        List<ValidationViolation> violations = errors.stream().map(this::violationFromObjectError).toList();
        String details = validationDetails(violations);

        log.warn("{} Invalid input at {}: {}", LogCategory.ERROR, request.getRequestURI(), details);

        return validationProblem(request, "error.validation", new Object[]{details},
                "Input validation failed: " + details, violations);
    }

    /**
     * {@link HandlerMethodValidationException} (Spring 6.1+) — raised when a
     * {@code @Validated} controller fails on method-level constraints
     * ({@code @PathVariable @Positive}, {@code @RequestParam @NotBlank}...).
     * Different from {@link MethodArgumentNotValidException}, which only covers
     * {@code @Valid @RequestBody}.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerMethodValidation(HandlerMethodValidationException ex,
            HttpServletRequest request) {
        List<ValidationViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> violation(Objects.toString(r.getMethodParameter().getParameterName(), "parameter"),
                                safeDefaultMessage(err.getDefaultMessage()), messageKey(err.getDefaultMessage()),
                                firstCode(err.getCodes()))))
                .toList();
        String details = validationDetails(violations);

        log.warn("{} Invalid parameter at {}: {}", LogCategory.ERROR, request.getRequestURI(), details);

        return validationProblem(request, "error.validation", new Object[]{details},
                "Input validation failed: " + details, violations);
    }

    /**
     * Fallback for {@link ConstraintViolationException} — some older versions of
     * the validation API (and Spring configuration) still throw it instead of
     * {@link HandlerMethodValidationException}. Keep both handlers so the behavior
     * does not depend on the auto-configuration order.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ValidationViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> violation(v.getPropertyPath().toString(), v.getMessage(), messageKey(v.getMessageTemplate()),
                        constraintCode(v)))
                .toList();
        String details = validationDetails(violations);

        log.warn("{} Invalid parameter at {}: {}", LogCategory.ERROR, request.getRequestURI(), details);

        return validationProblem(request, "error.validation", new Object[]{details},
                "Input validation failed: " + details, violations);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.warn("{} Missing required parameter at {}: {}", LogCategory.ERROR, request.getRequestURI(),
                ex.getParameterName());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                resolveMessage("error.validation.missingParam", new Object[]{ex.getParameterName()},
                        "Missing required parameter: " + ex.getParameterName(), request));
        problem.setType(ERROR_TYPE_BASE.resolve("bad_request"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ErrorCode.VALIDATION_ERROR.name());
        problem.setProperty("messageKey", "error.validation.missingParam");
        problem.setProperty("messageArgs", new Object[]{ex.getParameterName()});
        problem.setProperty("violations", List.of(violation(ex.getParameterName(), problem.getDetail(),
                "error.validation.missingParam", "MissingServletRequestParameter")));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("{} Unreadable request format at {}: {}", LogCategory.ERROR, request.getRequestURI(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, resolveMessage(
                "error.badRequest.unreadableJson", new Object[0], "Unreadable request format (JSON).", request));
        problem.setType(ERROR_TYPE_BASE.resolve("bad_request"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ErrorCode.BAD_REQUEST.name());
        problem.setProperty("messageKey", "error.badRequest.unreadableJson");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Spring 6.1+ propagates a GET to a missing static resource as
     * {@link NoResourceFoundException}. Without a dedicated handler it would fall
     * into the generic catch-all and be logged as CRITICAL with a full stack trace
     * — which would flood the logs with routine "missing favicon / missing static
     * page" requests. Return a clean 404 + a short DEBUG log.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("{} Static resource not found: {}", LogCategory.ERROR, request.getRequestURI());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, resolveMessage(
                "error.resource.notFound", new Object[0], "The requested resource was not found.", request));
        problem.setType(ERROR_TYPE_BASE.resolve("not_found"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ErrorCode.RESOURCE_NOT_FOUND.name());
        problem.setProperty("messageKey", "error.resource.notFound");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * The client closed the SSE / async connection mid-write (Connection reset by
     * peer, tab closed, network drop). Tomcat propagates this through async
     * dispatch as {@link AsyncRequestNotUsableException} on a different thread, out
     * of reach of the emitter service's local try/catch. This is not a server error
     * — log at DEBUG and return no body (the response is already committed with
     * Content-Type text/event-stream; a ProblemDetail would not serialize anyway).
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("{} Async client disconnected at {}: {}", LogCategory.SYNC, request.getRequestURI(), ex.getMessage());
    }

    /**
     * SSE / async request timeout — planned behavior, not an error. The client
     * (typically {@code EventSource}) reconnects automatically. Without a dedicated
     * handler it would fall through to {@link #handleGeneric}, which produces a
     * ProblemDetail JSON; Spring cannot serialize that into an already-committed
     * response with {@code Content-Type: text/event-stream} and a
     * {@code HttpMessageNotWritableException} flies out. The Tomcat connection
     * would never return to the pool, the JPA EntityManager connection would hang,
     * the Hikari pool would be exhausted and the whole backend would stop serving
     * DB requests. Return an empty 503: no body is serialized and the connection is
     * released.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public void handleAsyncRequestTimeout(AsyncRequestTimeoutException ex, HttpServletRequest request) {
        log.debug("{} Async request timeout at {}: {}", LogCategory.SYNC, request.getRequestURI(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("{} Unexpected exception at {}: ", LogCategory.CRITICAL, request.getRequestURI(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                resolveMessage("error.internal", new Object[0], "An internal server error occurred.", request));
        problem.setType(ERROR_TYPE_BASE.resolve("internal_error"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ErrorCode.INTERNAL_ERROR.name());
        problem.setProperty("messageKey", "error.internal");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private String resolveMessage(String key, Object[] args, String fallback, HttpServletRequest request) {
        if (key == null) {
            return fallback;
        }
        try {
            return messageSource.getMessage(key, args, locale(request));
        } catch (NoSuchMessageException ex) {
            return fallback;
        }
    }

    private static Locale locale(HttpServletRequest request) {
        Locale locale = request.getLocale();
        return locale == null ? Locale.getDefault() : locale;
    }

    private ProblemDetail validationProblem(HttpServletRequest request, String messageKey, Object[] messageArgs,
            String fallback, List<ValidationViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                resolveMessage(messageKey, messageArgs, fallback, request));
        problem.setType(ERROR_TYPE_BASE.resolve("validation_error"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ErrorCode.VALIDATION_ERROR.name());
        problem.setProperty("messageKey", messageKey);
        problem.setProperty("messageArgs", messageArgs);
        problem.setProperty("violations", violations);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private ValidationViolation violationFromObjectError(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        return violation(field, safeDefaultMessage(error.getDefaultMessage()), messageKey(error.getDefaultMessage()),
                error.getCode());
    }

    private ValidationViolation violation(String field, String message, String messageKey, String code) {
        return new ValidationViolation(field, message, messageKey, code);
    }

    private static String validationDetails(List<ValidationViolation> violations) {
        return violations.stream().map(v -> v.field() + ": " + v.message()).collect(Collectors.joining(", "));
    }

    private static String safeDefaultMessage(String message) {
        return message == null ? "Invalid value" : message;
    }

    private static String messageKey(String messageTemplate) {
        if (messageTemplate == null || !messageTemplate.startsWith("{") || !messageTemplate.endsWith("}")) {
            return null;
        }
        return messageTemplate.substring(1, messageTemplate.length() - 1);
    }

    private static String firstCode(String[] codes) {
        return codes == null || codes.length == 0 ? null : codes[0];
    }

    private static String constraintCode(jakarta.validation.ConstraintViolation<?> violation) {
        if (violation.getConstraintDescriptor() == null
                || violation.getConstraintDescriptor().getAnnotation() == null) {
            return null;
        }
        return violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    }

    private record ValidationViolation(String field, String message, String messageKey, String code) {
        private ValidationViolation {
            field = field == null || field.isBlank() ? "value" : field;
        }
    }
}
