package kz.zavod.blueprint;

import java.util.*;
import java.sql.DriverManager;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired JdbcTemplate jdbc;
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
        assertEquals(ActivationCommandService.OutcomeState.COMMITTED,
                commands.observe(f.site(),"command-1").state());
        assertEquals(1,receipts.countBySiteId(f.site()));
        assertEquals(1,transitions.countBySiteId(f.site()));
        assertEquals(1,sets.countBySiteId(f.site()));
        assertThrows(BlueprintLifecycleException.class,()->commands.activate(
                f.site(),"command-1",f.blueprint(),1,"another-set"));
        assertThrows(BlueprintLifecycleException.class,()->commands.activate(
                f.site(),"command-1",f.blueprint(),1,"<none>"));
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

    @Test void realPostgresConnectionTerminationBeforeCommitRollsBackCommandAndAudit() {
        var f=published();
        var transaction=new TransactionTemplate(transactionManager);
        assertThrows(RuntimeException.class,()->transaction.execute(status->{
            int targetPid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
            commands.activate(f.site(),"interrupted-before-commit",f.blueprint(),1,null);
            // Separate PostgreSQL session terminates the actual transaction connection.
            // This is a genuine DB disconnect BEFORE COMMIT, not a lost COMMIT ACK.
            try(var other=DriverManager.getConnection(pg.getJdbcUrl(),
                    pg.getUsername(),pg.getPassword());
                var statement=other.prepareStatement("select pg_terminate_backend(?)")) {
                statement.setInt(1,targetPid);
                try(var response=statement.executeQuery()) {
                    assertTrue(response.next());
                    assertTrue(response.getBoolean(1));
                }
            } catch(java.sql.SQLException error) {
                throw new IllegalStateException("FAULT_INJECTION_SETUP_FAILED",error);
            }
            return null;
        }));
        assertThrows(BlueprintLifecycleException.class,
            ()->commands.reconcile(f.site(),"interrupted-before-commit"));
        assertEquals(0,receipts.countBySiteId(f.site()));
        assertEquals(0,transitions.countBySiteId(f.site()));
        assertEquals(0,sets.countBySiteId(f.site()));
        assertNull(sites.findById(f.site()).orElseThrow().activeSetId());
    }

    @Test void observeWhileTransactionIsInFlightNeverClaimsRollback() throws Exception {
        var f=published();
        var pool=Executors.newSingleThreadExecutor();
        var recorded=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try {
            Future<String> task=pool.submit(()->{
                actor("activator");
                try {
                    var tx=new TransactionTemplate(transactionManager);
                    return tx.execute(status->{
                        String id=commands.activate(f.site(),"pending-commit",f.blueprint(),1,null)
                                .activationSetId();
                        recorded.countDown(); // not committed yet
                        try {
                            if(!release.await(20,TimeUnit.SECONDS))
                                throw new IllegalStateException("TEST_GATE_TIMEOUT");
                        } catch(InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("TEST_INTERRUPTED",e);
                        }
                        return id;
                    });
                } finally {SecurityContextHolder.clearContext();}
            });
            assertTrue(recorded.await(15,TimeUnit.SECONDS));
            var pending=commands.observe(f.site(),"pending-commit");
            assertEquals(ActivationCommandService.OutcomeState.NOT_OBSERVED_RETRY,pending.state());
            assertNull(pending.activationSetId());
            release.countDown();
            String id=task.get(25,TimeUnit.SECONDS);
            assertEquals(id,commands.observe(f.site(),"pending-commit").activationSetId());
            assertEquals(1,receipts.countBySiteId(f.site()));
            assertEquals(1,sets.countBySiteId(f.site()));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Real PostgreSQL fault inside COMMIT, not an afterCommit Java callback:
     * a DEFERRABLE constraint trigger runs when the transaction attempts COMMIT.
     * A separate physical JDBC session sees that COMMIT suspended in PostgreSQL
     * and terminates its backend. This proves the pre-durable branch, NOT the
     * post-durable/lost-ACK branch of the ambiguous-commit window.
     */
    @Test void terminateRealPostgresBackendWhileCommitRunsThenRetryExactlyOnce() throws Exception {
        var f=published();
        jdbc.execute("create or replace function g42_suspend_commit() returns trigger as $ "+
                "begin perform set_config('application_name','zavod-g42-commit-inflight',true); "+
                "perform pg_sleep(12); return NEW; end; $ language plpgsql");
        jdbc.execute("create constraint trigger g42_suspend_activation "+
                "after insert on activation_command deferrable initially deferred "+
                "for each row execute function g42_suspend_commit()");
        var worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> attempt=worker.submit(()->{
                actor("activator");
                try {commands.activate(f.site(),"cut-during-commit",f.blueprint(),1,null);}
                finally {SecurityContextHolder.clearContext();}
            });
            int targetPid=0;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            try(var observer=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
                var check=observer.prepareStatement(
                    "select pid from pg_stat_activity where datname=current_database() "+
                    "and application_name='zavod-g42-commit-inflight' "+
                    "and state='active' and query ilike '%COMMIT%'")) {
                while(System.nanoTime()<deadline && targetPid==0) {
                    try(var rs=check.executeQuery()) {if(rs.next())targetPid=rs.getInt(1);}
                    if(targetPid==0)Thread.sleep(30);
                }
                assertTrue(targetPid>0,"PostgreSQL COMMIT must be observed inside deferred trigger");
                try(var kill=observer.prepareStatement("select pg_terminate_backend(?)")) {
                    kill.setInt(1,targetPid);
                    try(var result=kill.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1));
                    }
                }
            } finally {
                // Release fixture regardless of failure; no leaks to other tests.
                jdbc.execute("drop trigger if exists g42_suspend_activation on activation_command");
                jdbc.execute("drop function if exists g42_suspend_commit()");
            }
            assertThrows(ExecutionException.class,()->attempt.get(20,TimeUnit.SECONDS));
            assertEquals(ActivationCommandService.OutcomeState.NOT_OBSERVED_RETRY,
                    commands.observe(f.site(),"cut-during-commit").state());
            assertEquals(0,receipts.countBySiteId(f.site()));
            assertEquals(0,transitions.countBySiteId(f.site()));
            assertEquals(0,sets.countBySiteId(f.site()));
            actor("activator");
            var retried=commands.activate(f.site(),"cut-during-commit",f.blueprint(),1,null);
            assertFalse(retried.replayed());
            assertEquals(retried.activationSetId(),
                    commands.observe(f.site(),"cut-during-commit").activationSetId());
            assertTrue(commands.activate(f.site(),"cut-during-commit",f.blueprint(),1,null).replayed());
            assertEquals(1,receipts.countBySiteId(f.site()));
            assertEquals(1,transitions.countBySiteId(f.site()));
            assertEquals(1,sets.countBySiteId(f.site()));
        } finally {
            worker.shutdownNow();
            jdbc.execute("drop trigger if exists g42_suspend_activation on activation_command");
            jdbc.execute("drop function if exists g42_suspend_commit()");
        }
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

    @Test void corruptedAuditActorBlocksReconciliationAndReplay() {
        var f=published();
        var successful=commands.activate(f.site(),"tamper-receipt",f.blueprint(),1,null);
        assertFalse(successful.replayed());
        jdbc.update("update activation_transition set actor_id=? where site_id=?",
                "forged-actor",f.site());
        assertThrows(IllegalStateException.class,
                ()->commands.reconcile(f.site(),"tamper-receipt"));
        assertThrows(IllegalStateException.class,
                ()->commands.activate(f.site(),"tamper-receipt",f.blueprint(),1,null));
        assertEquals(1,sets.countBySiteId(f.site()));
        assertEquals(1,receipts.countBySiteId(f.site()));
    }

    @Test void nonCommittedOrUnauthorizedCommandNeverClaimsSuccess(){
        var f=published();
        assertEquals(ActivationCommandService.OutcomeState.NOT_OBSERVED_RETRY,
                commands.observe(f.site(),"missing").state());
        assertNull(commands.observe(f.site(),"missing").activationSetId());
        assertThrows(BlueprintLifecycleException.class,()->commands.reconcile(f.site(),"missing"));
        actor("reviewer");
        assertThrows(SecurityException.class,()->commands.activate(f.site(),"blocked",f.blueprint(),1,null));
        assertEquals(0,receipts.countBySiteId(f.site()));
        assertEquals(0,transitions.countBySiteId(f.site()));
        assertEquals(0,sets.countBySiteId(f.site()));
    }
}
