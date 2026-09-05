package com.statistiloto.server.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.stream.Collectors;

/**
 * Consistent structured error responses across all controllers.
 *
 * <p>Every exception produces an {@link ErrorResponse} JSON body with:
 * <ul>
 *   <li>{@code error} — machine-readable code</li>
 *   <li>{@code message} — human-readable description</li>
 *   <li>{@code status} — HTTP status code</li>
 *   <li>{@code timestamp} — ISO-8601 instant</li>
 *   <li>{@code path} — request path</li>
 * </ul>
 *
 * <p>Logging:
 * <ul>
 *   <li>4xx client errors → WARN with context</li>
 *   <li>5xx server errors → ERROR with stack trace</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("Bad request on {} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(
            new ErrorResponse("BAD_REQUEST", e.getMessage(), 400, req.getRequestURI()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(SecurityException e, HttpServletRequest req) {
        log.warn("Forbidden access on {} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            new ErrorResponse("FORBIDDEN", e.getMessage(), 403, req.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e, HttpServletRequest req) {
        log.warn("Access denied on {} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            new ErrorResponse("ACCESS_DENIED", "You do not have permission to perform this action", 403, req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String details = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("Validation failed on {} {}: {}", req.getMethod(), req.getRequestURI(), details);
        return ResponseEntity.badRequest().body(
            new ErrorResponse("VALIDATION_FAILED", details, 400, req.getRequestURI()));
    }

    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<ErrorResponse> handleGrpcError(StatusRuntimeException e, HttpServletRequest req) {
        HttpStatus status = switch (e.getStatus().getCode()) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.error("gRPC error on {} {}: status={} description={}",
            req.getMethod(), req.getRequestURI(),
            e.getStatus().getCode(), e.getStatus().getDescription(), e);
        return ResponseEntity.status(status).body(
            new ErrorResponse(
                "GRPC_" + e.getStatus().getCode().name(),
                "Lottery service error: " + e.getStatus().getDescription(),
                status.value(),
                req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred: " + e.getMessage(), 500, req.getRequestURI()));
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamClientError(HttpClientErrorException e, HttpServletRequest req) {
        // Propagate the upstream (agent service) 4xx status code and body.
        String detail = extractUpstreamDetail(e.getResponseBodyAsString());
        log.warn("Upstream client error on {} {}: status={} body={}",
            req.getMethod(), req.getRequestURI(), e.getStatusCode(), e.getResponseBodyAsString());
        return ResponseEntity.status(e.getStatusCode()).body(
            new ErrorResponse("UPSTREAM_ERROR", detail, e.getStatusCode().value(), req.getRequestURI()));
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamServerError(HttpServerErrorException e, HttpServletRequest req) {
        // Propagate the upstream (agent service) 5xx status code and body.
        String detail = extractUpstreamDetail(e.getResponseBodyAsString());
        log.error("Upstream server error on {} {}: status={} body={}",
            req.getMethod(), req.getRequestURI(), e.getStatusCode(), e.getResponseBodyAsString());
        return ResponseEntity.status(e.getStatusCode()).body(
            new ErrorResponse("UPSTREAM_ERROR", detail, e.getStatusCode().value(), req.getRequestURI()));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamResourceAccess(ResourceAccessException e, HttpServletRequest req) {
        // Connection refused, read timeout, etc. when calling the agent service.
        String cause = e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName();
        log.error("Upstream connection error on {} {}: {} — {}",
            req.getMethod(), req.getRequestURI(), cause, e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(
            new ErrorResponse("UPSTREAM_TIMEOUT",
                "Agent service unreachable or timed out: " + e.getMessage(),
                504, req.getRequestURI()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClientError(RestClientException e, HttpServletRequest req) {
        // Covers all RestClient failures not handled above (deserialization errors,
        // socket timeouts wrapped during response extraction, etc.).
        String cause = e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName();
        log.error("RestClient error on {} {}: {} — {}",
            req.getMethod(), req.getRequestURI(), cause, e.getMessage(), e);
        // SocketTimeoutException → 504 Gateway Timeout; everything else → 502 Bad Gateway
        boolean isTimeout = e.getCause() instanceof java.net.SocketTimeoutException
            || e.getMessage() != null && e.getMessage().contains("timed out");
        if (isTimeout) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(
                new ErrorResponse("UPSTREAM_TIMEOUT",
                    "Agent service timed out: " + e.getMessage(),
                    504, req.getRequestURI()));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            new ErrorResponse("UPSTREAM_ERROR",
                "Agent service error: " + e.getMessage(),
                502, req.getRequestURI()));
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Extract a human-readable detail message from an upstream (FastAPI) error body.
     * FastAPI's HTTPException returns {"detail": "..."}. If parsing fails, return the raw body.
     */
    private String extractUpstreamDetail(String body) {
        if (body == null || body.isBlank()) return "Upstream service error";
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode detail = node.get("detail");
            if (detail != null && !detail.isNull()) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // Not JSON — return raw body
        }
        return body;
    }
}
