package kz.zavod.blueprint;

import kz.zavod.blueprint.domain.*;
import kz.zavod.blueprint.persistence.*;
import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.lab.PlantScenarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BlueprintPersistencePostgresTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("zavodtest").withUsername("zavod").withPassword("zavodtest");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired BlueprintDraftService drafts;
    @Autowired BlueprintRevisionRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void ninetyNineProcessUnitsCanBeSavedReopenedEditedAndAnalyzed() {
        UUID id=UUID.randomUUID();
        ProcessBlueprint initial=LabBlueprintAdapter.fromLab(id,1,PlantScenarios.generate(99,99));
        assertEquals(99,initial.units().size());
        assertTrue(BlueprintValidator.validate(initial).stream().noneMatch(
                f->f.severity()==BlueprintValidator.Severity.ERROR));
        assertFalse(BlueprintReadiness.blockers(initial,EquipmentCatalog.standard()).isEmpty(),
                "Unresolved catalogue fixtures and unbound interfaces must block publication, but not DRAFT save");

        drafts.saveDraft(initial,0);
        ProcessBlueprint recovered=drafts.loadLatest(id);
        assertEquals(initial,recovered,"All nodes, links, types and shared assets survive PostgreSQL round trip");
        assertEquals(64,recovered.transfers().size());
        assertEquals(14,recovered.dependencies().size());
        assertEquals(2,recovered.sharedAssets().size());
        assertEquals(853,recovered.units().stream().mapToLong(u->u.equipment().size()).sum());

        var units=new ArrayList<>(recovered.units());
        ProcessUnit before=units.get(4);
        ProcessUnit modified=new ProcessUnit(before.id(),before.kind(),"continuous.pump.modified.v2",
                before.equipment(),before.connections(),before.boundaries(),before.resources(),before.measurements(),false);
        units.set(4,modified);
        var revised=new ProcessBlueprint(id,2,99,units,recovered.sharedAssets(),recovered.transfers(),recovered.dependencies());
        var impact=BlueprintImpactAnalyzer.analyze(revised,Set.of(before.id()));
        assertTrue(impact.affectedUnits().contains(before.id()));
        assertTrue(impact.affectedUnits().size()>1,"Shared resource/dependents must be detected");
        assertFalse(impact.contention().isEmpty(),"Shared resource capacity pressure is reported");

        drafts.saveDraft(revised,1);
        assertEquals(revised,drafts.loadLatest(id));
        assertEquals(initial,drafts.loadRevision(id,1),"Old revision stays immutable");
        assertEquals(2,repository.findTopByBlueprintIdOrderByRevisionNoDesc(id.toString()).orElseThrow().revisionNo());
        assertThrows(IllegalStateException.class,()->drafts.saveDraft(revised,1),"No duplicate or blind overwrite");
    }

    @Test
    void twoConcurrentWritersCannotBothCommitTheSameRevision() throws Exception {
        UUID id=UUID.randomUUID();
        ProcessBlueprint initial=LabBlueprintAdapter.fromLab(id,1,PlantScenarios.generate(10,99));
        drafts.saveDraft(initial,0);
        ProcessBlueprint next=new ProcessBlueprint(id,2,99,initial.units(),initial.sharedAssets(),
                initial.transfers(),initial.dependencies());
        CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results=new ArrayList<>();
            for(int n=0;n<2;n++) {
                results.add(pool.submit(()->{
                    ready.countDown();
                    if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("writer startup timeout");
                    try { drafts.saveDraft(next,1); return "SUCCESS"; }
                    catch(BlueprintRevisionConflict ex) {return "CONFLICT";}
                }));
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS),"both writers must be ready");
            start.countDown();
            List<String> outcomes=new ArrayList<>();
            for(Future<String> result:results)outcomes.add(result.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"SUCCESS"),outcomes.toString());
            assertEquals(1,Collections.frequency(outcomes,"CONFLICT"),outcomes.toString());
            assertEquals(next,drafts.loadLatest(id));
            assertEquals(initial,drafts.loadRevision(id,1));
            assertEquals(2L,jdbc.queryForObject(
                    "select count(*) from blueprint_revision where blueprint_id=?",Long.class,id.toString()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void corruptedSnapshotAndUnknownSchemaAreNeverReadSilently() {
        UUID corruptId=UUID.randomUUID();
        ProcessBlueprint a=LabBlueprintAdapter.fromLab(corruptId,1,PlantScenarios.generate(1,99));
        drafts.saveDraft(a,0);
        jdbc.update("update blueprint_revision set snapshot_json=? where blueprint_id=?",
                "{malformed-and-corrupted}",corruptId.toString());
        IllegalStateException corrupted=assertThrows(IllegalStateException.class,
                ()->drafts.loadRevision(corruptId,1));
        assertEquals("CORRUPTED_BLUEPRINT_SNAPSHOT",corrupted.getMessage());

        UUID incompatibleId=UUID.randomUUID();
        ProcessBlueprint b=LabBlueprintAdapter.fromLab(incompatibleId,1,PlantScenarios.generate(1,99));
        drafts.saveDraft(b,0);
        jdbc.update("update blueprint_revision set schema_version=? where blueprint_id=?",
                "blueprint.unknown",incompatibleId.toString());
        IllegalStateException unsupported=assertThrows(IllegalStateException.class,
                ()->drafts.loadRevision(incompatibleId,1));
        assertEquals("UNSUPPORTED_BLUEPRINT_SCHEMA",unsupported.getMessage());
    }
}
