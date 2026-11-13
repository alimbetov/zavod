package kz.zavod.blueprint;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kz.zavod.blueprint.domain.GuidedBlueprintBuilder;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.studio.StudioBlueprintService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Deterministic cross-thread PostgreSQL MVCC test. A new committed ledger event
 * between full-chain verification and tip lookup must NOT be reported as
 * independently verified. A repeatable-read snapshot pins the original tip.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BusinessLedgerAuditSnapshotPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16.6")
            .withDatabaseName("audit_snapshot").withUsername("zavod").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",pg::getJdbcUrl);
        registry.add("spring.datasource.username",pg::getUsername);
        registry.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired SiteRepository sites;
    @Autowired SiteMemberRepository members;
    @Autowired StudioBlueprintService blueprints;
    @Autowired BlueprintLifecycleService lifecycle;
    @Autowired BusinessLedgerAuditService auditor;
    @Autowired BusinessTransitionRepository transitions;
    @MockitoSpyBean BusinessTransitionLedger ledger;

    private static void actor(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name,"unit-only",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        reset(ledger);
    }

    @Test void concurrentCommitDoesNotBecomeUnverifiedChainTip() throws Exception {
        String site="snapshot-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(site,10));
        var initial=blueprints.create(site);
        var prepared=GuidedBlueprintBuilder.concreteStarter(initial.id(),2,10,
                "CP01",EquipmentCatalog.standard());
        blueprints.saveDraft(site,initial.id(),1,prepared);
        members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),site,
                "reviewer",SiteMemberEntity.Role.REVIEWER));
        members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),site,
                "publisher",SiteMemberEntity.Role.PUBLISHER));
        actor("reviewer");
        lifecycle.approve(site,initial.id(),2,Set.of("CP01-AGG","CP01-CEM","CP01-MIX"));
        assertEquals(1,transitions.countBySiteId(site));

        var firstVerificationDone=new CountDownLatch(1);
        var allowTipRead=new CountDownLatch(1);
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            firstVerificationDone.countDown();
            if(!allowTipRead.await(20,TimeUnit.SECONDS))
                throw new IllegalStateException("AUDIT_TEST_BARRIER_TIMED_OUT");
            return result;
        }).when(ledger).verifySite(site);

        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->auditor.auditAll(Map.of()));
            assertTrue(firstVerificationDone.await(20,TimeUnit.SECONDS),
                    "audit should verify the original one-event snapshot");
            // Commit from independent thread/transaction after verification but
            // before the auditor fetches the chain tip from PostgreSQL.
            actor("publisher");
            lifecycle.publish(site,initial.id(),2);
            assertEquals(2,transitions.countBySiteId(site));
            allowTipRead.countDown();

            var report=future.get(20,TimeUnit.SECONDS);
            assertTrue(report.passed());
            var checked=report.sites().stream().filter(x->x.siteId().equals(site))
                    .findFirst().orElseThrow();
            assertEquals(1,checked.sequenceNo(),
                    "audit must NOT anchor a concurrently appended, unchecked event");
            assertNotEquals(transitions.findTopBySiteIdOrderBySequenceNoDesc(site)
                    .orElseThrow().eventHash(),checked.eventHash());
        } finally {
            allowTipRead.countDown();
        }
    }
}
