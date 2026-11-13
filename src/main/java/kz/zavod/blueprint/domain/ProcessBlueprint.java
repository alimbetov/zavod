package kz.zavod.blueprint.domain;

import kz.zavod.engineering.EngineeringPropertyValue;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable engineering aggregate. A process unit can be a line, circuit, station or zone. */
public record ProcessBlueprint(
        UUID id, long revision, int maxActiveLines,
        List<ProcessUnit> units,
        List<SharedAsset> sharedAssets,
        List<Transfer> transfers,
        List<Dependency> dependencies) {

    public ProcessBlueprint {
        Objects.requireNonNull(id, "blueprint id");
        if (revision < 1 || maxActiveLines < 1) throw new IllegalArgumentException("Invalid revision/line policy");
        units = List.copyOf(units);
        sharedAssets = List.copyOf(sharedAssets);
        transfers = List.copyOf(transfers);
        dependencies = List.copyOf(dependencies);
    }

    public enum UnitKind { LINE, CIRCUIT, ZONE, STATION }
    public enum FlowKind { MATERIAL, ITEM_FLOW, UTILITY, INSTRUMENT }
    public enum Direction { IN, OUT }
    public enum AssetMode { SHARED_CAPACITY, EXCLUSIVE }

    public record Port(String key, Direction direction, FlowKind kind, String medium, int min, int max) {
        public Port {
            Objects.requireNonNull(key); Objects.requireNonNull(direction); Objects.requireNonNull(kind); Objects.requireNonNull(medium);
            if (key.isBlank() || medium.isBlank() || min < 0 || max < 1 || max < min) throw new IllegalArgumentException("Invalid port");
        }
    }
    public record Equipment(String id, String typeKey, int templateVersion, List<Port> ports,
                            List<EngineeringPropertyValue> properties) {
        /** Legacy snapshots had no properties field; old constructor remains source-compatible. */
        public Equipment(String id,String typeKey,int templateVersion,List<Port> ports) {
            this(id,typeKey,templateVersion,ports,List.of());
        }
        public Equipment {
            Objects.requireNonNull(id); Objects.requireNonNull(typeKey);
            if (id.isBlank() || typeKey.isBlank() || templateVersion < 1) throw new IllegalArgumentException("Invalid equipment identity");
            ports = List.copyOf(ports);
            properties = List.copyOf(properties==null?List.of():properties);
        }
    }
    public record Connection(String sourceEquipment, String sourcePort, String targetEquipment, String targetPort) {}
    /** The boundary is an explicit contract, not a direct reference to another unit's internal node. */
    public record Boundary(String key, Direction direction, FlowKind kind, String medium,
                           String equipmentId, String physicalPortKey) {}
    public record Measurement(String sensorEquipmentId, String monitoredEquipmentId, String signalKey) {}
    public record ResourceUse(String sharedAssetId, int demand) {}
    public record ProcessUnit(String id, UnitKind kind, String profileKey,
                              List<Equipment> equipment, List<Connection> connections,
                              List<Boundary> boundaries, List<ResourceUse> resources,
                              List<Measurement> measurements, boolean recirculationAllowed) {
        public ProcessUnit {
            Objects.requireNonNull(id);Objects.requireNonNull(kind);Objects.requireNonNull(profileKey);
            if (id.isBlank() || profileKey.isBlank()) throw new IllegalArgumentException("Unit identity required");
            equipment = List.copyOf(equipment); connections = List.copyOf(connections);
            boundaries = List.copyOf(boundaries); resources = List.copyOf(resources); measurements = List.copyOf(measurements);
        }
    }
    public record SharedAsset(String id, int capacity, AssetMode mode) {}
    public record Transfer(String sourceUnit, String sourceBoundary, String targetUnit, String targetBoundary) {}
    public record Dependency(String sourceUnit, String targetUnit) {}
}
