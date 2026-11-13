package kz.zavod.blueprint;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import kz.zavod.blueprint.domain.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.engineering.*;
import kz.zavod.studio.StudioBlueprintService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static kz.zavod.blueprint.domain.ProcessBlueprint.*;
import static org.junit.jupiter.api.Assertions.*;

/** Complete server-side PostgreSQL business journey; explicitly no browser or actual PLC. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BlueprintBusinessJourneyPostgresTest {
    @Container
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("zavodjourney").withUsername("zavod").withPassword("zavodtest");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired SiteRepository sites;
    @Autowired SiteMemberRepository members;
    @Autowired StudioBlueprintService studio;
    @Autowired BlueprintDraftService drafts;
    @Autowired BlueprintLifecycleService lifecycle;
    @Autowired BlueprintApprovalRepository approvals;
    @Autowired PlantActivationSetRepository sets;
    @Autowired JdbcTemplate jdbc;
    private final EquipmentCatalog catalog=EquipmentCatalog.standard();

    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    private void actor(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username,"N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
    }

    private record Fixture(String siteId,UUID id,ProcessBlueprint snapshot,Set<String> selected) {}
    private Fixture setup(int lineCount) {
        String siteId="site-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(siteId,10));
        Map<String,SiteMemberEntity.Role> roles=Map.of(
                "reviewer",SiteMemberEntity.Role.REVIEWER,
                "publisher",SiteMemberEntity.Role.PUBLISHER,
                "activator",SiteMemberEntity.Role.ACTIVATOR,
                "viewer",SiteMemberEntity.Role.VIEWER);
        for(var entry:roles.entrySet())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),siteId,
                    entry.getKey(),entry.getValue()));
        ProcessBlueprint empty=studio.create(siteId);
        assertEquals(1,empty.revision());
        assertTrue(empty.units().isEmpty());
        List<ProcessUnit> units=new ArrayList<>();
        for(int i=0;i<lineCount;i++){
            String name=String.format(Locale.ROOT,"L%03d",i);
            if(i==37) {
                var tank=equipment("T037","water-tank",List.of());
                var p=equipment("P037","water-pump",List.of(ratedFlow("12")));
                var valve=equipment("V037","shutoff-valve",List.of());
                units.add(new ProcessUnit(name,UnitKind.LINE,"continuous.pump.v1",
                        List.of(tank,p,valve),
                        List.of(new Connection("T037","water-out","P037","water-in"),
                                new Connection("P037","water-out","V037","water-in")),
                        List.of(),List.of(),List.of(),false));
            } else {
                units.add(new ProcessUnit(name,UnitKind.LINE,"continuous.pump.v1",
                        List.of(equipment("T"+i,"water-tank",List.of())),
                        List.of(),List.of(),List.of(),List.of(),false));
            }
        }
        // Inter-line dependency is in an unselected DRAFT segment; it must be retained for Impact.
        var deps=lineCount>38?List.of(new Dependency("L037","L038")):List.<Dependency>of();
        ProcessBlueprint initial=new ProcessBlueprint(empty.id(),2,999,
                units,List.of(),List.of(),deps);
        ProcessBlueprint saved=studio.saveDraft(siteId,empty.id(),1,initial);
        assertEquals(10,saved.maxActiveLines(),"Site policy must not come from the submitted blueprint");
        assertTrue(BlueprintValidator.validate(saved).stream()
                .noneMatch(f->f.severity()==BlueprintValidator.Severity.ERROR));
        assertTrue(BlueprintCatalogAudit.inspect(saved,catalog).isEmpty());
        Set<String> selected=new TreeSet<>();
        for(int i=0;i<Math.min(10,lineCount);i++)
            selected.add(String.format(Locale.ROOT,"L%03d",i));
        return new Fixture(siteId,empty.id(),saved,selected);
    }
    private static EngineeringPropertyValue ratedFlow(String m3PerHour){
        return new EngineeringPropertyValue("rated_flow",new EngineeringQuantity(
                new BigDecimal(m3PerHour),"m3/h"),
                EngineeringPropertyValue.Source.MANUFACTURER,"vendor-manual/section-7");
    }
    private Equipment equipment(String id,String key,List<EngineeringPropertyValue> properties){
        var template=catalog.type(key,1);
        var ports=template.ports().stream().map(p->new Port(p.key(),
                Direction.valueOf(p.direction().name()),FlowKind.valueOf(p.streamKind().name()),
                p.medium(),p.minConnections(),p.maxConnections())).toList();
        return new Equipment(id,key,1,ports,properties);
    }

    @Test
    void createValidateSaveApprovePublishActivateReviseAndRetainHistoricalSet() {
        Fixture f=setup(99);
        actor("viewer");
        assertThrows(SecurityException.class,
                ()->lifecycle.approve(f.siteId(),f.id(),2,f.selected()));
        actor("reviewer");
        lifecycle.approve(f.siteId(),f.id(),2,f.selected());
        assertEquals(BlueprintApprovalEntity.State.APPROVED,
                approvals.findById(f.id()+":2").orElseThrow().state());
        actor("publisher");
        lifecycle.publish(f.siteId(),f.id(),2);
        assertEquals(BlueprintApprovalEntity.State.PUBLISHED,
                approvals.findById(f.id()+":2").orElseThrow().state());
        actor("activator");
        String first=lifecycle.activate(f.siteId(),f.id(),2,null);
        assertEquals(2,lifecycle.active(f.siteId()).revisionNo());
        assertEquals(first,lifecycle.active(f.siteId()).id());
        assertEquals("ALREADY_ACTIVE",assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.activate(f.siteId(),f.id(),2,first)).getMessage());
        assertEquals(1,sets.countBySiteId(f.siteId()));

        List<ProcessUnit> edited=new ArrayList<>(f.snapshot().units());
        ProcessUnit l037=edited.get(37);
        List<Equipment> devices=new ArrayList<>(l037.equipment());
        var originalPump=devices.get(1);
        devices.set(1,new Equipment(originalPump.id(),originalPump.typeKey(),
                originalPump.templateVersion(),originalPump.ports(),List.of(ratedFlow("15"))));
        edited.set(37,new ProcessUnit(l037.id(),l037.kind(),l037.profileKey(),
                devices,l037.connections(),l037.boundaries(),l037.resources(),
                l037.measurements(),l037.recirculationAllowed()));
        ProcessBlueprint revision3=new ProcessBlueprint(f.id(),3,999,edited,
                f.snapshot().sharedAssets(),f.snapshot().transfers(),f.snapshot().dependencies());
        ProcessBlueprint saved=studio.saveDraft(f.siteId(),f.id(),2,revision3);
        assertEquals(10,saved.maxActiveLines());
        assertEquals(Set.of("L037","L038"),
                BlueprintImpactAnalyzer.analyzeChange(f.snapshot(),saved).affectedUnits());
        assertEquals(2,lifecycle.active(f.siteId()).revisionNo(),
                "BR-024: saving a new draft cannot silently change active production config");
        assertEquals(f.snapshot(),drafts.loadRevision(f.id(),2),
                "BR-002: old approved engineering revision is immutable");
        actor("reviewer");
        lifecycle.approve(f.siteId(),f.id(),3,f.selected());
        actor("publisher");
        lifecycle.publish(f.siteId(),f.id(),3);
        actor("activator");
        String second=lifecycle.activate(f.siteId(),f.id(),3,first);
        assertNotEquals(first,second);
        assertEquals(3,lifecycle.active(f.siteId()).revisionNo());
        assertEquals(first,lifecycle.active(f.siteId()).previousSetId());
        assertEquals(2,sets.findById(first).orElseThrow().revisionNo());
        assertEquals(2,sets.countBySiteId(f.siteId()));
        assertEquals(saved,drafts.loadLatest(f.id()));
        assertEquals(f.snapshot(),drafts.loadRevision(f.id(),2));
    }

    @Test
    void invalidRevisionCannotDisplaceTheLastValidActivation() {
        Fixture f=setup(3);
        actor("reviewer");lifecycle.approve(f.siteId(),f.id(),2,f.selected());
        actor("publisher");lifecycle.publish(f.siteId(),f.id(),2);
        actor("activator");
        String active=lifecycle.activate(f.siteId(),f.id(),2,null);

        List<ProcessUnit> badUnits=new ArrayList<>(f.snapshot().units());
        ProcessUnit first=badUnits.get(0);
        badUnits.set(0,new ProcessUnit(first.id(),first.kind(),first.profileKey(),
                first.equipment(),first.connections(),
                List.of(new Boundary("broken",Direction.OUT,FlowKind.MATERIAL,"water",
                        "T0","nonexistent-port")),first.resources(),first.measurements(),false));
        studio.saveDraft(f.siteId(),f.id(),2,new ProcessBlueprint(f.id(),3,10,
                badUnits,f.snapshot().sharedAssets(),f.snapshot().transfers(),f.snapshot().dependencies()));
        actor("reviewer");
        assertTrue(assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.approve(f.siteId(),f.id(),3,f.selected()))
                .getMessage().contains("INVALID_BOUNDARY_BINDING"));
        actor("activator");
        assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.activate(f.siteId(),f.id(),3,active));
        assertEquals(active,lifecycle.active(f.siteId()).id());
        assertEquals(2,lifecycle.active(f.siteId()).revisionNo());
        assertEquals(1,sets.countBySiteId(f.siteId()));
        assertEquals(3,drafts.loadLatest(f.id()).revision());
        assertEquals(2,drafts.loadRevision(f.id(),2).revision());
    }

    @Test
    void concurrentApprovalIsExactlyOnceAndConflictIsDomainControlled() throws Exception {
        Fixture f=setup(3);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{
                actor("reviewer");
                try{
                    ready.countDown();
                    if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER_TIMEOUT");
                    try{
                        lifecycle.approve(f.siteId(),f.id(),2,f.selected());
                        return "SUCCESS";
                    } catch(BlueprintLifecycleException e) {
                        return e.getMessage();
                    }
                } finally {SecurityContextHolder.clearContext();}
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));
            start.countDown();
            List<String> outcomes=new ArrayList<>();
            for(var future:futures)outcomes.add(future.get(40,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"SUCCESS"),outcomes.toString());
            assertEquals(1,Collections.frequency(outcomes,"ALREADY_APPROVED"),outcomes.toString());
            assertEquals(1L,jdbc.queryForObject(
                    "select count(*) from blueprint_approval where blueprint_id=?",
                    Long.class,f.id().toString()));
        } finally {
            pool.shutdownNow();
        }
    }
}
