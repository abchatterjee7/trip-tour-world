package org.aadi.triptourworld.web;

import org.aadi.triptourworld.service.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Uniform JSON errors; never leaks stack traces or internals. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = Logger.getLogger(ApiExceptionHandler.class.getName());

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(body(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(f -> f.getDefaultMessage() == null ? "invalid value for " + f.getField() : f.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(body(msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(body("Malformed JSON request body."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        if (e instanceof ErrorResponse er) { // 404 / 405 / 415 ... keep Spring's own status
            return ResponseEntity.status(er.getStatusCode()).body(body(e.getMessage()));
        }
        LOG.log(Level.SEVERE, "Unhandled error", e);
        return ResponseEntity.status(500).body(body("Internal server error."));
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", message);
        return m;
    }
}
