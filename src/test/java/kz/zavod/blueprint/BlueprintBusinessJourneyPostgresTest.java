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
    void revokedPublicationCannotBeActivatedAndKeepsApprovedHistory() {
        Fixture f=setup(3);
        actor("reviewer"); lifecycle.approve(f.siteId(),f.id(),2,f.selected());
        actor("publisher"); lifecycle.publish(f.siteId(),f.id(),2);
        lifecycle.revoke(f.siteId(),f.id(),2,"Engineering risk identified");
        assertEquals(BlueprintApprovalEntity.State.REVOKED,
                approvals.findById(f.id()+":2").orElseThrow().state());
        actor("activator");
        assertEquals("NOT_PUBLISHED",assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.activate(f.siteId(),f.id(),2,null)).getMessage());
        assertNull(sites.findById(f.siteId()).orElseThrow().activeSetId());
        assertEquals(0,sets.countBySiteId(f.siteId()));
        assertEquals(f.snapshot(),drafts.loadRevision(f.id(),2));
        actor("publisher");
        assertThrows(BlueprintLifecycleException.class,()->lifecycle.publish(f.siteId(),f.id(),2));
        assertThrows(BlueprintLifecycleException.class,()->lifecycle.revoke(f.siteId(),f.id(),2,"again"));
    }

    @Test
    void cannotRevokeCurrentlyActiveBlueprintUntilReplaced() {
        Fixture f=setup(3);
        actor("reviewer"); lifecycle.approve(f.siteId(),f.id(),2,f.selected());
        actor("publisher"); lifecycle.publish(f.siteId(),f.id(),2);
        actor("activator"); String current=lifecycle.activate(f.siteId(),f.id(),2,null);
        actor("publisher");
        assertEquals("ACTIVE_PUBLICATION_CANNOT_REVOKE",assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.revoke(f.siteId(),f.id(),2,"Safety concern")).getMessage());
        assertEquals(BlueprintApprovalEntity.State.PUBLISHED,
                approvals.findById(f.id()+":2").orElseThrow().state());
        assertEquals(current,sites.findById(f.siteId()).orElseThrow().activeSetId());
    }

    @Test
    void restorePreviouslyActivatedRevisionCreatesNewHistoricalSetAndChecksPointer() {
        Fixture f=setup(3);
        actor("reviewer"); lifecycle.approve(f.siteId(),f.id(),2,f.selected());
        actor("publisher"); lifecycle.publish(f.siteId(),f.id(),2);
        actor("activator"); String original=lifecycle.activate(f.siteId(),f.id(),2,null);
        var revision3=new ProcessBlueprint(f.id(),3,10,f.snapshot().units(),
                f.snapshot().sharedAssets(),f.snapshot().transfers(),f.snapshot().dependencies());
        studio.saveDraft(f.siteId(),f.id(),2,revision3);
        actor("reviewer"); lifecycle.approve(f.siteId(),f.id(),3,f.selected());
        actor("publisher"); lifecycle.publish(f.siteId(),f.id(),3);
        actor("activator"); String upgraded=lifecycle.activate(f.siteId(),f.id(),3,original);
        assertEquals("STALE_ACTIVATION_SET",assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.restore(f.siteId(),original,original,"Wrong pointer")).getMessage());
        String restored=lifecycle.restore(f.siteId(),original,upgraded,"Rollback after regression");
        assertNotEquals(original,restored);
        assertNotEquals(upgraded,restored);
        assertEquals(3,sets.countBySiteId(f.siteId()));
        assertEquals(2,lifecycle.active(f.siteId()).revisionNo());
        assertEquals(upgraded,lifecycle.active(f.siteId()).previousSetId());
        assertEquals(original,lifecycle.active(f.siteId()).restoredFromSetId());
        assertEquals("Rollback after regression",lifecycle.active(f.siteId()).changeReason());
        assertEquals("ALREADY_ACTIVE",assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.restore(f.siteId(),original,restored,"duplicate")).getMessage());
        assertEquals(f.snapshot(),drafts.loadRevision(f.id(),2));
        actor("publisher");
        lifecycle.revoke(f.siteId(),f.id(),3,"Bad revision");
        actor("activator");
        assertEquals("NOT_PUBLISHED",assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.restore(f.siteId(),upgraded,restored,"revoked target")).getMessage());
    }

    @Test
    void sameActorCannotApproveAndPublishEvenWithAdministratorRole() {
        Fixture f=setup(3);
        members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),
                f.siteId(),"engineer-admin",SiteMemberEntity.Role.ADMIN));
        actor("engineer-admin");
        lifecycle.approve(f.siteId(),f.id(),2,f.selected());
        var refusal=assertThrows(BlueprintLifecycleException.class,
                ()->lifecycle.publish(f.siteId(),f.id(),2));
        assertEquals("FOUR_EYES_REQUIRED",refusal.getMessage());
        assertEquals(BlueprintApprovalEntity.State.APPROVED,
                approvals.findById(f.id()+":2").orElseThrow().state());
        actor("publisher");
        lifecycle.publish(f.siteId(),f.id(),2);
        assertEquals(BlueprintApprovalEntity.State.PUBLISHED,
                approvals.findById(f.id()+":2").orElseThrow().state());
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
    void databaseRejectsFinalSitePointerAndActivationInsertRollsBackAtomically() {
        Fixture f=setup(3);
        actor("reviewer");lifecycle.approve(f.siteId(),f.id(),2,f.selected());
        actor("publisher");lifecycle.publish(f.siteId(),f.id(),2);
        actor("activator");
        // Deterministic PostgreSQL failpoint: forbid any update to active_set_id.
        // ActivationSet insert flushes first, then site pointer update must fail,
        // forcing the entire transaction to roll back.
        jdbc.execute("alter table site add constraint bcq_fault_active_pointer check ("+
                "site_id <> '"+f.siteId()+"' or active_set_id is null)");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    ()->lifecycle.activate(f.siteId(),f.id(),2,null));
            assertEquals(0,sets.countBySiteId(f.siteId()),
                    "No orphan ActivationSet is allowed after failed final pointer commit");
            assertNull(sites.findById(f.siteId()).orElseThrow().activeSetId(),
                    "The last good Site configuration must survive transaction rollback");
            assertEquals(BlueprintApprovalEntity.State.PUBLISHED,
                    approvals.findById(f.id()+":2").orElseThrow().state());
        } finally {
            jdbc.execute("alter table site drop constraint bcq_fault_active_pointer");
        }
        String afterRecovery=lifecycle.activate(f.siteId(),f.id(),2,null);
        assertEquals(afterRecovery,lifecycle.active(f.siteId()).id());
        assertEquals(1,sets.countBySiteId(f.siteId()));
    }

    @Test
    void corruptedActivePointerAcrossSitesIsAlwaysRejected() {
        Fixture owner=setup(1);
        Fixture foreign=setup(1);
        actor("reviewer");
        lifecycle.approve(foreign.siteId(),foreign.id(),2,foreign.selected());
        lifecycle.approve(owner.siteId(),owner.id(),2,owner.selected());
        actor("publisher");
        lifecycle.publish(foreign.siteId(),foreign.id(),2);
        lifecycle.publish(owner.siteId(),owner.id(),2);
        actor("activator");
        String foreignSet=lifecycle.activate(foreign.siteId(),foreign.id(),2,null);
        assertEquals(1,sets.countBySiteId(foreign.siteId()));

        // A buggy database restore or manual SQL repair points owner's Site to
        // a perfectly valid but FOREIGN immutable activation set.
        assertEquals(1,jdbc.update("update site set active_set_id=? where site_id=?",
                foreignSet,owner.siteId()));
        actor("viewer");
        assertEquals("INVALID_ACTIVATION_POINTER",assertThrows(
                BlueprintLifecycleException.class,
                ()->lifecycle.active(owner.siteId())).getMessage(),
                "OI-011: view must not disclose cross-site active selection");
        actor("activator");
        assertEquals("INVALID_ACTIVATION_POINTER",assertThrows(
                BlueprintLifecycleException.class,
                ()->lifecycle.activate(owner.siteId(),owner.id(),2,foreignSet)).getMessage(),
                "OI-011: corrupted pointer must not become previousSetId in new chain");
        actor("publisher");
        assertEquals("INVALID_ACTIVATION_POINTER",assertThrows(
                BlueprintLifecycleException.class,
                ()->lifecycle.revoke(owner.siteId(),owner.id(),2,"Invalid old pointer")).getMessage());
        actor("activator");
        assertEquals("INVALID_ACTIVATION_POINTER",assertThrows(
                BlueprintLifecycleException.class,
                ()->lifecycle.restore(owner.siteId(),foreignSet,foreignSet,"Invalid restore")).getMessage());
        assertEquals(0,sets.countBySiteId(owner.siteId()));
        assertEquals(foreignSet,sites.findById(owner.siteId()).orElseThrow().activeSetId());
        assertEquals(1,sets.countBySiteId(foreign.siteId()));
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
