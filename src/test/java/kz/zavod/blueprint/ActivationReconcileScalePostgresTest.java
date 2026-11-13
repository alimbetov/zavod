package kz.zavod.blueprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC+JPA replay latency with 10,000 and 100,000 valid linked ledger rows.
 * Prints p95/p99 (50 samples) for CI evidence. Never run against production.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ActivationReconcileScalePostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("g42scale").withUsername("zavod").withPassword("test");
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
    @Autowired BusinessTransitionRepository ledger;
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    private void actor(String a){
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(a,"test",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    @Test void reconciliationRemainsBoundedAt10kAnd100kLinkedEvents() throws Exception {
        String site="scale-"+UUID.randomUUID();
        UUID blueprint=UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        ownership.saveAndFlush(new BlueprintSiteEntity(blueprint.toString(),site));
        for(var entry:Map.of("reviewer",SiteMemberEntity.Role.REVIEWER,
                "publisher",SiteMemberEntity.Role.PUBLISHER,
                "activator",SiteMemberEntity.Role.ACTIVATOR).entrySet())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),site,
                    entry.getKey(),entry.getValue()));
        var e=new Equipment("tank","water-tank",1,
                List.of(new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var u=new ProcessUnit("L1",UnitKind.LINE,"test.water-storage",
                List.of(e),List.of(),List.of(),List.of(),List.of(),false);
        drafts.saveDraft(new ProcessBlueprint(blueprint,1,10,List.of(u),
                List.of(),List.of(),List.of()),0);
        actor("reviewer");lifecycle.approve(site,blueprint,1,Set.of("L1"));
        actor("publisher");lifecycle.publish(site,blueprint,1);
        actor("activator");
        var receipt=commands.activate(site,"scale-key",blueprint,1,null);
        String expected=receipt.activationSetId();
        assertEquals(3,ledger.findBySiteIdOrderBySequenceNoAsc(site).size());
        var prev=ledger.findTopBySiteIdOrderBySequenceNoDesc(site).orElseThrow();
        insertEvents(site,blueprint.toString(),prev.contentHash(),prev.sequenceNo()+1,
                10_000-prev.sequenceNo(),prev.eventHash());
        measure(site,expected,10_000);
        var previous=ledger.findTopBySiteIdOrderBySequenceNoDesc(site).orElseThrow();
        insertEvents(site,blueprint.toString(),previous.contentHash(),previous.sequenceNo()+1,
                100_000-previous.sequenceNo(),previous.eventHash());
        measure(site,expected,100_000);
    }
    private void measure(String site,String expected,int ledgerSize) {
        assertEquals(ledgerSize,ledger.countBySiteId(site));
        var samples=new ArrayList<Long>();
        for(int i=0;i<50;i++){
            long start=System.nanoTime();
            var observation=commands.observe(site,"scale-key");
            assertEquals(ActivationCommandService.OutcomeState.COMMITTED,observation.state());
            assertEquals(expected,observation.activationSetId());
            assertEquals(expected,commands.reconcile(site,"scale-key").activationSetId());
            var replay=commands.activate(site,"scale-key",currentBlueprint,1,null);
            assertTrue(replay.replayed());
            assertEquals(expected,replay.activationSetId());
            samples.add((System.nanoTime()-start)/1_000_000L);
        }
        samples.sort(Long::compareTo);
        long p95=samples.get(47),p99=samples.get(49);
        System.out.printf("BCQ_G42_SCALE_PASS events=%d samples=50 triplet_observe_reconcile_replay_p95_ms=%d p99_ms=%d%n",
                ledgerSize,p95,p99);
        assertTrue(p99<3_000,"100K event replay must not do full-history verification");
        assertEquals(ledgerSize,ledger.countBySiteId(site));
        assertEquals(1,receipts.countBySiteId(site));
    }
    private UUID currentBlueprint;
    private void insertEvents(String site,String blueprint,String contentHash,long first,
                              long count,String previousHash) throws Exception {
        currentBlueprint=UUID.fromString(blueprint);
        try(var connection=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())){
            connection.setAutoCommit(false);
            String sql="INSERT INTO business_transition "+
                    "(id,site_id,sequence_no,event_type,actor_id,blueprint_id,revision_no,"+
                    "previous_set_id,new_set_id,reason,reason_code,correlation_id,command_key,"+
                    "content_hash,previous_event_hash,event_hash,created_at) "+
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try(var statement=connection.prepareStatement(sql)){
                for(long n=0;n<count;n++){
                    long seq=first+n;
                    String id=UUID.randomUUID().toString();
                    Instant when=Instant.now().truncatedTo(ChronoUnit.MICROS);
                    String eventHash=fingerprint(id,site,Long.toString(seq),"PUBLISH","publisher",
                            blueprint,"1",null,null,null,null,"PUBLISH_TRANSITION",id,
                            contentHash,previousHash,when.toString());
                    statement.setString(1,id);
                    statement.setString(2,site);
                    statement.setLong(3,seq);
                    statement.setString(4,"PUBLISH");
                    statement.setString(5,"publisher");
                    statement.setString(6,blueprint);
                    statement.setLong(7,1);
                    statement.setString(8,null);
                    statement.setString(9,null);
                    statement.setString(10,null);
                    statement.setString(11,"PUBLISH_TRANSITION");
                    statement.setString(12,id);
                    statement.setString(13,null);
                    statement.setString(14,contentHash);
                    statement.setString(15,previousHash);
                    statement.setString(16,eventHash);
                    statement.setTimestamp(17,Timestamp.from(when));
                    statement.addBatch();
                    previousHash=eventHash;
                    if((n+1)%1000==0){statement.executeBatch();connection.commit();}
                }
                statement.executeBatch();connection.commit();
            }
        }
    }
    private static String fingerprint(String... fields) throws Exception {
        var input=new StringBuilder("ZAVOD-G4.2-LEDGER-V1|");
        for(var f:fields) {
            if(f==null)input.append("N|");
            else input.append("V").append(f.length()).append(':').append(f).append('|');
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
