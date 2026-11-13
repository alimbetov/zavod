package kz.zavod.blueprint;

import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BlueprintLifecyclePostgresTest {
    @Container
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("zavodlife").withUsername("zavod").withPassword("zavodtest");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired BlueprintDraftService drafts;
    @Autowired BlueprintLifecycleService lifecycle;
    @Autowired SiteRepository sites;
    @Autowired SiteMemberRepository members;
    @Autowired BlueprintSiteRepository assignments;
    @Autowired BlueprintApprovalRepository approvals;
    @Autowired PlantActivationSetRepository activationSets;

    @AfterEach void clearIdentity(){ SecurityContextHolder.clearContext(); }

    private void actor(String username){
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username,"N/A",
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
    }

    private record Fixture(String siteId,UUID blueprintId,ProcessBlueprint snapshot,Set<String> selected) {}

    /** A valid 99-line DRAFT with 10 publishable active lines; no physical IO. */
    private Fixture setup(int count){
        String siteId="site-"+UUID.randomUUID();
        UUID id=UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(siteId,10));
        assignments.saveAndFlush(new BlueprintSiteEntity(id.toString(),siteId));
        for(var entry:Map.of("reviewer",SiteMemberEntity.Role.REVIEWER,
                "publisher",SiteMemberEntity.Role.PUBLISHER,
                "activator",SiteMemberEntity.Role.ACTIVATOR,
                "viewer",SiteMemberEntity.Role.VIEWER).entrySet()) {
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),siteId,
                    entry.getKey(),entry.getValue()));
        }
        List<ProcessUnit> units=new ArrayList<>();
        for(int i=0;i<count;i++){
            String name=String.format(Locale.ROOT,"LINE-%03d",i);
            Equipment equipment=new Equipment("tank-"+i,"water-tank",1,List.of(
                    new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
            units.add(new ProcessUnit(name,UnitKind.LINE,"test.water-storage",
                    List.of(equipment),List.of(),List.of(),List.of(),List.of(),false));
        }
        ProcessBlueprint b=new ProcessBlueprint(id,1,1,units,List.of(),List.of(),List.of());
        drafts.saveDraft(b,0);
        Set<String> selected=new TreeSet<>();
        for(int i=0;i<Math.min(10,count);i++)selected.add(String.format(Locale.ROOT,"LINE-%03d",i));
        return new Fixture(siteId,id,b,selected);
    }

    @Test void approvePublishActivateTenOfNinetyNineAndKeepImmutableSelection(){
        Fixture f=setup(99);
        actor("viewer");
        assertThrows(SecurityException.class,()->lifecycle.approve(f.siteId(),f.blueprintId(),1,f.selected()));
        actor("reviewer");
        lifecycle.approve(f.siteId(),f.blueprintId(),1,f.selected());
        assertThrows(SecurityException.class,()->lifecycle.publish(f.siteId(),f.blueprintId(),1));
        actor("activator");
        assertThrows(BlueprintLifecycleException.class,()->lifecycle.activate(f.siteId(),f.blueprintId(),1,null));
        actor("publisher");
        lifecycle.publish(f.siteId(),f.blueprintId(),1);
        actor("activator");
        String activeId=lifecycle.activate(f.siteId(),f.blueprintId(),1,null);
        assertNotNull(activeId);
        assertThrows(BlueprintLifecycleException.class,()->lifecycle.activate(f.siteId(),f.blueprintId(),1,null));
        actor("viewer");
        var active=lifecycle.active(f.siteId());
        assertEquals(activeId,active.id());
        assertEquals(f.blueprintId().toString(),active.blueprintId());
        assertEquals(1L,active.revisionNo());
        assertNull(active.previousSetId());
        assertEquals(10,new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(readJson(active.selectedUnitsJson()),List.class).size());
        assertEquals(f.snapshot(),drafts.loadRevision(f.blueprintId(),1));
        assertEquals(1,activationSets.count());
    }

    private static List<?> readJson(String input){
        try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(input,List.class);}
        catch(Exception ex){throw new AssertionError(ex);}
    }

    @Test void invalidDraftCannotBeApprovedAndSiteBoundaryIsEnforced(){
        Fixture f=setup(1);
        var unit=f.snapshot().units().get(0);
        var invalid=new ProcessUnit(unit.id(),unit.kind(),unit.profileKey(),
                unit.equipment(),unit.connections(),List.of(
                    new Boundary("bad",Direction.OUT,FlowKind.MATERIAL,"water","tank-0","missing-port")),
                unit.resources(),unit.measurements(),false);
        ProcessBlueprint revision2=new ProcessBlueprint(f.blueprintId(),2,1,
                List.of(invalid),List.of(),List.of(),List.of());
        drafts.saveDraft(revision2,1);
        actor("reviewer");
        BlueprintLifecycleException blocked=assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.approve(f.siteId(),f.blueprintId(),2,f.selected()));
        assertTrue(blocked.getMessage().contains("INVALID_BOUNDARY_BINDING"));
        assertEquals(f.snapshot(),drafts.loadRevision(f.blueprintId(),1));
        actor("viewer");
        assertThrows(SecurityException.class,()->lifecycle.approve(f.siteId(),f.blueprintId(),1,f.selected()));
        SecurityContextHolder.clearContext();
        assertThrows(SecurityException.class,()->lifecycle.approve(f.siteId(),f.blueprintId(),1,f.selected()));

        String foreignSite="foreign-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(foreignSite,10));
        members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),
                foreignSite,"reviewer",SiteMemberEntity.Role.REVIEWER));
        actor("reviewer");
        assertThrows(SecurityException.class,()->lifecycle.approve(foreignSite,f.blueprintId(),1,f.selected()));
    }

    @Test void elevenActiveLinesAreRejectedWithoutTouchingPreviousActivation(){
        Fixture f=setup(99);
        Set<String> allEleven=new TreeSet<>(f.selected());
        allEleven.add("LINE-010");
        actor("reviewer");
        var fail=assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.approve(f.siteId(),f.blueprintId(),1,allEleven));
        assertTrue(fail.getMessage().contains("ACTIVE_LINE_QUOTA_EXCEEDED"));
        assertFalse(approvals.existsById(f.blueprintId()+":1"));
        lifecycle.approve(f.siteId(),f.blueprintId(),1,f.selected());
        actor("publisher");
        lifecycle.publish(f.siteId(),f.blueprintId(),1);
        actor("activator");
        String first=lifecycle.activate(f.siteId(),f.blueprintId(),1,null);
        assertEquals(first,lifecycle.active(f.siteId()).id());
        assertEquals(1,activationSets.count());
    }

    @Test void twoActivatorsWithSamePreviousPointerCannotBothActivate() throws Exception {
        Fixture f=setup(10);
        actor("reviewer"); lifecycle.approve(f.siteId(),f.blueprintId(),1,f.selected());
        actor("publisher"); lifecycle.publish(f.siteId(),f.blueprintId(),1);
        SecurityContextHolder.clearContext();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<String>> runs=new ArrayList<>();
            for(int i=0;i<2;i++)runs.add(pool.submit(()->{
                actor("activator");
                try{
                    ready.countDown();
                    if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");
                    try{lifecycle.activate(f.siteId(),f.blueprintId(),1,null);return "OK";}
                    catch(BlueprintLifecycleException e){return e.getMessage();}
                } finally {SecurityContextHolder.clearContext();}
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            List<String> outcomes=new ArrayList<>();
            for(Future<String> fut:runs)outcomes.add(fut.get(40,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"OK"),outcomes.toString());
            assertEquals(1,Collections.frequency(outcomes,"STALE_ACTIVATION_SET"),outcomes.toString());
            assertEquals(1,activationSets.count());
            actor("viewer");
            assertNotNull(lifecycle.active(f.siteId()).id());
        } finally {pool.shutdownNow();}
    }
}
