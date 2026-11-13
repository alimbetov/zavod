package kz.zavod.engineering;

import java.math.BigDecimal;
import java.util.Objects;

/** Numeric value and an explicit supported engineering unit (not a sensor sample). */
public record EngineeringQuantity(BigDecimal value,String unitCode) {
    public EngineeringQuantity {
        Objects.requireNonNull(value,"value");
        var unit=EngineeringUnits.require(unitCode);
        if(unit.dimension()==EngineeringUnits.Dimension.TEMPERATURE)
            EngineeringUnits.convert(value,unitCode,"K"); // rejects impossible absolute temperatures
    }
    public EngineeringUnits.Dimension dimension(){return EngineeringUnits.dimension(unitCode);}
    public EngineeringQuantity in(String targetUnit) {
        return new EngineeringQuantity(EngineeringUnits.convert(value,unitCode,targetUnit),targetUnit);
    }
}
