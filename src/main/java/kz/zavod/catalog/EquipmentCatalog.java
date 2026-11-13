package kz.zavod.catalog;

import kz.zavod.engineering.EngineeringPropertyDefinition;
import kz.zavod.engineering.EngineeringUnits;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static kz.zavod.catalog.EquipmentType.Capability.*;
import static kz.zavod.catalog.EquipmentType.Direction.*;
import static kz.zavod.catalog.EquipmentType.Mode.*;

/** Fixed v0 catalogue: 15 equipment definitions and two complete pilot profiles. */
public final class EquipmentCatalog {
    private final Map<String, EquipmentType> types;
    private final Map<TemplateId, EquipmentType> versions;
    public record TemplateId(String key,int version) {}
    private final Map<String, IndustryProfile> profiles;

    public EquipmentCatalog(List<EquipmentType> equipment, List<IndustryProfile> lineProfiles) {
        var nextTypes = new HashMap<String, EquipmentType>();
        var byVersion = new HashMap<TemplateId,EquipmentType>();
        for (var type : equipment) {
            if (byVersion.putIfAbsent(new TemplateId(type.key(),type.version()),type) != null) {
                throw new IllegalArgumentException("Duplicate equipment type/version: " + type.key()+"/"+type.version());
            }
            nextTypes.merge(type.key(), type, (a,b)->a.version()>=b.version()?a:b);
        }
        var nextProfiles = new HashMap<String, IndustryProfile>();
        for (var profile : lineProfiles) {
            if (nextProfiles.putIfAbsent(profile.key(), profile) != null) {
                throw new IllegalArgumentException("Duplicate profile: " + profile.key());
            }
            for (var key : profile.equipmentKeys()) {
                if (!nextTypes.containsKey(key)) {
                    throw new IllegalArgumentException("Profile " + profile.key() + " refers to unknown equipment " + key);
                }
            }
        }
        types = Map.copyOf(nextTypes);
        versions = Map.copyOf(byVersion);
        profiles = Map.copyOf(nextProfiles);
    }

    public Map<String, EquipmentType> types() { return types; }
    public Map<TemplateId,EquipmentType> versions() { return versions; }
    public EquipmentType type(String key,int version) {
        EquipmentType type=versions.get(new TemplateId(key,version));
        if(type==null)throw new IllegalArgumentException("Unknown equipment type/version: "+key+"/"+version);
        return type;
    }
    public Map<String, IndustryProfile> profiles() { return profiles; }

    public EquipmentType type(String key) {
        var result = types.get(key);
        if (result == null) throw new IllegalArgumentException("Unknown equipment: " + key);
        return result;
    }

    public IndustryProfile profile(String key) {
        var result = profiles.get(key);
        if (result == null) throw new IllegalArgumentException("Unknown profile: " + key);
        return result;
    }

    private static EquipmentType.Port port(String key, EquipmentType.Direction dir, String medium, int min) {
        return new EquipmentType.Port(key, dir, medium, min, 1);
    }

    private static EquipmentType item(String key, String name, EquipmentType.Capability cap, Set<EquipmentType.Mode> modes,
                                      EquipmentType.Port... ports) {
        return new EquipmentType(key, 1, name, Set.of(cap), modes, List.of(ports));
    }

    public static EquipmentCatalog standard() {
        var types = List.of(
            item("cement-silo", "Cement silo", STORE, Set.of(), port("cement-out", OUT, "cement", 0)),
            item("aggregate-bin", "Aggregate bin", STORE, Set.of(), port("aggregate-out", OUT, "aggregate", 0)),
            new EquipmentType("water-tank",1,"Water tank",Set.of(STORE),Set.of(),
                List.of(port("water-out", OUT, "water", 0)),
                List.of(new EngineeringPropertyDefinition("nominal_volume",
                    EngineeringUnits.Dimension.VOLUME,"m3",BigDecimal.ZERO,null,false,true))),
            item("admixture-tank", "Admixture tank", STORE, Set.of(), port("admixture-out", OUT, "admixture", 0)),
            item("cement-scale", "Cement scale", MEASURE, Set.of(), port("cement-in", IN, "cement", 1), port("cement-out", OUT, "cement", 0)),
            item("aggregate-scale", "Aggregate scale", MEASURE, Set.of(), port("aggregate-in", IN, "aggregate", 1), port("aggregate-out", OUT, "aggregate", 0)),
            item("water-doser", "Water doser", DOSE, Set.of(TIMED, RECIPE_PARAMETERS), port("water-in", IN, "water", 1), port("water-out", OUT, "water", 0)),
            item("admixture-doser", "Admixture doser", DOSE, Set.of(TIMED, RECIPE_PARAMETERS), port("admixture-in", IN, "admixture", 1), port("admixture-out", OUT, "admixture", 0)),
            item("belt-conveyor", "Belt conveyor", TRANSFER, Set.of(MANUAL_REQUEST), port("aggregate-in", IN, "aggregate", 1), port("aggregate-out", OUT, "aggregate", 0)),
            item("batch-mixer", "Batch mixer", MIX, Set.of(MANUAL_REQUEST, TIMED, RECIPE_PARAMETERS),
                 port("cement-in", IN, "cement", 1), port("aggregate-in", IN, "aggregate", 1),
                 port("water-in", IN, "water", 1), port("admixture-in", IN, "admixture", 0),
                 port("product-out", OUT, "concrete", 1)),
            item("discharge", "Discharge", DISCHARGE, Set.of(), port("product-in", IN, "concrete", 1)),
            item("process-tank", "Process tank", STORE, Set.of(), port("water-out", OUT, "water", 0)),
            new EquipmentType("water-pump",1,"Water pump",Set.of(PUMP),
                Set.of(MANUAL_REQUEST,CONTINUOUS_SETPOINT),
                List.of(port("water-in",IN,"water",1),port("water-out",OUT,"water",0)),
                List.of(
                    new EngineeringPropertyDefinition("rated_flow",
                        EngineeringUnits.Dimension.VOLUMETRIC_FLOW,"m3/h",BigDecimal.ZERO,null,false,true),
                    new EngineeringPropertyDefinition("rated_power",
                        EngineeringUnits.Dimension.POWER,"kW",BigDecimal.ZERO,null,false,true),
                    new EngineeringPropertyDefinition("rated_pressure",
                        EngineeringUnits.Dimension.PRESSURE,"bar",BigDecimal.ZERO,null,false,true))),
            item("pressure-sensor", "Pressure measurement", MEASURE, Set.of(),
                new EquipmentType.Port("measurement",IN,"pressure",0,1,EquipmentType.StreamKind.INSTRUMENT)),
            item("shutoff-valve", "Shutoff valve", ISOLATE, Set.of(MANUAL_REQUEST), port("water-in", IN, "water", 1), port("water-out", OUT, "water", 0))
        );
        var profiles = List.of(
            new IndustryProfile("batch.concrete.v1", IndustryProfile.ProcessKind.BATCH, Set.of(
                "cement-silo", "aggregate-bin", "water-tank", "admixture-tank", "cement-scale",
                "aggregate-scale", "water-doser", "admixture-doser", "belt-conveyor", "batch-mixer", "discharge")),
            new IndustryProfile("continuous.pump.v1", IndustryProfile.ProcessKind.CONTINUOUS, Set.of(
                "process-tank", "water-tank", "water-pump", "pressure-sensor", "shutoff-valve"))
        );
        return new EquipmentCatalog(types, profiles);
    }
}
