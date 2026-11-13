package kz.zavod.blueprint;

import java.util.UUID;
import kz.zavod.blueprint.domain.GuidedBlueprintBuilder;
import kz.zavod.blueprint.lifecycle.BusinessTransitionLedger;
import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.studio.StudioBlueprintService;
import kz.zavod.blueprint.lifecycle.SiteEntity;
import kz.zavod.blueprint.lifecycle.SiteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full demo route with real business lifecycle, separate synthetic reviewers,
 * persisted simulated samples and immutable event trail; DEV loopback only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-local")
@Testcontainers
class PlantDemoDevApiPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("demo").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",pg::getJdbcUrl);
        p.add("spring.datasource.username",pg::getUsername);
        p.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired SiteRepository sites;
    @Autowired StudioBlueprintService blueprints;
    @Autowired BusinessTransitionLedger ledger;
    @Test void completeConcreteDemoUsesRealLifecycleAndPersistsSimulatedAlarm() throws Exception {
        String site="local-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        var draft=blueprints.create(site);
        var starter=GuidedBlueprintBuilder.concreteStarter(draft.id(),2,10,"CP01",
                EquipmentCatalog.standard());
        blueprints.saveDraft(site,draft.id(),1,starter);
        String demo="/api/v1/sites/"+site+"/blueprints/"+draft.id()+"/revisions/2/demo";
        String signals="/api/v1/sites/"+site+"/blueprints/"+draft.id()+"/revisions/2/telemetry";
        mvc.perform(get(demo)).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalState").value("DRAFT"))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(post(demo+"/approve"))
                .andExpect(status().isForbidden()); // DEV mutation header is mandatory
        mvc.perform(post(demo+"/approve").header("X-Zavod-Dev-Write","true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalState").value("APPROVED"));
        mvc.perform(post(demo+"/publish").header("X-Zavod-Dev-Write","true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalState").value("PUBLISHED"));
        mvc.perform(post(demo+"/activate").header("X-Zavod-Dev-Write","true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        // Lost HTTP response/retry must not create a second activation/audit event.
        mvc.perform(post(demo+"/activate").header("X-Zavod-Dev-Write","true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitionCount").value(3));
        mvc.perform(post(demo+"/simulate").header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"NORMAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("NORMAL"))
                .andExpect(jsonPath("$.value").value(2.5));
        mvc.perform(post(demo+"/simulate").header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"ALARM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("ALARM"))
                .andExpect(jsonPath("$.value").value(6.2));
        mvc.perform(get(signals+"?unitId=CP01-MIX")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].definition.sourceKind").value("SIMULATED"))
                .andExpect(jsonPath("$[0].definition.signalKey").value("demo-pressure"))
                .andExpect(jsonPath("$[0].latest.status").value("CURRENT"))
                .andExpect(jsonPath("$[0].history.length()").value(2));
        mvc.perform(get(demo)).andExpect(status().isOk())
                .andExpect(jsonPath("$.signalCount").value(1))
                .andExpect(jsonPath("$.transitionCount").value(3));
        ledger.verifySite(site);
        mvc.perform(post(demo+"/simulate").header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"PLC_WRITE\"}"))
                .andExpect(status().isUnprocessableEntity());
        String nonLocal="remote-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(nonLocal,10));
        mvc.perform(get(demo.replace(site,nonLocal))).andExpect(status().isForbidden());
    }
}
