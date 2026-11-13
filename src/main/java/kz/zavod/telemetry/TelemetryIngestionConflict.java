package kz.zavod.telemetry;

/** Reused signal/event identifier with a different immutable payload. */
public final class TelemetryIngestionConflict extends IllegalStateException {
    public TelemetryIngestionConflict(String message){super(message);}
}
