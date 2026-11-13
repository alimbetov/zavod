package kz.zavod.blueprint;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.blueprint.lifecycle.SiteEntity;
import kz.zavod.blueprint.lifecycle.SiteRepository;
import kz.zavod.studio.StudioBlueprintService;
import kz.zavod.telemetry.TelemetryContracts.*;
import kz.zavod.telemetry.TelemetryIngestionService;
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

/** Real HTTP/MockMvc contract against PostgreSQL; browser qualification is separate. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-local")
@Testcontainers
class TelemetryMonitorDevApiPostgresTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("tf03").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired SiteRepository sites;
    @Autowired StudioBlueprintService blueprints;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    @Test void simulatedSignalAndSampleAreVersionBoundAndReturnedAsReadOnlyMonitoring() throws Exception {
        String site="tf03-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        var initial=blueprints.create(site);
        var equipment=new Equipment("pump","water-pump",1,List.of(),List.of());
        var unit=new ProcessUnit("L1",UnitKind.LINE,"continuous.pump.v1",
                List.of(equipment),List.of(),List.of(),List.of(),List.of(),false);
        blueprints.saveDraft(site,initial.id(),1,new ProcessBlueprint(initial.id(),2,10,
                List.of(unit),List.of(),List.of(),List.of()));
        var binding=new SignalDefinition(UUID.randomUUID(),site,initial.id(),2,"L1",
                "pump","pressure","bar",SourceKind.SIMULATED,Duration.ofSeconds(20));
        String base="/api/v1/sites/"+site+"/blueprints/"+initial.id()+"/revisions/2/telemetry";
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post(base+"/signals").header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(binding)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceKind").value("SIMULATED"));
        var now=Instant.now();
        var incoming=new TelemetryIngestionService.Incoming("evt-1",new BigDecimal("2.5"),
                "bar",now.minusSeconds(2),Quality.GOOD);
        mvc.perform(post(base+"/signals/"+binding.id()+"/samples")
                .header("X-Zavod-Dev-Write","true").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(incoming)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false));
        // Event key is immutable. The HTTP contract must report a telemetry
        // payload conflict, not a misleading Blueprint revision conflict.
        var conflicting=new TelemetryIngestionService.Incoming("evt-1",new BigDecimal("3.1"),
                "bar",now.minusSeconds(2),Quality.GOOD);
        mvc.perform(post(base+"/signals/"+binding.id()+"/samples")
                .header("X-Zavod-Dev-Write","true").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(conflicting)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEMETRY_EVENT_CONFLICT"));
        mvc.perform(get(base+"?unitId=L1&minutes=60&limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].definition.equipmentId").value("pump"))
                .andExpect(jsonPath("$[0].latest.status").value("CURRENT"))
                .andExpect(jsonPath("$[0].history[0].sourceEventId").value("evt-1"));
        // Clock-skew-budget future observation must suppress the previous GOOD
        // at the HTTP boundary, not only when invoking the pure evaluator.
        var future=new TelemetryIngestionService.Incoming("evt-clock-ahead",new BigDecimal("6.2"),
                "bar",Instant.now().plusSeconds(10),Quality.GOOD);
        mvc.perform(post(base+"/signals/"+binding.id()+"/samples")
                .header("X-Zavod-Dev-Write","true").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(future))).andExpect(status().isOk());
        mvc.perform(get(base+"?unitId=L1&minutes=60&limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latest.status").value("UNTRUSTED"))
                .andExpect(jsonPath("$[0].latest.value").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(base+"?unitId=L2"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(base+"?limit=501")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(base+"?minutes=0")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(base+"/signals/"+binding.id()+"/samples")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(incoming)))
                .andExpect(status().isForbidden());
        String other="other-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(other,10));
        mvc.perform(get(base.replace(site,other))).andExpect(status().isNotFound());
    }
}
