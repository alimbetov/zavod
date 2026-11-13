package kz.zavod.blueprint;

import java.util.*;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import kz.zavod.studio.StudioBlueprintService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Application-context restart with the SAME PostgreSQL instance, not a full JVM/OS restart. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlueprintContextRestartPostgresTest {
    @Container
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("restart").withUsername("zavod").withPassword("zavodtest");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }
    private static ApplicationContext firstContext;
    private static UUID blueprintId;
    private static String siteId,activationId;
    private static ProcessBlueprint immutableSnapshot;

    @Autowired ApplicationContext context;
    @Autowired SiteRepository sites;
    @Autowired SiteMemberRepository members;
    @Autowired StudioBlueprintService studio;
    @Autowired BlueprintDraftService drafts;
    @Autowired BlueprintLifecycleService lifecycle;

    private void actor(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username,"N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
    }
    @AfterEach void clearContext() {SecurityContextHolder.clearContext();}

    @Test @Order(1)
    @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void createValidateApproveActivateThenCloseSpringApplicationContext() {
        firstContext=context;
        siteId="site-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(siteId,10));
        Map<String,SiteMemberEntity.Role> roles=Map.of(
                "reviewer",SiteMemberEntity.Role.REVIEWER,
                "publisher",SiteMemberEntity.Role.PUBLISHER,
                "activator",SiteMemberEntity.Role.ACTIVATOR,
                "viewer",SiteMemberEntity.Role.VIEWER);
        for(var e:roles.entrySet())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),siteId,
                    e.getKey(),e.getValue()));
        var empty=studio.create(siteId);
        blueprintId=empty.id();
        var tank=new Equipment("T001","water-tank",1,
                List.of(new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var unit=new ProcessUnit("L001",UnitKind.LINE,"continuous.pump.v1",
                List.of(tank),List.of(),List.of(),List.of(),List.of(),false);
        immutableSnapshot=new ProcessBlueprint(blueprintId,2,10,
                List.of(unit),List.of(),List.of(),List.of());
        studio.saveDraft(siteId,blueprintId,1,immutableSnapshot);
        actor("reviewer");lifecycle.approve(siteId,blueprintId,2,Set.of("L001"));
        actor("publisher");lifecycle.publish(siteId,blueprintId,2);
        actor("activator");
        activationId=lifecycle.activate(siteId,blueprintId,2,null);
        assertEquals(immutableSnapshot,drafts.loadRevision(blueprintId,2));
    }

    @Test @Order(2)
    void freshApplicationContextReadsSameImmutableBlueprintAndActivationFromPostgres() {
        assertNotNull(firstContext);
        assertNotSame(firstContext,context,"Spring application context must be recreated");
        assertNotNull(blueprintId);
        assertEquals(immutableSnapshot,drafts.loadRevision(blueprintId,2));
        assertEquals(immutableSnapshot,drafts.loadLatest(blueprintId));
        actor("viewer");
        var active=lifecycle.active(siteId);
        assertEquals(activationId,active.id());
        assertEquals(2L,active.revisionNo());
        assertEquals(blueprintId.toString(),active.blueprintId());
        assertTrue(active.selectedUnitsJson().contains("L001"));
    }
}
