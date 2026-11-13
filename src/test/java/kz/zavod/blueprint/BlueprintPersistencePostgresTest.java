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
}
