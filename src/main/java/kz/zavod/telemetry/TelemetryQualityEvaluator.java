package kz.zavod.telemetry;

import java.time.Instant;
import java.util.Objects;
import kz.zavod.engineering.EngineeringUnits;
import static kz.zavod.telemetry.TelemetryContracts.*;

/**
 * Fail-closed interpretation of read-only observations at a chosen clock instant.
 * Never silently promotes old/bad readings into a healthy/current signal.
 */
public final class TelemetryQualityEvaluator {
    private TelemetryQualityEvaluator() {}

    public static Reading evaluate(SignalDefinition definition,TelemetrySample sample,Instant now) {
        Objects.requireNonNull(definition);Objects.requireNonNull(now);
        if(sample==null)return new Reading(Status.NO_DATA,null,definition.unitCode(),null,now,definition.sourceKind());
        if(!sample.signalId().equals(definition.id())
                ||sample.sourceKind()!=definition.sourceKind())
            throw new IllegalArgumentException("SIGNAL_BINDING_MISMATCH");
        if(sample.sourceTime().isAfter(now)||sample.receivedAt().isAfter(now))
            return new Reading(Status.UNTRUSTED,null,definition.unitCode(),sample.sourceTime(),now,definition.sourceKind());
        if(sample.quality()==Quality.DISCONNECTED)
            return new Reading(Status.DISCONNECTED,null,definition.unitCode(),sample.sourceTime(),now,definition.sourceKind());
        if(sample.quality()==Quality.BAD||sample.quality()==Quality.UNCERTAIN)
            return new Reading(Status.UNTRUSTED,null,definition.unitCode(),sample.sourceTime(),now,definition.sourceKind());
        if(sample.quality()==Quality.STALE ||
                sample.sourceTime().plus(definition.staleAfter()).isBefore(now))
            return new Reading(Status.STALE,null,definition.unitCode(),sample.sourceTime(),now,definition.sourceKind());
        if(EngineeringUnits.dimension(sample.unitCode())!=EngineeringUnits.dimension(definition.unitCode()))
            throw new IllegalArgumentException("INCOMPATIBLE_SIGNAL_DIMENSION");
        return new Reading(Status.CURRENT,
                EngineeringUnits.convert(sample.value(),sample.unitCode(),definition.unitCode()),
                definition.unitCode(),sample.sourceTime(),now,definition.sourceKind());
    }
}
