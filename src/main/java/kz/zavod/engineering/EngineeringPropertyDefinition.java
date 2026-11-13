package kz.zavod.engineering;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable template-level engineering property constraints; units are not PLC setpoints. */
public record EngineeringPropertyDefinition(
        String key,EngineeringUnits.Dimension dimension,String canonicalUnit,
        BigDecimal minInclusive,BigDecimal maxInclusive,boolean required,
        boolean evidenceRequiredForApproval) {
    public EngineeringPropertyDefinition {
        Objects.requireNonNull(key);Objects.requireNonNull(dimension);Objects.requireNonNull(canonicalUnit);
        if(key.isBlank()||EngineeringUnits.dimension(canonicalUnit)!=dimension)
            throw new IllegalArgumentException("INVALID_PROPERTY_DIMENSION:"+key);
        if(minInclusive!=null&&maxInclusive!=null&&minInclusive.compareTo(maxInclusive)>0)
            throw new IllegalArgumentException("INVALID_PROPERTY_RANGE:"+key);
    }
}
