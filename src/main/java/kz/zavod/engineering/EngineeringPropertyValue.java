package kz.zavod.engineering;

import java.util.Objects;

/** Versioned equipment-instance design datum; never an observed or commanded PLC value. */
public record EngineeringPropertyValue(
        String key,EngineeringQuantity quantity,Source source,String evidenceRef) {
    public enum Source {
        MANUFACTURER, ENGINEER_VERIFIED, PROJECT_ASSUMPTION, MEASURED, IMPORT_SUGGESTION
    }
    public EngineeringPropertyValue {
        Objects.requireNonNull(key);Objects.requireNonNull(quantity);Objects.requireNonNull(source);
        if(key.isBlank())throw new IllegalArgumentException("INVALID_PROPERTY_KEY");
        if(evidenceRef!=null&&evidenceRef.isBlank())throw new IllegalArgumentException("INVALID_EVIDENCE_REF");
    }
}
