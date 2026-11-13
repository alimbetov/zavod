package kz.zavod.catalog;

import java.util.Objects;

/** Pure read-only validation on two physical port declarations. */
public final class ConnectionRules {
    public enum Verdict { OK, DIRECTION_MISMATCH, MEDIUM_MISMATCH }

    private ConnectionRules() {}

    public static Verdict check(EquipmentType.Port source, EquipmentType.Port target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.direction() != EquipmentType.Direction.OUT || target.direction() != EquipmentType.Direction.IN) {
            return Verdict.DIRECTION_MISMATCH;
        }
        if (!source.medium().equals(target.medium())) return Verdict.MEDIUM_MISMATCH;
        return Verdict.OK;
    }
}
