package kz.zavod.blueprint;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.studio.StudioBlueprintService;
import kz.zavod.blueprint.lifecycle.SiteEntity;
import kz.zavod.blueprint.lifecycle.SiteRepository;
import kz.zavod.telemetry.*;
import kz.zavod.telemetry.TelemetryContracts.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class TelemetryRegistryPostgresTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("telemetry").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",postgres::getJdbcUrl);
        props.add("spring.datasource.username",postgres::getUsername);
        props.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired SiteRepository sites;
    @Autowired StudioBlueprintService blueprints;
    @Autowired SignalRegistryService registry;
    @Autowired SignalBindingRepository mappings;

    private ProcessBlueprint createWithPump(String site) {
        sites.saveAndFlush(new SiteEntity(site,10));
        var first=blueprints.create(site);
        var pump=new Equipment("pump","water-pump",1,List.of(),List.of());
        var unit=new ProcessUnit("L1",UnitKind.LINE,"continuous.pump.v1",
                List.of(pump),List.of(),List.of(),List.of(),List.of(),false);
        return blueprints.saveDraft(site,first.id(),1,
                new ProcessBlueprint(first.id(),2,10,List.of(unit),List.of(),List.of(),List.of()));
    }
    @Test void registeredSignalIdentityMustNeverBeRetargetedWithSameUuid() {
        String site="binding-immutable-"+UUID.randomUUID();
        var blueprint=createWithPump(site);
        UUID signalId=UUID.randomUUID();
        var original=new SignalDefinition(signalId,site,blueprint.id(),2,
                "L1","pump","pressure","bar",SourceKind.SIMULATED,Duration.ofSeconds(30));
        assertEquals(original,registry.registerSimulated(original));
        // Reusing the same client-supplied ID must not mutate an immutable
        // signal binding. Otherwise historical samples would be reattributed.
        var forged=new SignalDefinition(signalId,site,blueprint.id(),2,
                "L1","pump","forged-pressure","Pa",SourceKind.SIMULATED,Duration.ofSeconds(5));
        assertThrows(TelemetryIngestionConflict.class,()->registry.registerSimulated(forged));
        assertEquals(original,mappings.findById(signalId.toString()).orElseThrow().definition());
        assertEquals(List.of(original),registry.list(site,blueprint.id(),2));
        assertEquals(1,mappings.count());
    }

    @Test void versionPinnedBindingRoundTripsAndRejectsCrossSiteAndUnknownEquipment() {
        String site="telemetry-"+UUID.randomUUID(),other="other-"+UUID.randomUUID();
        var blueprint=createWithPump(site);
        sites.saveAndFlush(new SiteEntity(other,10));
        var def=new SignalDefinition(UUID.randomUUID(),site,blueprint.id(),2,
                "L1","pump","pressure","bar",SourceKind.SIMULATED,Duration.ofSeconds(30));
        assertEquals(def,registry.registerSimulated(def));
        assertEquals(List.of(def),registry.list(site,blueprint.id(),2));
        assertEquals(1,mappings.count());
        assertThrows(RuntimeException.class,()->registry.list(other,blueprint.id(),2));
        assertThrows(IllegalArgumentException.class,()->registry.registerSimulated(
                new SignalDefinition(UUID.randomUUID(),site,blueprint.id(),2,
                        "L1","nonexistent","pressure","bar",SourceKind.SIMULATED,Duration.ofSeconds(30))));
        assertThrows(IllegalArgumentException.class,()->registry.registerSimulated(
                new SignalDefinition(UUID.randomUUID(),site,blueprint.id(),2,
                        "L1","pump","pressure","bar",SourceKind.EDGE_READ_ONLY,Duration.ofSeconds(30))));
    }
}
