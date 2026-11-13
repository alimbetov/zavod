package kz.zavod.telemetry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kz.zavod.engineering.EngineeringUnits;

/**
 * Read-only telemetry contracts. Observations are never engineering design
 * parameters and can never authorize or execute physical PLC commands.
 */
public final class TelemetryContracts {
    private TelemetryContracts() {}
    public enum SourceKind { SIMULATED, EDGE_READ_ONLY }
    public enum Quality { GOOD, UNCERTAIN, BAD, STALE, DISCONNECTED }
    public enum Status { CURRENT, UNTRUSTED, STALE, DISCONNECTED, NO_DATA }

    public record SignalDefinition(UUID id,String siteId,UUID blueprintId,long blueprintRevision,
                                   String unitId,String equipmentId,String signalKey,String unitCode,
                                   SourceKind sourceKind,Duration staleAfter) {
        public SignalDefinition {
            Objects.requireNonNull(id); Objects.requireNonNull(blueprintId);
            Objects.requireNonNull(sourceKind);Objects.requireNonNull(staleAfter);
            requireText(siteId);requireText(unitId);requireText(equipmentId);requireText(signalKey);
            EngineeringUnits.require(unitCode);
            // The database stores staleness in milliseconds. A positive
            // sub-millisecond duration would round to zero and become invalid
            // or silently disable expiry when the mapping is reloaded.
            if(blueprintRevision<1||staleAfter.toMillis()<1
                    ||staleAfter.compareTo(Duration.ofDays(7))>0)
                throw new IllegalArgumentException("INVALID_SIGNAL_DEFINITION");
        }
    }

    /** Immutable observation with explicit provenance and separated timestamps. */
    public record TelemetrySample(UUID signalId,SourceKind sourceKind,String sourceEventId,
                                  BigDecimal value,String unitCode,Instant sourceTime,
                                  Instant receivedAt,Quality quality) {
        public TelemetrySample {
            Objects.requireNonNull(signalId);Objects.requireNonNull(sourceKind);
            requireText(sourceEventId);Objects.requireNonNull(value);
            EngineeringUnits.require(unitCode);
            Objects.requireNonNull(sourceTime);Objects.requireNonNull(receivedAt);
            Objects.requireNonNull(quality);
            if(value.scale()>18||value.precision()>38)
                throw new IllegalArgumentException("UNBOUNDED_SAMPLE_PRECISION");
        }
    }

    public record Reading(Status status,BigDecimal value,String unitCode,Instant observedAt,
                          Instant evaluatedAt,SourceKind sourceKind) {
        public Reading {
            Objects.requireNonNull(status);Objects.requireNonNull(evaluatedAt);
        }
    }

    private static void requireText(String text) {
        if(text==null||text.isBlank()||text.length()>128)
            throw new IllegalArgumentException("INVALID_SIGNAL_IDENTITY");
    }
}
