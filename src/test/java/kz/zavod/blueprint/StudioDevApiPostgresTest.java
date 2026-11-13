package kz.zavod.blueprint;

import java.math.BigDecimal;
import java.util.*;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.engineering.*;
import kz.zavod.studio.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-local")
@Testcontainers
class StudioDevApiPostgresTest {
    @Container
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("studio").withUsername("zavod").withPassword("zavodtest");
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String WRITABLE="X-Zavod-Dev-Write";
    private String createSite(int maxLines) throws Exception {
        var response=mvc.perform(post("/api/v1/dev/sites").header(WRITABLE,"true")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxActiveLines\":"+maxLines+"}"))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("siteId").asText();
    }
    private ProcessBlueprint createBlueprint(String site) throws Exception{
        var response=mvc.perform(post("/api/v1/sites/{siteId}/blueprints",site)
            .header(WRITABLE,"true").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        return json.readValue(response.getResponse().getContentAsString(),ProcessBlueprint.class);
    }
    private static Equipment equipment(String id,String typeKey,List<EngineeringPropertyValue> properties){
        var template=EquipmentCatalog.standard().type(typeKey);
        var ports=template.ports().stream().map(p->new Port(p.key(),
            Direction.valueOf(p.direction().name()),
            FlowKind.valueOf(p.streamKind().name()),p.medium(),
            p.minConnections(),p.maxConnections())).toList();
        return new Equipment(id,template.key(),template.version(),ports,properties);
    }
    private static ProcessBlueprint pumpLine(ProcessBlueprint baseline) {
        var property=new EngineeringPropertyValue("rated_flow",
            new EngineeringQuantity(new BigDecimal("100"),"L/min"),
            EngineeringPropertyValue.Source.MANUFACTURER,"verified-pump/page-3");
        List<Equipment> equipment=List.of(equipment("tank","water-tank",List.of()),
                equipment("pump","water-pump",List.of(property)),
                equipment("valve","shutoff-valve",List.of()));
        List<Connection> connections=List.of(new Connection("tank","water-out","pump","water-in"),
                new Connection("pump","water-out","valve","water-in"));
        var unit=new ProcessUnit("L001",UnitKind.LINE,"continuous.pump.v1",
                equipment,connections,List.of(),List.of(),List.of(),false);
        // 999 is deliberately hostile. Server must replace it with site-owned 10.
        return new ProcessBlueprint(baseline.id(),2,999,List.of(unit),List.of(),List.of(),List.of());
    }

    @Test
    void localhostCreatesValidatesSavesAndRestoresGraphAndIndependentCanvas() throws Exception {
        String site=createSite(10);
        var palette=mvc.perform(get("/api/v1/sites/{site}/equipment-types",site))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var definitions=json.readTree(palette);
        assertEquals(15,definitions.size());
        assertTrue(palette.contains("rated_flow"));
        var baseline=createBlueprint(site);
        assertEquals(1,baseline.revision());
        assertTrue(baseline.units().isEmpty());
        var next=pumpLine(baseline);
        String base="/api/v1/sites/"+site+"/blueprints/"+baseline.id();
        var saved=mvc.perform(put(base+"/draft").header(WRITABLE,"true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StudioDevController.SaveDraftRequest(1,next))))
            .andExpect(status().isOk()).andReturn();
        var restored=json.readValue(saved.getResponse().getContentAsString(),ProcessBlueprint.class);
        assertEquals(2,restored.revision());
        assertEquals(10,restored.maxActiveLines(),"Policy is not browser controlled");
        assertEquals(3,restored.units().get(0).equipment().size());

        mvc.perform(get(base)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(post(base+"/validate").header(WRITABLE,"true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(restored)))
            .andExpect(status().isOk()).andExpect(content().json("[]"));

        String layoutPath=base+"/layouts/engineering";
        var layout=new StudioViewLayout.LayoutBody("L001",Map.of(
                "tank",new StudioViewLayout.Position(30.5,12.0),
                "pump",new StudioViewLayout.Position(200.0,12.0),
                "valve",new StudioViewLayout.Position(400.0,12.0)),
                new StudioViewLayout.Viewport(10.0,20.0,1.2));
        var layout1=new StudioViewLayout.SaveLayout(0,2,layout);
        mvc.perform(put(layoutPath).header(WRITABLE,"true")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(layout1)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.layoutVersion").value(1));
        mvc.perform(get(layoutPath)).andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.positions.pump.x").value(200.0))
            .andExpect(jsonPath("$.blueprintRevision").value(2));
        var moved=new StudioViewLayout.LayoutBody("L001",Map.of(
                "pump",new StudioViewLayout.Position(215.0,12.0)),
                new StudioViewLayout.Viewport(0,0,2));
        mvc.perform(put(layoutPath).header(WRITABLE,"true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StudioViewLayout.SaveLayout(1,2,moved))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.layoutVersion").value(2));
        mvc.perform(get(base)).andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put(layoutPath).header(WRITABLE,"true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StudioViewLayout.SaveLayout(1,2,moved))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        mvc.perform(put(base+"/draft").header(WRITABLE,"true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StudioDevController.SaveDraftRequest(1,next))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));

        mvc.perform(get(base+"/impact").param("from","1").param("to","2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.affectedUnits[0]").value("L001"));
    }

    @Test
    void guidedBuilderGoldenExplanationsAndChangeEndpointAreSiteScoped() throws Exception {
        String site=createSite(10);
        var empty=createBlueprint(site);
        String base="/api/v1/sites/"+site+"/blueprints/"+empty.id();
        var generated=mvc.perform(get(base+"/guided/pump").param("unitId","L001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn();
        var starter=json.readValue(generated.getResponse().getContentAsString(),ProcessBlueprint.class);
        mvc.perform(put(base+"/draft").header(WRITABLE,"true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StudioDevController.SaveDraftRequest(1,starter))))
                .andExpect(status().isOk());
        mvc.perform(get(base+"/golden")).andExpect(status().isOk())
                .andExpect(jsonPath("$.structurallyQualified").value(true))
                .andExpect(jsonPath("$.physicallyQualified").value(false));
        mvc.perform(get(base+"/explain")).andExpect(status().isOk());
        mvc.perform(get(base+"/changes").param("from","1").param("to","2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changedUnits[0]").value("L001"))
                .andExpect(jsonPath("$.changedBy").value("NOT_RECORDED"));
        mvc.perform(get(base+"/revisions/2/hmi-preview"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ENGINEERING_INPUT"));
        String other=createSite(10);
        mvc.perform(get("/api/v1/sites/{site}/blueprints/{id}/golden",other,empty.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void localOnlyHeaderAndSiteScopeAreEnforced() throws Exception {
        String one=createSite(10);
        String other=createSite(10);
        ProcessBlueprint own=createBlueprint(one);
        String path="/api/v1/sites/"+one+"/blueprints/"+own.id();
        mvc.perform(get("/api/v1/sites/{site}/blueprints/{id}",other,own.id()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BLUEPRINT_NOT_FOUND"));
        mvc.perform(post("/api/v1/sites/{site}/blueprints",one))
            .andExpect(status().isForbidden()); // unsafe request without explicit local header
        mvc.perform(get(path).with(req->{req.setRemoteAddr("203.0.113.8");return req;}))
            .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Origin","https://evil.example"))
            .andExpect(status().isForbidden());
    }
}
