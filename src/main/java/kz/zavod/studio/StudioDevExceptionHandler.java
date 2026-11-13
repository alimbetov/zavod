package kz.zavod.studio;

import kz.zavod.blueprint.persistence.BlueprintRevisionConflict;
import kz.zavod.telemetry.TelemetryMonitorDevController;
import kz.zavod.telemetry.TelemetryIngestionConflict;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Stable, non-SQL error codes for the local studio API. */
@RestControllerAdvice(assignableTypes={StudioDevController.class,EngineeringProjectDevController.class,TelemetryMonitorDevController.class})
@Profile("dev-local")
public class StudioDevExceptionHandler {
    public record ApiError(String code,String message) {}
    @ExceptionHandler({StudioConflictException.class,BlueprintRevisionConflict.class,TelemetryIngestionConflict.class})
    ResponseEntity<ApiError> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("REVISION_CONFLICT",ex.getMessage()));
    }
    @ExceptionHandler(StudioNotFoundException.class)
    ResponseEntity<ApiError> missing(StudioNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage(),"Resource unavailable"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalid(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("INVALID_ENGINEERING_INPUT",ex.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_JSON","Malformed or unsupported JSON payload"));
    }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ApiError> unauthorized(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("FORBIDDEN","Access denied"));
    }
}
