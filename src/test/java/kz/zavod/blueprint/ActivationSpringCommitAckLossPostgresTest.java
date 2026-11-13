package kz.zavod.blueprint;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import org.junit.jupiter.api.AfterAll;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * G4.2-E.1: actual Spring DataSource -> Hibernate/JPA -> transactional
 * ActivationCommandService over a real PostgreSQL wire-protocol proxy.
 * Drop ONLY the server's successful COMMIT CommandComplete packet after arming
 * the fault. After the client receives a failure, an independent DB connection
 * and the production reconciliation service must prove an atomic, durable
 * receipt, audit, ledger and site pointer with exactly-once key replay.
 *
 * Production PLC paths are NOT exercised or enabled.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class ActivationSpringCommitAckLossPostgresTest {
    private static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("spring_ack").withUsername("zavod").withPassword("test");
    private static final AckDropProxy PROXY;
    static {
        PG.start();
        try { PROXY=new AckDropProxy(PG.getHost(),PG.getMappedPort(5432)); }
        catch(IOException e) { PG.stop();throw new ExceptionInInitializerError(e); }
    }
    @AfterAll static void stop() throws Exception {PROXY.close();PG.stop();}
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+PROXY.port()
                +"/spring_ack?sslmode=disable&connectTimeout=5&socketTimeout=8");
        p.add("spring.datasource.username",PG::getUsername);
        p.add("spring.datasource.password",PG::getPassword);
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);
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
    @Autowired BusinessTransitionRepository ledger;

    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    private static void actor(String name) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(name,"test-only",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    @Test void actualSpringTransactionPersistsBeforeDroppedCommitAckAndReplaysOnce() throws Exception {
        String site="ack-spring-"+UUID.randomUUID();
        UUID blueprint=UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        ownership.saveAndFlush(new BlueprintSiteEntity(blueprint.toString(),site));
        for(var role:Map.of("reviewer",SiteMemberEntity.Role.REVIEWER,
                "publisher",SiteMemberEntity.Role.PUBLISHER,
                "activator",SiteMemberEntity.Role.ACTIVATOR).entrySet())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),
                    site,role.getKey(),role.getValue()));
        var equipment=new Equipment("tank","water-tank",1,
                List.of(new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var unit=new ProcessUnit("L1",UnitKind.LINE,"test.water-storage",
                List.of(equipment),List.of(),List.of(),List.of(),List.of(),false);
        drafts.saveDraft(new ProcessBlueprint(blueprint,1,10,List.of(unit),
                List.of(),List.of(),List.of()),0);
        actor("reviewer");
        lifecycle.approve(site,blueprint,1,Set.of("L1"));
        actor("publisher");
        lifecycle.publish(site,blueprint,1);
        actor("activator");

        String key="spring-durable-ack-drop";
        PROXY.arm();
        try {
            // Hibernate/Spring reports a failed COMMIT acknowledgement, even though
            // PostgreSQL has issued CommandComplete('COMMIT') for that transaction.
            assertThrows(RuntimeException.class,
                    ()->commands.activate(site,key,blueprint,1,null));
            assertTrue(PROXY.awaitIntercept(),
                    "must intercept REAL PostgreSQL COMMIT CommandComplete");
        } finally {PROXY.disarm();}

        // Verify on a separate direct PostgreSQL connection, not through Spring/Hikari.
        String actualSetId;
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),
                PG.getUsername(),PG.getPassword())) {
            try(var stmt=connection.prepareStatement(
                    "select result_set_id from activation_command where site_id=? and command_key=?")) {
                stmt.setString(1,site);stmt.setString(2,key);
                try(var rs=stmt.executeQuery()){
                    assertTrue(rs.next(),"receipt must be durable despite lost client ACK");
                    actualSetId=rs.getString(1);
                    assertFalse(rs.next());
                }
            }
            try(var stmt=connection.prepareStatement(
                    "select active_set_id from site where site_id=?")) {
                stmt.setString(1,site);
                try(var rs=stmt.executeQuery()){
                    assertTrue(rs.next());assertEquals(actualSetId,rs.getString(1));
                }
            }
            for(var table:List.of("activation_transition","plant_activation_set")) {
                // Read-only independent DB evidence; site is a test fixture value.
                try(var stmt=connection.prepareStatement(
                        "select count(*) from "+table+" where site_id=?")) {
                    stmt.setString(1,site);
                    try(var rs=stmt.executeQuery()){
                        assertTrue(rs.next());assertEquals(1,rs.getLong(1),table);
                    }
                }
            }
            try(var stmt=connection.prepareStatement(
                    "select count(*) from business_transition where site_id=? and event_type='ACTIVATE'")) {
                stmt.setString(1,site);
                try(var rs=stmt.executeQuery()){
                    assertTrue(rs.next());assertEquals(1,rs.getLong(1));
                }
            }
        }
        assertEquals(ActivationCommandService.OutcomeState.COMMITTED,
                commands.observe(site,key).state());
        assertEquals(actualSetId,commands.reconcile(site,key).activationSetId());
        var replay=commands.activate(site,key,blueprint,1,null);
        assertTrue(replay.replayed());
        assertEquals(actualSetId,replay.activationSetId());
        assertEquals(1,receipts.countBySiteId(site));
        assertEquals(1,transitions.countBySiteId(site));
        assertEquals(1,sets.countBySiteId(site));
        var evidence=ledger.findBySiteIdOrderBySequenceNoAsc(site);
        assertEquals(3,evidence.size());
        var activation=evidence.get(2);
        assertEquals(BusinessTransitionEntity.EventType.ACTIVATE,activation.eventType());
        assertEquals(key,activation.commandKey());
        assertEquals(actualSetId,activation.newSetId());
        assertEquals(64,activation.eventHash().length());
        assertEquals(64,activation.contentHash().length());
        assertFalse(activation.correlationId().isBlank());
        assertEquals("ACTIVATE_TRANSITION",activation.reasonCode());
        assertEquals(actualSetId,sites.findById(site).orElseThrow().activeSetId());
    }

    /**
     * All application connections traverse this proxy. The server response
     * protocol is framed; client->server startup/messages are streamed intact.
     */
    static final class AckDropProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final String host;
        private final int port;
        private final Thread acceptor;
        private final AtomicBoolean armed=new AtomicBoolean();
        private final AtomicBoolean dropped=new AtomicBoolean();
        private volatile CountDownLatch observed=new CountDownLatch(1);
        private final AtomicReference<Throwable> failure=new AtomicReference<>();
        AckDropProxy(String host,int port) throws IOException {
            this.host=host;this.port=port;
            listener=new ServerSocket(0,32,InetAddress.getLoopbackAddress());
            acceptor=new Thread(this::serve,"g42-spring-ack-proxy");
            acceptor.setDaemon(true);acceptor.start();
        }
        int port(){return listener.getLocalPort();}
        void arm(){failure.set(null);dropped.set(false);observed=new CountDownLatch(1);armed.set(true);}
        void disarm(){armed.set(false);}
        boolean awaitIntercept() throws InterruptedException {
            if(!observed.await(10,TimeUnit.SECONDS))return false;
            if(failure.get()!=null)throw new AssertionError("Proxy failure",failure.get());
            return dropped.get();
        }
        private void serve() {
            while(!listener.isClosed()) {
                try {
                    Socket client=listener.accept();
                    Thread worker=new Thread(()->relay(client),"g42-pg-relay");
                    worker.setDaemon(true);worker.start();
                } catch(IOException e) { if(!listener.isClosed())failure.compareAndSet(null,e); }
            }
        }
        private void relay(Socket client) {
            try(client;Socket backend=new Socket(host,port)) {
                client.setSoTimeout(30000);
                backend.setSoTimeout(30000);
                Thread forward=new Thread(()->{
                    try{client.getInputStream().transferTo(backend.getOutputStream());}
                    catch(IOException ignored){/* expected when fault closes JDBC connection */}
                },"g42-pg-sql-forward");
                forward.setDaemon(true);forward.start();
                var server=new DataInputStream(backend.getInputStream());
                var writer=new DataOutputStream(client.getOutputStream());
                while(true) {
                    int tag=server.read();
                    if(tag<0)break;
                    int len=server.readInt();
                    if(len<4||len>16*1024*1024)throw new IOException("Bad PostgreSQL frame length "+len);
                    byte[] payload=new byte[len-4];
                    server.readFully(payload);
                    if(tag=='C' && armed.get() && !dropped.get()
                            && new String(payload,StandardCharsets.UTF_8).equals("COMMIT\0")
                            && dropped.compareAndSet(false,true)) {
                        armed.set(false);
                        observed.countDown();
                        return; // client socket closes WITHOUT forwarding durable COMMIT ACK.
                    }
                    writer.writeByte(tag);
                    writer.writeInt(len);
                    writer.write(payload);
                    writer.flush();
                }
            } catch(IOException e) {
                if(armed.get()&&!dropped.get())failure.compareAndSet(null,e);
            }
        }
        @Override public void close() throws Exception {
            listener.close();
            acceptor.join(Duration.ofSeconds(2));
        }
    }
}
