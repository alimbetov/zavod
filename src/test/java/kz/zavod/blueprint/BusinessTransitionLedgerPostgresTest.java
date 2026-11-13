package kz.zavod.blueprint;

import java.util.*;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** RED contract: each committed lifecycle transition requires matching durable business evidence. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BusinessTransitionLedgerPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.6")
        .withDatabaseName("g42ledger").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",pg::getJdbcUrl);
        p.add("spring.datasource.username",pg::getUsername);
        p.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired SiteRepository sites;
    @Autowired SiteMemberRepository members;
    @Autowired BlueprintSiteRepository ownership;
    @Autowired BlueprintDraftService drafts;
    @Autowired BlueprintLifecycleService lifecycle;
    @Autowired ActivationCommandService commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessTransitionRepository ledger;
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    private void actor(String name) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(name,"unused",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    private record Fixture(String site,UUID blueprint) {}
    private Fixture setup() {
        var site="ledger-"+UUID.randomUUID();
        var id=UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        ownership.saveAndFlush(new BlueprintSiteEntity(id.toString(),site));
        for(var entry:Map.of("reviewer",SiteMemberEntity.Role.REVIEWER,
            "publisher",SiteMemberEntity.Role.PUBLISHER,
            "activator",SiteMemberEntity.Role.ACTIVATOR).entrySet())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),
                site,entry.getKey(),entry.getValue()));
        var e=new Equipment("tank","water-tank",1,
            List.of(new Port("out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var u=new ProcessUnit("L1",UnitKind.LINE,"test.water-storage",
            List.of(e),List.of(),List.of(),List.of(),List.of(),false);
        drafts.saveDraft(new ProcessBlueprint(id,1,10,List.of(u),List.of(),List.of(),List.of()),0);
        return new Fixture(site,id);
    }
    @Test void approvePublishActivateAndRevokeAllLeaveOrderedImmutableEvidence() {
        var f=setup();
        actor("reviewer");
        lifecycle.approve(f.site(),f.blueprint(),1,Set.of("L1"));
        actor("publisher");
        lifecycle.publish(f.site(),f.blueprint(),1);
        actor("activator");
        String result=commands.activate(f.site(),"ledger-command",f.blueprint(),1,null);
        var rows=ledger.findBySiteIdOrderBySequenceNoAsc(f.site());
        assertEquals(List.of("APPROVE","PUBLISH","ACTIVATE"),
            rows.stream().map(r->r.eventType().name()).toList());
        assertEquals(List.of("reviewer","publisher","activator"),
            rows.stream().map(BusinessTransitionEntity::actorId).toList());
        assertEquals(result,rows.get(2).newSetId());
        assertEquals("ledger-command",rows.get(2).commandKey());
        assertEquals(3,rows.stream().map(BusinessTransitionEntity::contentHash).distinct().count()==1?3:0);
        assertEquals(3,rows.stream().map(BusinessTransitionEntity::eventHash).distinct().count());
        assertEquals(0,rows.get(0).previousEventHash().length());
        assertEquals(rows.get(0).eventHash(),rows.get(1).previousEventHash());
        assertEquals(rows.get(1).eventHash(),rows.get(2).previousEventHash());
        // Cannot revoke an active version, and rejected operation must leave no event.
        actor("publisher");
        assertThrows(BlueprintLifecycleException.class,
            ()->lifecycle.revoke(f.site(),f.blueprint(),1,"unsafe"));
        assertEquals(3,ledger.findBySiteIdOrderBySequenceNoAsc(f.site()).size());
    }
    @Test void journalInsertFailureRollsBackApprovalAtomically() {
        var f=setup();
        actor("reviewer");
        jdbc.execute("create or replace function g42_reject_ledger() returns trigger as $$ "+
            "begin raise exception 'intentional ledger rejection'; end; $$ language plpgsql");
        jdbc.execute("create trigger g42_reject before insert on business_transition "+
            "for each row execute function g42_reject_ledger()");
        try {
            assertThrows(RuntimeException.class,()->lifecycle.approve(
                f.site(),f.blueprint(),1,Set.of("L1")));
            assertEquals(0,ledger.findBySiteIdOrderBySequenceNoAsc(f.site()).size());
        } finally {
            jdbc.execute("drop trigger if exists g42_reject on business_transition");
            jdbc.execute("drop function if exists g42_reject_ledger()");
        }
        // A failed audit insert must not leave APPROVED business state.
        actor("publisher");
        assertThrows(BlueprintLifecycleException.class,
            ()->lifecycle.publish(f.site(),f.blueprint(),1));
    }
}
