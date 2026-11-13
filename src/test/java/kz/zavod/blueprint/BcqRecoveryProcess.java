package kz.zavod.blueprint;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import kz.zavod.ZavodApplication;
import kz.zavod.blueprint.domain.LabBlueprintAdapter;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import kz.zavod.lab.PlantScenarios;
import kz.zavod.studio.StudioBlueprintService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Cross-JVM, external PostgreSQL qualification. Seed process stays alive until SIGKILL.
 * Verifier runs in a different JVM; never changes production configurations.
 */
public final class BcqRecoveryProcess {
    private BcqRecoveryProcess() {}
    private static void check(boolean good,String reason) {
        if(!good)throw new AssertionError("BCQ-G3 "+reason);
    }
    private static void actor(String actorId){
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actorId,"test-only",
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
    }
    private static String hash(JdbcTemplate jdbc,UUID id,int rev){
        return jdbc.queryForObject(
            "select content_hash from blueprint_revision where blueprint_id=? and revision_no=?",
            String.class,id.toString(),rev);
    }
    private static String str(Properties p,String name) {
        String value=p.getProperty(name);
        if(value==null||value.isBlank())throw new AssertionError("BCQ missing manifest field "+name);
        return value;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3||(!"seed".equals(args[0])&&!"verify".equals(args[0])))
            throw new IllegalArgumentException("Usage: BcqRecoveryProcess seed|verify MANIFEST READY");
        try(ConfigurableApplicationContext context=new SpringApplicationBuilder(ZavodApplication.class)
                    .web(WebApplicationType.NONE).run()) {
            if(args[0].equals("seed")) {
                seed(context,Path.of(args[1]),Path.of(args[2]));
                // This process must be forcibly terminated by the test harness.
                Thread.sleep(Long.MAX_VALUE);
                throw new AssertionError("Seed process survived unexpectedly");
            } else {
                verify(context,Path.of(args[1]));
                System.out.println("BCQ-G3: cross-JVM/DB restoration PASS");
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
    private static void seed(ConfigurableApplicationContext app,Path manifest,Path ready) throws IOException {
        var sites=app.getBean(SiteRepository.class);
        var members=app.getBean(SiteMemberRepository.class);
        var drafts=app.getBean(BlueprintDraftService.class);
        var studio=app.getBean(StudioBlueprintService.class);
        var lifecycle=app.getBean(BlueprintLifecycleService.class);
        var jdbc=app.getBean(JdbcTemplate.class);
        String siteId="bcq-recovery-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(siteId,10));
        for(var role:Map.of("reviewer",SiteMemberEntity.Role.REVIEWER,
                            "publisher",SiteMemberEntity.Role.PUBLISHER,
                            "activator",SiteMemberEntity.Role.ACTIVATOR,
                            "viewer",SiteMemberEntity.Role.VIEWER).entrySet())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),
                siteId,role.getKey(),role.getValue()));

        ProcessBlueprint base=studio.create(siteId);
        var waterTank=new Equipment("T001","water-tank",1,
            List.of(new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var unit=new ProcessUnit("L001",UnitKind.LINE,"continuous.pump.v1",
            List.of(waterTank),List.of(),List.of(),List.of(),List.of(),false);
        ProcessBlueprint revision2=new ProcessBlueprint(base.id(),2,10,
            List.of(unit),List.of(),List.of(),List.of());
        ProcessBlueprint saved=studio.saveDraft(siteId,base.id(),1,revision2);
        check(saved.equals(revision2),"active qualified blueprint save");
        actor("reviewer");
        lifecycle.approve(siteId,base.id(),2,Set.of("L001"));
        actor("publisher");
        lifecycle.publish(siteId,base.id(),2);
        actor("activator");
        String activationId=lifecycle.activate(siteId,base.id(),2,null);
        check(lifecycle.active(siteId).id().equals(activationId),"activation pointer before JVM crash");
        // Exercise the full published -> upgraded -> restored -> revoked historical chain
        // before SIGKILL. The separate verifier must recover this precise chain.
        ProcessBlueprint revision3=new ProcessBlueprint(base.id(),3,10,
                revision2.units(),revision2.sharedAssets(),revision2.transfers(),revision2.dependencies());
        studio.saveDraft(siteId,base.id(),2,revision3);
        actor("reviewer");lifecycle.approve(siteId,base.id(),3,Set.of("L001"));
        actor("publisher");lifecycle.publish(siteId,base.id(),3);
        actor("activator");
        String upgradedId=lifecycle.activate(siteId,base.id(),3,activationId);
        String restoredId=lifecycle.restore(siteId,activationId,upgradedId,"BCQ-G3 rollback after regression");
        actor("publisher");lifecycle.revoke(siteId,base.id(),3,"BCQ-G3 blocked revision");
        check(lifecycle.active(siteId).id().equals(restoredId),"restored pointer before JVM crash");

        ProcessBlueprint emptyLab=studio.create(siteId);
        UUID labId=emptyLab.id();
        ProcessBlueprint synthetic=LabBlueprintAdapter.fromLab(labId,2,PlantScenarios.generate(99,99));
        ProcessBlueprint stored=studio.saveDraft(siteId,labId,1,synthetic);
        check(stored.units().size()==99,"lab units baseline");
        check(stored.transfers().size()==64,"lab interline transfers baseline");
        check(stored.dependencies().size()==14,"lab dependencies baseline");
        check(stored.units().stream().mapToInt(x->x.equipment().size()).sum()==853,"lab equipment baseline");
        Properties p=new Properties();
        p.setProperty("siteId",siteId);
        p.setProperty("activeBlueprintId",base.id().toString());
        p.setProperty("labBlueprintId",labId.toString());
        p.setProperty("activationId",restoredId);
        p.setProperty("originalActivationId",activationId);
        p.setProperty("upgradedActivationId",upgradedId);
        p.setProperty("activeHash",hash(jdbc,base.id(),2));
        p.setProperty("labHash",hash(jdbc,labId,2));
        try(var writer=Files.newBufferedWriter(manifest)) {p.store(writer,"BCQ-G3 crash-and-recover qualification");}
        Files.writeString(ready,"committed PID="+ProcessHandle.current().pid());
        System.out.println("BCQ-G3: committed state in PostgreSQL, PID="+ProcessHandle.current().pid());
        System.out.flush();
    }
    private static void verify(ConfigurableApplicationContext app,Path manifest) throws IOException {
        Properties p=new Properties();
        try(var in=Files.newBufferedReader(manifest)) {p.load(in);}
        String siteId=str(p,"siteId");
        UUID activeBlueprintId=UUID.fromString(str(p,"activeBlueprintId"));
        UUID labId=UUID.fromString(str(p,"labBlueprintId"));
        String activationId=str(p,"activationId");
        var sites=app.getBean(SiteRepository.class);
        var drafts=app.getBean(BlueprintDraftService.class);
        var lifecycle=app.getBean(BlueprintLifecycleService.class);
        var approvals=app.getBean(BlueprintApprovalRepository.class);
        var sets=app.getBean(PlantActivationSetRepository.class);
        var jdbc=app.getBean(JdbcTemplate.class);
        check(sites.findById(siteId).orElseThrow().activeSetId().equals(activationId),"site pointer");
        check(sites.findById(siteId).orElseThrow().maxActiveLines()==10,"server quota");
        check(str(p,"activeHash").equals(hash(jdbc,activeBlueprintId,2)),"active revision hash");
        check(str(p,"labHash").equals(hash(jdbc,labId,2)),"99-line revision hash");
        check(drafts.loadRevision(activeBlueprintId,1).units().isEmpty(),"initial rev1 preserved");
        check(drafts.loadRevision(labId,1).units().isEmpty(),"lab initial rev1 preserved");
        ProcessBlueprint working=drafts.loadRevision(activeBlueprintId,2);
        check(working.units().size()==1,"active blueprint rev2 one line");
        check(working.units().get(0).equipment().get(0).id().equals("T001"),"active equipment stable id");
        check(approvals.findById(activeBlueprintId+":2").orElseThrow().state()
                ==BlueprintApprovalEntity.State.PUBLISHED,"approval state");
        check(sets.countBySiteId(siteId)==3,"original, upgraded and restored sets retained");
        check(approvals.findById(activeBlueprintId+":3").orElseThrow().state()
                ==BlueprintApprovalEntity.State.REVOKED,"revoked upgrade persisted");
        String originalId=str(p,"originalActivationId");
        String upgradedId=str(p,"upgradedActivationId");
        check(sets.findById(originalId).orElseThrow().revisionNo()==2,"original revision remains immutable");
        check(sets.findById(upgradedId).orElseThrow().revisionNo()==3,"upgraded revision retained");
        check(drafts.loadRevision(activeBlueprintId,3).revision()==3,"revoked revision snapshot preserved");
        actor("viewer");
        var active=lifecycle.active(siteId);
        check(active.id().equals(activationId),"active set id");
        check(active.revisionNo()==2L,"active set revision");
        check(active.blueprintId().equals(activeBlueprintId.toString()),"active blueprint");
        check(active.selectedUnitsJson().contains("L001"),"selected units");
        check(active.previousSetId().equals(str(p,"upgradedActivationId")),"restore previous pointer");
        check(active.restoredFromSetId().equals(str(p,"originalActivationId")),"restore historical target");
        check("BCQ-G3 rollback after regression".equals(active.changeReason()),"restore reason persisted");

        ProcessBlueprint actual=drafts.loadRevision(labId,2);
        ProcessBlueprint expected=LabBlueprintAdapter.fromLab(labId,2,PlantScenarios.generate(99,99));
        expected=new ProcessBlueprint(labId,2,10,expected.units(),
                expected.sharedAssets(),expected.transfers(),expected.dependencies());
        check(actual.equals(expected),"exact equality for all 99 process graphs, equipment, ports and links");
        check(actual.equals(drafts.loadLatest(labId)),"latest lab snapshot equality");
        check(actual.units().size()==99,"99 process units");
        check(actual.units().stream().mapToInt(u->u.equipment().size()).sum()==853,"853 equipment");
        check(actual.transfers().size()==64,"64 transfers");
        check(actual.dependencies().size()==14,"14 dependencies");
        check(actual.sharedAssets().size()==2,"two shared assets");
    }
}
