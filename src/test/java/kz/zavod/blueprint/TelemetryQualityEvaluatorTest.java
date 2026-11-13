package kz.zavod.blueprint;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.zavod.telemetry.TelemetryQualityEvaluator;
import kz.zavod.telemetry.TelemetryContracts.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure, no Spring context: safety-critical telemetry quality truth table. */
class TelemetryQualityEvaluatorTest {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000091");
    private final SignalDefinition definition=new SignalDefinition(ID,"local-unit-test",
            UUID.fromString("00000000-0000-0000-0000-000000000092"),1,
            "L1","pump","pressure","bar",SourceKind.SIMULATED,Duration.ofSeconds(20));
    private static final Instant T=Instant.parse("2026-11-13T10:00:00Z");
    private TelemetrySample sample(Quality quality,Instant time,Instant received,
                                   BigDecimal value,String unit,UUID id,SourceKind source) {
        return new TelemetrySample(id,source,"event-1",value,unit,time,received,quality);
    }
    private TelemetrySample sample(Quality q) {
        return sample(q,T,T,new BigDecimal("2.5"),"bar",ID,SourceKind.SIMULATED);
    }

    @Test void missingSampleCannotBeRepresentedAsCurrent() {
        var reading=TelemetryQualityEvaluator.evaluate(definition,null,T);
        assertEquals(Status.NO_DATA,reading.status());
        assertNull(reading.value());
        assertNull(reading.observedAt());
    }
    @Test void currentAtExactStaleBoundaryAndStaleOneNanosecondLater() {
        var good=sample(Quality.GOOD);
        var boundary=TelemetryQualityEvaluator.evaluate(definition,good,T.plusSeconds(20));
        assertEquals(Status.CURRENT,boundary.status());
        assertEquals(0,boundary.value().compareTo(new BigDecimal("2.5")));
        var stale=TelemetryQualityEvaluator.evaluate(definition,good,T.plusSeconds(20).plusNanos(1));
        assertEquals(Status.STALE,stale.status());
        assertNull(stale.value(),"stale telemetry must never expose an apparently verified value");
    }
    @Test void badUncertainStaleAndDisconnectedAreFailClosed() {
        for(var quality:new Quality[]{Quality.BAD,Quality.UNCERTAIN}) {
            var r=TelemetryQualityEvaluator.evaluate(definition,sample(quality),T.plusSeconds(1));
            assertEquals(Status.UNTRUSTED,r.status());
            assertNull(r.value());
        }
        var stale=TelemetryQualityEvaluator.evaluate(definition,sample(Quality.STALE),T.plusSeconds(1));
        assertEquals(Status.STALE,stale.status());
        assertNull(stale.value());
        var down=TelemetryQualityEvaluator.evaluate(definition,sample(Quality.DISCONNECTED),T.plusSeconds(1));
        assertEquals(Status.DISCONNECTED,down.status());
        assertNull(down.value());
    }
    @Test void futureSourceAndFutureReceiveTimesAreUntrusted() {
        var future=sample(Quality.GOOD,T.plusSeconds(1),T,
                BigDecimal.ONE,"bar",ID,SourceKind.SIMULATED);
        var lateReceived=sample(Quality.GOOD,T,T.plusSeconds(1),
                BigDecimal.ONE,"bar",ID,SourceKind.SIMULATED);
        assertEquals(Status.UNTRUSTED,TelemetryQualityEvaluator.evaluate(definition,future,T).status());
        assertEquals(Status.UNTRUSTED,TelemetryQualityEvaluator.evaluate(definition,lateReceived,T).status());
    }
    @Test void convertPressureOnlyWhenQualityAndDimensionMatch() {
        var pascal=sample(Quality.GOOD,T,T,new BigDecimal("250000"),"Pa",ID,SourceKind.SIMULATED);
        var good=TelemetryQualityEvaluator.evaluate(definition,pascal,T.plusSeconds(1));
        assertEquals(Status.CURRENT,good.status());
        assertEquals(0,good.value().compareTo(new BigDecimal("2.5")));
        var power=sample(Quality.GOOD,T,T,BigDecimal.ONE,"kW",ID,SourceKind.SIMULATED);
        assertThrows(IllegalArgumentException.class,
                ()->TelemetryQualityEvaluator.evaluate(definition,power,T.plusSeconds(1)));
    }
    @Test void wrongBindingOrUnqualifiedSourceCannotBeDisplayed() {
        var wrongId=sample(Quality.GOOD,T,T,BigDecimal.ONE,"bar",UUID.randomUUID(),SourceKind.SIMULATED);
        var wrongSource=sample(Quality.GOOD,T,T,BigDecimal.ONE,"bar",ID,SourceKind.EDGE_READ_ONLY);
        assertThrows(IllegalArgumentException.class,
                ()->TelemetryQualityEvaluator.evaluate(definition,wrongId,T.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                ()->TelemetryQualityEvaluator.evaluate(definition,wrongSource,T.plusSeconds(1)));
    }
}
