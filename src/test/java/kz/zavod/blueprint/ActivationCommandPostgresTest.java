package kz.zavod.blueprint;

import java.util.*;
import java.util.concurrent.*;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ActivationCommandPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("g4").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){
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
    @Autowired ActivationCommandRepository receipts;
    @Autowired ActivationTransitionRepository transitions;
    @Autowired PlantActivationSetRepository sets;
    @Autowired PlatformTransactionManager transactionManager;
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    private void actor(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username,"unused",
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    private record Fixture(String site,UUID blueprint) {}
    private Fixture published(){
        String site="site-"+UUID.randomUUID();
        UUID blueprint=UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        ownership.saveAndFlush(new BlueprintSiteEntity(blueprint.toString(),site));
        for(var e:Map.of("reviewer",SiteMemberEntity.Role.REVIEWER,
                "publisher",SiteMemberEntity.Role.PUBLISHER,
                "activator",SiteMemberEntity.Role.ACTIVATOR).entrySet())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),
                    site,e.getKey(),e.getValue()));
        var tank=new Equipment("tank","water-tank",1,
                List.of(new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var unit=new ProcessUnit("L1",UnitKind.LINE,"test.water-storage",
                List.of(tank),List.of(),List.of(),List.of(),List.of(),false);
        drafts.saveDraft(new ProcessBlueprint(blueprint,1,10,List.of(unit),
                List.of(),List.of(),List.of()),0);
        actor("reviewer");
        lifecycle.approve(site,blueprint,1,Set.of("L1"));
        actor("publisher");
        lifecycle.publish(site,blueprint,1);
        actor("activator");
        return new Fixture(site,blueprint);
    }

    @Test void sameKeyReplaysExactlySameCommittedActivationWithoutDuplicateAudit() {
        var f=published();
        var initial=commands.activate(f.site(),"command-1",f.blueprint(),1,null);
        assertFalse(initial.replayed());
        var afterLostHttpResponse=commands.activate(f.site(),"command-1",f.blueprint(),1,null);
        assertTrue(afterLostHttpResponse.replayed());
        assertEquals(initial.activationSetId(),afterLostHttpResponse.activationSetId());
        assertEquals(initial.activationSetId(),
                commands.reconcile(f.site(),"command-1").activationSetId());
        assertEquals(1,receipts.countBySiteId(f.site()));
        assertEquals(1,transitions.countBySiteId(f.site()));
        assertEquals(1,sets.countBySiteId(f.site()));
        assertThrows(BlueprintLifecycleException.class,()->commands.activate(
                f.site(),"command-1",f.blueprint(),1,"another-set"));
        assertEquals(1,receipts.countBySiteId(f.site()));
    }

    @Test void lostCommitAcknowledgementCanBeReconciledWithoutDoubleExecution() {
        var f=published();
        // Fault injection: a transport/ack failure after DB commit, not a real TCP cut.
        // TransactionSynchronization.afterCommit runs after persistence is durable.
        var transaction=new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{
            var receipt=commands.activate(f.site(),"ack-lost",f.blueprint(),1,null);
            assertFalse(receipt.replayed());
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        throw new IllegalStateException("SIMULATED_LOST_COMMIT_ACK");
                    }
                });
            return receipt;
        }));
        var committed=commands.reconcile(f.site(),"ack-lost");
        assertTrue(committed.replayed());
        assertEquals(committed.activationSetId(),
            commands.activate(f.site(),"ack-lost",f.blueprint(),1,null).activationSetId());
        assertEquals(1,receipts.countBySiteId(f.site()));
        assertEquals(1,transitions.countBySiteId(f.site()));
        assertEquals(1,sets.countBySiteId(f.site()));
    }

    @Test void concurrentIdenticalCommandsProduceOneStateTransition() throws Exception {
        var f=published();
        var pool=Executors.newFixedThreadPool(3);
        var gate=new CountDownLatch(1);
        try {
            List<Future<ActivationCommandService.Receipt>> calls=new ArrayList<>();
            for(int n=0;n<3;n++)calls.add(pool.submit(()->{
                actor("activator");gate.await();
                try{return commands.activate(f.site(),"same-key",f.blueprint(),1,null);}
                finally {SecurityContextHolder.clearContext();}
            }));
            gate.countDown();
            var results=new ArrayList<ActivationCommandService.Receipt>();
            for(var future:calls)results.add(future.get(30,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(x->!x.replayed()).count());
            assertEquals(2,results.stream().filter(ActivationCommandService.Receipt::replayed).count());
            assertEquals(1,results.stream().map(ActivationCommandService.Receipt::activationSetId).distinct().count());
            assertEquals(1,transitions.countBySiteId(f.site()));
            assertEquals(1,sets.countBySiteId(f.site()));
        } finally {pool.shutdownNow();}
    }

    @Test void nonCommittedOrUnauthorizedCommandNeverClaimsSuccess(){
        var f=published();
        assertThrows(BlueprintLifecycleException.class,()->commands.reconcile(f.site(),"missing"));
        actor("reviewer");
        assertThrows(SecurityException.class,()->commands.activate(f.site(),"blocked",f.blueprint(),1,null));
        assertEquals(0,receipts.countBySiteId(f.site()));
        assertEquals(0,transitions.countBySiteId(f.site()));
        assertEquals(0,sets.countBySiteId(f.site()));
    }
}
