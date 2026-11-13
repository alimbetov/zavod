package kz.zavod.blueprint;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Production/default Boot profile must never expose the local engineering sandbox. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StudioDefaultProfileSecurityPostgresTest {
    @Container
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("studiosecure").withUsername("zavod").withPassword("zavodtest");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props){
        props.add("spring.datasource.url",postgres::getJdbcUrl);
        props.add("spring.datasource.username",postgres::getUsername);
        props.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Test
    void localStudioEndpointsAreDeniedWithoutExplicitDevProfile() throws Exception {
        mvc.perform(get("/api/v1/dev/sites")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/sites/local-test/equipment-types")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/sites/local-test/projects")).andExpect(status().isForbidden());
        // Synthetic demo lifecycle is NEVER exposed outside dev-local.
        mvc.perform(get("/api/v1/sites/local-test/blueprints/"
                +"00000000-0000-0000-0000-000000000001/revisions/2/demo"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sites/local-test/blueprints/"
                +"00000000-0000-0000-0000-000000000001/revisions/2/demo/activate")
                .header("X-Zavod-Dev-Write","true"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sites/local-test/projects")
            .header("X-Zavod-Dev-Write","true").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Must not open\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dev/sites").header("X-Zavod-Dev-Write","true")
            .contentType(MediaType.APPLICATION_JSON).content("{\"maxActiveLines\":10}"))
            .andExpect(status().isForbidden());
    }
}
