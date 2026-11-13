package kz.zavod.blueprint;

import java.util.*;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.studio.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-local")
@Testcontainers
class EngineeringProjectCrudPostgresTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("projectcrud").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EngineeringProjectRepository repository;

    private String site() throws Exception {
        var response=mvc.perform(post("/api/v1/dev/sites").header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxActiveLines\":10}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("siteId").asText();
    }
    private JsonNode create(String site,String name) throws Exception {
        var response=mvc.perform(post("/api/v1/sites/{site}/projects",site)
                .header("X-Zavod-Dev-Write","true").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name",name,"description","Pilot project"))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }

    @Test void createListReadUpdateArchiveAndPreserveImmutableBlueprint() throws Exception {
        String site=site();
        JsonNode created=create(site,"Concrete batching plant");
        String id=created.get("projectId").asText();
        assertEquals("ACTIVE",created.get("status").asText());
        assertEquals(0,created.get("version").asInt());

        mvc.perform(get("/api/v1/sites/{site}/projects",site)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value(id));
        mvc.perform(get("/api/v1/sites/{site}/blueprints/{id}",site,id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));

        mvc.perform(put("/api/v1/sites/{site}/projects/{id}",site,id)
                .header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"name\":\"Concrete line A\",\"description\":\"Verified scope\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("Concrete line A"));

        mvc.perform(put("/api/v1/sites/{site}/projects/{id}",site,id)
                .header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"name\":\"Stale edit\",\"description\":\"ignored\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));

        mvc.perform(delete("/api/v1/sites/{site}/projects/{id}",site,id)
                .param("expectedVersion","1").header("X-Zavod-Dev-Write","true"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/sites/{site}/projects",site)).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/sites/{site}/projects/{id}",site,id))
                .andExpect(status().isNotFound());
        assertEquals(EngineeringProjectEntity.Status.ARCHIVED,
                repository.findById(id).orElseThrow().status());
        mvc.perform(put("/api/v1/sites/{site}/blueprints/{id}/draft",site,id)
                .header("X-Zavod-Dev-Write","true").contentType(MediaType.APPLICATION_JSON)
                .content("{\\"expectedRevision\\":1,\\"snapshot\\":null}"))
                .andExpect(status().isUnprocessableEntity());
        // Soft delete must never remove append-only revision history.
        mvc.perform(get("/api/v1/sites/{site}/blueprints/{id}/revisions/1",site,id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(delete("/api/v1/sites/{site}/projects/{id}",site,id)
                .param("expectedVersion","1").header("X-Zavod-Dev-Write","true"))
                .andExpect(status().isNotFound());
    }

    @Test void accessIsSiteScopedAndWritesRequireLocalHeader() throws Exception {
        String one=site(),other=site();
        String id=create(one,"Pump project").get("projectId").asText();
        mvc.perform(get("/api/v1/sites/{site}/projects/{id}",other,id))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/sites/{site}/projects/{id}",other,id)
                .header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"name\":\"Hijack\",\"description\":\"\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/sites/{site}/projects/{id}",other,id)
                .header("X-Zavod-Dev-Write","true").param("expectedVersion","0"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/sites/{site}/projects",one)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Without header\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/sites/{site}/projects",one)
                .with(req->{req.setRemoteAddr("203.0.113.5");return req;}))
                .andExpect(status().isForbidden());
    }

    @Test void invalidPayloadAndStaleDeletionAreRejected() throws Exception {
        String site=site();
        mvc.perform(post("/api/v1/sites/{site}/projects",site)
                .header("X-Zavod-Dev-Write","true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        String id=create(site,"A").get("projectId").asText();
        mvc.perform(delete("/api/v1/sites/{site}/projects/{id}",site,id)
                .header("X-Zavod-Dev-Write","true").param("expectedVersion","8"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/sites/{site}/projects/{id}",site,id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
