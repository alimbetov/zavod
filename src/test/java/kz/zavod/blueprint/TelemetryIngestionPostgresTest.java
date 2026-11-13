package kz.zavod.blueprint;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.blueprint.lifecycle.SiteEntity;
import kz.zavod.blueprint.lifecycle.SiteRepository;
import kz.zavod.studio.StudioBlueprintService;
import kz.zavod.telemetry.*;
import kz.zavod.telemetry.TelemetryContracts.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class TelemetryIngestionPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("tf02").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",pg::getJdbcUrl);
        p.add("spring.datasource.username",pg::getUsername);
        p.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired SiteRepository sites;
    @Autowired StudioBlueprintService blueprints;
    @Autowired SignalRegistryService registry;
    @Autowired TelemetryIngestionService ingestion;
    @Autowired JdbcTemplate jdbc;

    private SignalDefinition createSignal() {
        String site="tf02-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        var empty=blueprints.create(site);
        var unit=new ProcessUnit("L1",UnitKind.LINE,"continuous.pump.v1",
                List.of(new Equipment("pump","water-pump",1,List.of(),List.of())),
                List.of(),List.of(),List.of(),List.of(),false);
        var next=new ProcessBlueprint(empty.id(),2,10,List.of(unit),List.of(),List.of(),List.of());
        blueprints.saveDraft(site,empty.id(),1,next);
        return registry.registerSimulated(new SignalDefinition(UUID.randomUUID(),
                site,empty.id(),2,"L1","pump","pressure","bar",
                SourceKind.SIMULATED,Duration.ofSeconds(20)));
    }
    private static TelemetryIngestionService.Incoming sample(String event,Instant at,String value) {
        return new TelemetryIngestionService.Incoming(event,new BigDecimal(value),
                "bar",at,Quality.GOOD);
    }
    private long count(UUID signalId){
        return jdbc.queryForObject("select count(*) from telemetry_sample where signal_id=?",
                Long.class,signalId.toString());
    }

    @Test void idempotentRetryReturnsOriginalReceiptAndPayloadMismatchIsRejected(){
        var def=createSignal();
        var at=Instant.now().minusSeconds(3);
        var incoming=sample("seq-001",at,"2.00");
        var first=ingestion.ingestSimulated(def.siteId(),def.blueprintId(),
                def.blueprintRevision(),def.id(),incoming);
        var replay=ingestion.ingestSimulated(def.siteId(),def.blueprintId(),
                def.blueprintRevision(),def.id(),incoming);
        assertFalse(first.duplicate());
        assertTrue(replay.duplicate());
        assertEquals(first.sample(),replay.sample());
        assertEquals(1,count(def.id()));
        assertThrows(TelemetryIngestionConflict.class,()->ingestion.ingestSimulated(
                def.siteId(),def.blueprintId(),def.blueprintRevision(),def.id(),
                sample("seq-001",at,"3")));
        assertEquals(1,count(def.id()));
        var history=ingestion.history(def.siteId(),def.blueprintId(),2,def.id(),
                at.minusSeconds(1),at.plusSeconds(1),20);
        assertEquals(1,history.size());
        assertEquals("seq-001",history.get(0).sourceEventId());
    }

    @Test void outOfOrderSourceTimeCannotReplaceLatestAndStaleStateIsExplicit(){
        var def=createSignal();
        var now=Instant.now();
        ingestion.ingestSimulated(def.siteId(),def.blueprintId(),2,def.id(),
                sample("later",now.minusSeconds(1),"3"));
        ingestion.ingestSimulated(def.siteId(),def.blueprintId(),2,def.id(),
                sample("earlier",now.minusSeconds(30),"1"));
        // receivedAt is later than the source clock captured before ingestion.
        // Evaluate after commit; time-travel reads remain UNTRUSTED by design.
        var evaluationTime=Instant.now().plusMillis(100);
        var reading=ingestion.latest(def.siteId(),def.blueprintId(),2,def.id(),evaluationTime);
        assertEquals(Status.CURRENT,reading.status());
        assertEquals(0,reading.value().compareTo(new BigDecimal("3")));
        assertEquals(Status.STALE,ingestion.latest(def.siteId(),def.blueprintId(),2,
                def.id(),evaluationTime.plusSeconds(40)).status());
        var history=ingestion.history(def.siteId(),def.blueprintId(),2,def.id(),
                now.minusSeconds(60),now.plusSeconds(1),5);
        assertEquals(List.of("earlier","later"),history.stream()
                .map(TelemetrySample::sourceEventId).toList());
    }

    @Test void crossSiteFutureTimestampAndInvalidQueriesAreDenied(){
        var def=createSignal();
        String other="other-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(other,10));
        var now=Instant.now();
        assertThrows(RuntimeException.class,()->ingestion.ingestSimulated(other,def.blueprintId(),
                2,def.id(),sample("wrong-site",now,"2")));
        assertThrows(IllegalArgumentException.class,()->ingestion.ingestSimulated(
                def.siteId(),def.blueprintId(),2,def.id(),
                sample("future",now.plusSeconds(90),"2")));
        assertThrows(IllegalArgumentException.class,()->ingestion.history(
                def.siteId(),def.blueprintId(),2,def.id(),now,now,10));
        assertThrows(IllegalArgumentException.class,()->ingestion.history(
                def.siteId(),def.blueprintId(),2,def.id(),
                now.minusSeconds(30),now,501));
        assertEquals(0,count(def.id()));
    }

    @Test void concurrentSameEventCommitsExactlyOneRow() throws Exception {
        var def=createSignal();
        var item=sample("same-event",Instant.now().minusSeconds(2),"2");
        var pool=Executors.newFixedThreadPool(4);
        var start=new CountDownLatch(1);
        try {
            List<Future<TelemetryIngestionService.Receipt>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(pool.submit(()->{
                start.await();
                return ingestion.ingestSimulated(def.siteId(),def.blueprintId(),2,def.id(),item);
            }));
            start.countDown();
            int inserted=0,duplicates=0;
            for(var call:calls) {
                var result=call.get(25,TimeUnit.SECONDS);
                if(result.duplicate())duplicates++;else inserted++;
            }
            assertEquals(1,inserted);
            assertEquals(3,duplicates);
            assertEquals(1,count(def.id()));
        } finally {pool.shutdownNow();}
    }
}
