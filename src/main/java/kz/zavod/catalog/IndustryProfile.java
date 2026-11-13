package kz.zavod.catalog;

import java.util.Objects;
import java.util.Set;

/** A catalog selection and process classification, NOT a PLC implementation. */
public record IndustryProfile(String key, ProcessKind processKind, Set<String> equipmentKeys) {
    public enum ProcessKind { BATCH, CONTINUOUS, DISCRETE, UTILITY }

    public IndustryProfile {
        EquipmentType.requireKey(key, "profile key");
        Objects.requireNonNull(processKind, "process kind");
        equipmentKeys = Set.copyOf(Objects.requireNonNull(equipmentKeys, "equipment keys"));
        if (equipmentKeys.isEmpty()) throw new IllegalArgumentException("Profile must contain equipment");
        equipmentKeys.forEach(k -> EquipmentType.requireKey(k, "equipment key"));
    }
}
