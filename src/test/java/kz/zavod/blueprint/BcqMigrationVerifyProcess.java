package kz.zavod.blueprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import kz.zavod.ZavodApplication;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.domain.ProcessBlueprint.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Cross-JVM migration acceptance with Hibernate ddl-auto=validate and a
 * separate login role unable to update/delete the business ledger.
 * Verifies preservation of pre-upgrade business data and post-upgrade writes.
 */
public final class BcqMigrationVerifyProcess {
    private BcqMigrationVerifyProcess(){}
    private static void check(boolean valid,String message) {
        if(!valid)throw new AssertionError("G42 MIGRATION FAILED: "+message);
    }
    private static void actor(String name) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(name,"test",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: BcqMigrationVerifyProcess MANIFEST");
        Properties manifest=new Properties();
        try(var input=Files.newBufferedReader(Path.of(args[0]))){manifest.load(input);}
        try(var app=new SpringApplicationBuilder(ZavodApplication.class)
                    .web(WebApplicationType.NONE).run()) {
            String site=manifest.getProperty("siteId");
            UUID oldBlueprint=UUID.fromString(manifest.getProperty("activeBlueprintId"));
            String previous=manifest.getProperty("activationId");
            var sites=app.getBean(SiteRepository.class);
            var approvals=app.getBean(BlueprintApprovalRepository.class);
            var sets=app.getBean(PlantActivationSetRepository.class);
            var ownership=app.getBean(BlueprintSiteRepository.class);
            var drafts=app.getBean(BlueprintDraftService.class);
            var lifecycle=app.getBean(BlueprintLifecycleService.class);
            var commands=app.getBean(ActivationCommandService.class);
            var ledger=app.getBean(BusinessTransitionRepository.class);
            check(sites.findById(site).orElseThrow().activeSetId().equals(previous),
                    "legacy active pointer changed");
            check(sets.countBySiteId(site)==3,"legacy activation rows lost");
            check(approvals.findById(oldBlueprint+":2").orElseThrow().state()
                    ==BlueprintApprovalEntity.State.PUBLISHED,"legacy approval changed");
            check(approvals.findById(oldBlueprint+":3").orElseThrow().state()
                    ==BlueprintApprovalEntity.State.REVOKED,"legacy revoke changed");
            check(drafts.loadRevision(oldBlueprint,2).units().size()==1,
                    "legacy blueprint revision changed");
            UUID upgraded=UUID.randomUUID();
            ownership.saveAndFlush(new BlueprintSiteEntity(upgraded.toString(),site));
            var equipment=new Equipment("tank","water-tank",1,
                    List.of(new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
            var unit=new ProcessUnit("L1",UnitKind.LINE,"test.water-storage",
                    List.of(equipment),List.of(),List.of(),List.of(),List.of(),false);
            drafts.saveDraft(new ProcessBlueprint(upgraded,1,10,List.of(unit),
                    List.of(),List.of(),List.of()),0);
            actor("reviewer");lifecycle.approve(site,upgraded,1,Set.of("L1"));
            actor("publisher");lifecycle.publish(site,upgraded,1);
            actor("activator");
            var newReceipt=commands.activate(site,"g42-post-migration",upgraded,1,previous);
            check(!newReceipt.replayed(),"initial migration command replayed");
            check(newReceipt.activationSetId().equals(sites.findById(site)
                    .orElseThrow().activeSetId()),"post-migration pointer missing");
            check(commands.activate(site,"g42-post-migration",upgraded,1,previous).replayed(),
                    "post-migration replay must be idempotent");
            check(commands.observe(site,"g42-post-migration").state()
                    ==ActivationCommandService.OutcomeState.COMMITTED,"receipt unreadable");
            check(ledger.findBySiteIdOrderBySequenceNoAsc(site).size()==3,
                    "new journal must contain exactly approve/publish/activate");
            check(sets.countBySiteId(site)==4,"legacy activation set not preserved");
            app.getBean(BusinessTransitionLedger.class).verifySite(site);
            System.out.println("BCQ_G42_MIGRATION_VALIDATE_PASS: populated legacy state preserved; "+
                    "ddl-auto=validate and restricted runtime role; atomic new command and audit");
        } finally {SecurityContextHolder.clearContext();}
    }
}
