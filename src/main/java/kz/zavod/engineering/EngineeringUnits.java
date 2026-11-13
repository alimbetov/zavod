package kz.zavod.engineering;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.Objects;

/** Pure deterministic dimensional conversion, without guessing density or process physics. */
public final class EngineeringUnits {
    private EngineeringUnits() {}
    public enum Dimension {
        VOLUME, VOLUMETRIC_FLOW, MASS, MASS_FLOW, PRESSURE, POWER, ENERGY,
        TEMPERATURE, ELECTRICAL_CONDUCTIVITY, ACIDITY
    }
    public record Unit(String code,Dimension dimension,BigDecimal scaleToBase,BigDecimal offsetToBase) {
        public Unit {
            Objects.requireNonNull(code); Objects.requireNonNull(dimension);
            Objects.requireNonNull(scaleToBase); Objects.requireNonNull(offsetToBase);
            if(code.isBlank()||scaleToBase.signum()<=0)throw new IllegalArgumentException("INVALID_UNIT");
        }
    }
    private static Unit unit(String key,Dimension dim,String scale,String offset) {
        return new Unit(key,dim,new BigDecimal(scale),new BigDecimal(offset));
    }
    // Base dimensions use m3, m3/h, kg, kg/h, Pa, W, Wh, K, mS/cm and pH.
    private static final Map<String,Unit> UNITS=Map.ofEntries(
        Map.entry("m3",unit("m3",Dimension.VOLUME,"1","0")),
        Map.entry("L",unit("L",Dimension.VOLUME,"0.001","0")),
        Map.entry("m3/h",unit("m3/h",Dimension.VOLUMETRIC_FLOW,"1","0")),
        Map.entry("L/min",unit("L/min",Dimension.VOLUMETRIC_FLOW,"0.06","0")),
        Map.entry("kg",unit("kg",Dimension.MASS,"1","0")),
        Map.entry("t",unit("t",Dimension.MASS,"1000","0")),
        Map.entry("kg/h",unit("kg/h",Dimension.MASS_FLOW,"1","0")),
        Map.entry("t/h",unit("t/h",Dimension.MASS_FLOW,"1000","0")),
        Map.entry("Pa",unit("Pa",Dimension.PRESSURE,"1","0")),
        Map.entry("bar",unit("bar",Dimension.PRESSURE,"100000","0")),
        Map.entry("W",unit("W",Dimension.POWER,"1","0")),
        Map.entry("kW",unit("kW",Dimension.POWER,"1000","0")),
        Map.entry("Wh",unit("Wh",Dimension.ENERGY,"1","0")),
        Map.entry("kWh",unit("kWh",Dimension.ENERGY,"1000","0")),
        Map.entry("K",unit("K",Dimension.TEMPERATURE,"1","0")),
        Map.entry("degC",unit("degC",Dimension.TEMPERATURE,"1","273.15")),
        Map.entry("°C",unit("°C",Dimension.TEMPERATURE,"1","273.15")),
        Map.entry("mS/cm",unit("mS/cm",Dimension.ELECTRICAL_CONDUCTIVITY,"1","0")),
        Map.entry("pH",unit("pH",Dimension.ACIDITY,"1","0"))
    );

    public static Map<String,Unit> definitions(){return UNITS;}
    public static Unit require(String code) {
        Unit unit=UNITS.get(code);
        if(unit==null)throw new IllegalArgumentException("UNKNOWN_ENGINEERING_UNIT:"+code);
        return unit;
    }
    public static Dimension dimension(String unitCode){return require(unitCode).dimension();}
    public static BigDecimal convert(BigDecimal value,String from,String to) {
        Objects.requireNonNull(value,"quantity");
        Unit source=require(from),target=require(to);
        if(source.dimension()!=target.dimension())
            throw new IllegalArgumentException("INCOMPATIBLE_UNIT_DIMENSION:"+from+"->"+to);
        BigDecimal base=value.multiply(source.scaleToBase()).add(source.offsetToBase());
        if(source.dimension()==Dimension.TEMPERATURE && base.signum()<0)
            throw new IllegalArgumentException("TEMPERATURE_BELOW_ABSOLUTE_ZERO");
        // Affine conversion matters for Celsius/Kelvin; never use one global multiplication.
        return base.subtract(target.offsetToBase()).divide(target.scaleToBase(),MathContext.DECIMAL128);
    }
}
