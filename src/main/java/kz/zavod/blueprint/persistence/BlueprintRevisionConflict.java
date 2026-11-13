package kz.zavod.blueprint.persistence;

/** Controlled concurrency failure, not an opaque HTTP 500 or leaked SQL state. */
public final class BlueprintRevisionConflict extends IllegalStateException {
    public BlueprintRevisionConflict(String message) {super("REVISION_CONFLICT: "+message);}
    public BlueprintRevisionConflict(String message,Throwable cause) {super("REVISION_CONFLICT: "+message,cause);}
}
