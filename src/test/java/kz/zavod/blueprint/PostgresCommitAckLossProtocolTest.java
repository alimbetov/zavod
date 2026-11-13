package kz.zavod.blueprint;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G4.2-E: deterministic loss of server COMMIT acknowledgement.
 *
 * This is a REAL PostgreSQL wire-protocol fault: the proxy passes the
 * transaction to PostgreSQL, observes the server's CommandComplete("COMMIT")
 * (which PostgreSQL emits only after completing COMMIT), then closes the
 * client connection WITHOUT forwarding that packet.
 *
 * No production endpoints or PLC commands. The tables in this test are a
 * narrowly scoped atomicity model, NOT the full Spring lifecycle service.
 */
class PostgresCommitAckLossProtocolTest {
    @Test void durableCommitWithoutClientAckIsReconciledExactlyOnce() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16.6")
                .withDatabaseName("commit_ack").withUsername("zavod").withPassword("test")) {
            pg.start();
            try (Connection setup=DriverManager.getConnection(pg.getJdbcUrl(),
                    pg.getUsername(),pg.getPassword());
                 var sql=setup.createStatement()) {
                sql.execute("create table g42_pointer (site text primary key, value text)");
                sql.execute("create table g42_receipt (site text, command_key text, result text, "+
                        "primary key(site,command_key))");
                sql.execute("create table g42_audit (site text, command_key text, result text, "+
                        "primary key(site,command_key))");
                sql.execute("insert into g42_pointer(site,value) values ('site-1','none')");
            }

            try(var proxy=new CommitAckDropProxy(pg.getHost(),pg.getMappedPort(5432))) {
                String url="jdbc:postgresql://127.0.0.1:"+proxy.port()+
                        "/commit_ack?sslmode=disable&connectTimeout=5&socketTimeout=8";
                Properties auth=new Properties();
                auth.setProperty("user",pg.getUsername());
                auth.setProperty("password",pg.getPassword());
                try(Connection client=DriverManager.getConnection(url,auth)) {
                    client.setAutoCommit(false);
                    try(var stmt=client.createStatement()) {
                        stmt.executeUpdate("update g42_pointer set value='activation-1' "+
                                "where site='site-1'");
                        stmt.executeUpdate("insert into g42_receipt(site,command_key,result) "+
                                "values ('site-1','idempotency-1','activation-1')");
                        stmt.executeUpdate("insert into g42_audit(site,command_key,result) "+
                                "values ('site-1','idempotency-1','activation-1')");
                    }
                    // The backend successfully COMMITs but the client sees a connection failure.
                    assertThrows(SQLException.class,client::commit);
                } catch(SQLException ex) {
                    // JDBC may report a broken connection as soon as the intercepted
                    // COMMIT response is dropped; verify the signal below regardless.
                }
                assertTrue(proxy.sawCommitCommandComplete(),
                        "Proxy must observe real PostgreSQL COMMIT completion");
            }

            try(Connection independent=DriverManager.getConnection(pg.getJdbcUrl(),
                    pg.getUsername(),pg.getPassword());
                var q=independent.createStatement()) {
                // Independent backend confirms persistence, not client-local cache.
                try(var rs=q.executeQuery("select value from g42_pointer where site='site-1'")) {
                    assertTrue(rs.next()); assertEquals("activation-1",rs.getString(1));
                }
                try(var rs=q.executeQuery("select result from g42_receipt "+
                        "where site='site-1' and command_key='idempotency-1'")) {
                    assertTrue(rs.next()); assertEquals("activation-1",rs.getString(1));
                }
                try(var rs=q.executeQuery("select count(*) from g42_audit where site='site-1'")) {
                    assertTrue(rs.next()); assertEquals(1,rs.getInt(1));
                }
                // A retry with the SAME key returns durable receipt without redoing
                // the transition. Conflict constraint is the database authority.
                assertEquals(0,q.executeUpdate("insert into g42_receipt(site,command_key,result) "+
                        "values ('site-1','idempotency-1','activation-1') "+
                        "on conflict(site,command_key) do nothing"));
                try(var rs=q.executeQuery("select count(*) from g42_receipt")) {
                    assertTrue(rs.next());assertEquals(1,rs.getInt(1));
                }
                try(var rs=q.executeQuery("select count(*) from g42_audit")) {
                    assertTrue(rs.next());assertEquals(1,rs.getInt(1));
                }
            }
        }
    }

    /** One-client protocol proxy; only server->client COMMIT response is withheld. */
    private static final class CommitAckDropProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private final CountDownLatch intercepted=new CountDownLatch(1);
        private final AtomicReference<Throwable> failure=new AtomicReference<>();
        private final String upstreamHost;
        private final int upstreamPort;
        CommitAckDropProxy(String host,int port) throws IOException {
            upstreamHost=host;upstreamPort=port;
            listener=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());
            worker=new Thread(this::run,"g42-postgres-commit-ack-proxy");
            worker.setDaemon(true);worker.start();
        }
        int port(){return listener.getLocalPort();}
        boolean sawCommitCommandComplete() throws InterruptedException {
            if(!intercepted.await(15,TimeUnit.SECONDS))return false;
            if(failure.get()!=null)throw new AssertionError("Proxy failed",failure.get());
            return true;
        }
        private void run() {
            try(Socket client=listener.accept();
                Socket backend=new Socket(upstreamHost,upstreamPort)) {
                client.setSoTimeout(15000);backend.setSoTimeout(15000);
                Thread forward=new Thread(()->{
                    try {client.getInputStream().transferTo(backend.getOutputStream());}
                    catch(IOException ignored) {/* socket shutdown expected after interception */}
                },"g42-pg-client-forward");
                forward.setDaemon(true);forward.start();
                var server=new DataInputStream(backend.getInputStream());
                var toClient=new DataOutputStream(client.getOutputStream());
                // Server messages are framed: tag (1 byte), length (4 bytes, incl. length), body.
                // Client StartupMessage has NO tag; it is forwarded by the other thread.
                while(true) {
                    int tag=server.read();
                    if(tag<0)break;
                    int length=server.readInt();
                    if(length<4||length>16*1024*1024)
                        throw new IOException("Invalid PostgreSQL frame length="+length);
                    byte[] payload=new byte[length-4];
                    server.readFully(payload);
                    if(tag=='C' && new String(payload,StandardCharsets.UTF_8).equals("COMMIT\0")) {
                        // PostgreSQL has processed COMMIT, but client receives no ACK.
                        intercepted.countDown();
                        return;
                    }
                    toClient.writeByte(tag);
                    toClient.writeInt(length);
                    toClient.write(payload);
                    toClient.flush();
                }
            } catch(Throwable ex) {
                if(intercepted.getCount()>0)failure.set(ex);
            }
        }
        @Override public void close() throws Exception {
            listener.close();worker.join(Duration.ofSeconds(2));
        }
    }
}
