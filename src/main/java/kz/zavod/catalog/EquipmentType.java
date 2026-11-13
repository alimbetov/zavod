package kz.zavod.catalog;

import kz.zavod.engineering.EngineeringPropertyDefinition;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** An immutable equipment template, independent from PLC addresses and process recipes. */
public record EquipmentType(
        String key,
        int version,
        String label,
        Set<Capability> capabilities,
        Set<Mode> modes,
        List<Port> ports,
        List<EngineeringPropertyDefinition> properties) {
    /** Compatibility for the original v1 templates without numeric properties. */
    public EquipmentType(String key,int version,String label,Set<Capability> capabilities,
                         Set<Mode> modes,List<Port> ports) {
        this(key,version,label,capabilities,modes,ports,List.of());
    }

    public enum Capability { STORE, MIX, DOSE, TRANSFER, PUMP, MEASURE, DISCHARGE, ISOLATE }
    public enum Mode { MANUAL_REQUEST, TIMED, RECIPE_PARAMETERS, CONTINUOUS_SETPOINT }
    public enum Direction { IN, OUT }
    public enum StreamKind { MATERIAL, ITEM_FLOW, UTILITY, INSTRUMENT }

    /** A physical connection point. Media names are canonical, not translated UI labels. */
    public record Port(String key, Direction direction, String medium, int minConnections, int maxConnections, StreamKind streamKind) {
        /** Compatibility constructor for the first material-flow equipment templates. */
        public Port(String key, Direction direction, String medium, int minConnections, int maxConnections) {
            this(key,direction,medium,minConnections,maxConnections,StreamKind.MATERIAL);
        }
        public Port {
            requireKey(key, "port key");
            Objects.requireNonNull(direction, "port direction");
            requireKey(medium, "port medium");
            Objects.requireNonNull(streamKind, "port stream kind");
            if (minConnections < 0 || maxConnections < minConnections || maxConnections < 1) {
                throw new IllegalArgumentException("Invalid port cardinality for " + key);
            }
        }
    }

    public EquipmentType {
        requireKey(key, "equipment key");
        requireKey(label, "equipment label");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        modes = Set.copyOf(Objects.requireNonNull(modes, "modes"));
        ports = List.copyOf(Objects.requireNonNull(ports, "ports"));
        properties=List.copyOf(properties==null?List.of():properties);
        if (capabilities.isEmpty()) throw new IllegalArgumentException("capabilities must not be empty");
        Set<String> keys = new HashSet<>();
        for (Port port : ports) {
            if (!keys.add(port.key())) throw new IllegalArgumentException("Duplicate port: " + port.key());
        }
        Set<String> propertyKeys=new HashSet<>();
        for(EngineeringPropertyDefinition p:properties)
            if(!propertyKeys.add(p.key()))throw new IllegalArgumentException("Duplicate property: "+p.key());
        if (modes.contains(Mode.RECIPE_PARAMETERS) && !capabilities.contains(Capability.MIX) && !capabilities.contains(Capability.DOSE)) {
            throw new IllegalArgumentException("RECIPE_PARAMETERS requires MIX or DOSE capability");
        }
    }

    public Port port(String key) {
        return ports.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown port: " + key));
    }

    static void requireKey(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}
