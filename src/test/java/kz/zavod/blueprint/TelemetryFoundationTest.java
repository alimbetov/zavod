package kz.zavod.blueprint;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.zavod.telemetry.TelemetryContracts.*;
import kz.zavod.telemetry.TelemetryQualityEvaluator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryFoundationTest {
    private final UUID id=UUID.randomUUID(),blueprint=UUID.randomUUID();
    private final SignalDefinition definition=new SignalDefinition(id,"site-A",blueprint,3,
            "L1","pump","pressure","bar",SourceKind.SIMULATED,Duration.ofSeconds(10));
    private final Instant now=Instant.parse("2026-11-13T10:00:00Z");
    private TelemetrySample sample(Instant at,Quality quality,String unit,BigDecimal value) {
        return new TelemetrySample(id,SourceKind.SIMULATED,"event-001",value,unit,at,now,quality);
    }

    @Test void goodReadingConvertsPhysicalUnitWithoutPretendingItIsLive() {
        var reading=TelemetryQualityEvaluator.evaluate(definition,
                sample(now.minusSeconds(5),Quality.GOOD,"Pa",new BigDecimal("200000")),now);
        assertEquals(Status.CURRENT,reading.status());
        assertEquals(0,reading.value().compareTo(new BigDecimal("2")));
        assertEquals(SourceKind.SIMULATED,reading.sourceKind());
    }
    @Test void missingBadUncertainOldDisconnectedAndFutureCannotBecomeCurrent() {
        assertEquals(Status.NO_DATA,TelemetryQualityEvaluator.evaluate(definition,null,now).status());
        assertEquals(Status.UNTRUSTED,TelemetryQualityEvaluator.evaluate(definition,
                sample(now,Quality.BAD,"bar",BigDecimal.ONE),now).status());
        assertEquals(Status.UNTRUSTED,TelemetryQualityEvaluator.evaluate(definition,
                sample(now,Quality.UNCERTAIN,"bar",BigDecimal.ONE),now).status());
        assertEquals(Status.STALE,TelemetryQualityEvaluator.evaluate(definition,
                sample(now.minusSeconds(11),Quality.GOOD,"bar",BigDecimal.ONE),now).status());
        assertEquals(Status.DISCONNECTED,TelemetryQualityEvaluator.evaluate(definition,
                sample(now,Quality.DISCONNECTED,"bar",BigDecimal.ONE),now).status());
        assertEquals(Status.UNTRUSTED,TelemetryQualityEvaluator.evaluate(definition,
                sample(now.plusSeconds(1),Quality.GOOD,"bar",BigDecimal.ONE),now).status());
        assertNull(TelemetryQualityEvaluator.evaluate(definition,
                sample(now,Quality.BAD,"bar",BigDecimal.ONE),now).value());
    }
    @Test void rejectMisboundAndDimensionallyWrongSignal() {
        assertThrows(IllegalArgumentException.class,()->TelemetryQualityEvaluator.evaluate(
                definition,new TelemetrySample(UUID.randomUUID(),SourceKind.SIMULATED,
                "external",BigDecimal.ONE,"bar",now,now,Quality.GOOD),now));
        assertThrows(IllegalArgumentException.class,()->TelemetryQualityEvaluator.evaluate(
                definition,sample(now,Quality.GOOD,"kg",BigDecimal.ONE),now));
        assertThrows(IllegalArgumentException.class,()->new SignalDefinition(
                id,"site-A",blueprint,3,"L1","pump","p","bar",
                SourceKind.EDGE_READ_ONLY,Duration.ZERO));
    }
}
