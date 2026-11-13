package kz.zavod.studio;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.telemetry.*;
import kz.zavod.telemetry.TelemetryContracts.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Locally isolated, explicitly synthetic demonstration of the real engineering
 * lifecycle. Actual authorization, four-eyes checks, receipts, hash ledger,
 * telemetry persistence and quality all run through production services.
 * No physical plant, PLC, OT write or credential is ever reached.
 */
@RestController
@Profile("dev-local")
@RequestMapping("/api/v1/sites/{siteId}/blueprints/{blueprintId}/revisions/{revision}/demo")
public class PlantDemoDevController {
    public record DemoStatus(String approvalState,boolean active,String activationSetId,
                             int signalCount,long transitionCount) {}
    public record SimulationRequest(String scenario) {}
    public record SimulationResult(String scenario,String unitId,UUID signalId,
                                   String eventId,BigDecimal value,String unitCode) {}

    private static final String REVIEWER="synthetic-demo-reviewer";
    private static final String PUBLISHER="synthetic-demo-publisher";
    private static final String ACTIVATOR="synthetic-demo-activator";
    private static final String SIGNAL_KEY="demo-pressure";
    private final StudioBlueprintService blueprints;
    private final SiteMemberRepository members;
    private final SiteRepository sites;
    private final BlueprintApprovalRepository approvals;
    private final PlantActivationSetRepository activations;
    private final ActivationCommandService commands;
    private final BlueprintLifecycleService lifecycle;
    private final BusinessTransitionRepository events;
    private final SignalRegistryService registry;
    private final TelemetryIngestionService ingestion;

    public PlantDemoDevController(StudioBlueprintService blueprints,SiteMemberRepository members,
                                  SiteRepository sites,BlueprintApprovalRepository approvals,
                                  PlantActivationSetRepository activations,
                                  ActivationCommandService commands,BlueprintLifecycleService lifecycle,
                                  BusinessTransitionRepository events,SignalRegistryService registry,
                                  TelemetryIngestionService ingestion) {
        this.blueprints=blueprints;this.members=members;this.sites=sites;
        this.approvals=approvals;this.activations=activations;this.commands=commands;
        this.lifecycle=lifecycle;this.events=events;this.registry=registry;this.ingestion=ingestion;
    }
    private kz.zavod.blueprint.domain.ProcessBlueprint qualify(String siteId,UUID blueprintId,long revision) {
        if(!siteId.startsWith("local-"))throw new SecurityException("DEMO_LOCAL_SITE_ONLY");
        var blueprint=blueprints.revision(siteId,blueprintId,revision);
        if(blueprint.units().size()!=3
                ||blueprint.units().stream().noneMatch(u->u.id().endsWith("-MIX")))
            throw new IllegalArgumentException("DEMO_REQUIRES_CONCRETE_STARTER");
        return blueprint;
    }
    private void ensureActor(String siteId,String actor,SiteMemberEntity.Role role) {
        if(members.findBySiteIdAndActorId(siteId,actor).isEmpty())
            members.saveAndFlush(new SiteMemberEntity(UUID.randomUUID().toString(),siteId,actor,role));
    }
    private <T> T asActor(String actor,Supplier<T> operation) {
        var original=SecurityContextHolder.getContext();
        var synthetic=SecurityContextHolder.createEmptyContext();
        synthetic.setAuthentication(new UsernamePasswordAuthenticationToken(actor,
                "local-demo-only",List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(synthetic);
        try{return operation.get();}
        finally{SecurityContextHolder.setContext(original);}
    }
    @GetMapping
    public DemoStatus status(@PathVariable String siteId,@PathVariable UUID blueprintId,
                             @PathVariable long revision) {
        qualify(siteId,blueprintId,revision);
        var approval=approvals.findById(blueprintId+":"+revision);
        String state=approval.map(x->x.state().name()).orElse("DRAFT");
        String id=sites.findById(siteId).orElseThrow().activeSetId();
        boolean active=id!=null && activations.findById(id)
                .map(x->x.blueprintId().equals(blueprintId.toString()) && x.revisionNo()==revision)
                .orElse(false);
        return new DemoStatus(state,active,active?id:null,
                registry.list(siteId,blueprintId,revision).size(),events.countBySiteId(siteId));
    }
    @PostMapping("/approve")
    public DemoStatus approve(@PathVariable String siteId,@PathVariable UUID blueprintId,
                              @PathVariable long revision) {
        var blueprint=qualify(siteId,blueprintId,revision);
        ensureActor(siteId,REVIEWER,SiteMemberEntity.Role.REVIEWER);
        asActor(REVIEWER,()->{lifecycle.approve(siteId,blueprintId,revision,
                blueprint.units().stream().map(x->x.id()).collect(java.util.stream.Collectors.toSet()));return null;});
        return status(siteId,blueprintId,revision);
    }
    @PostMapping("/publish")
    public DemoStatus publish(@PathVariable String siteId,@PathVariable UUID blueprintId,
                              @PathVariable long revision) {
        qualify(siteId,blueprintId,revision);
        ensureActor(siteId,PUBLISHER,SiteMemberEntity.Role.PUBLISHER);
        asActor(PUBLISHER,()->{lifecycle.publish(siteId,blueprintId,revision);return null;});
        return status(siteId,blueprintId,revision);
    }
    @PostMapping("/activate")
    public DemoStatus activate(@PathVariable String siteId,@PathVariable UUID blueprintId,
                               @PathVariable long revision) {
        qualify(siteId,blueprintId,revision);
        ensureActor(siteId,ACTIVATOR,SiteMemberEntity.Role.ACTIVATOR);
        String previous=sites.findById(siteId).orElseThrow().activeSetId();
        // Fixed per-revision key: retry after dropped response remains idempotent.
        String key="local-demo-"+blueprintId+"-r"+revision;
        asActor(ACTIVATOR,()->commands.activate(siteId,key,blueprintId,revision,previous));
        return status(siteId,blueprintId,revision);
    }
    private SignalDefinition demoSignal(String siteId,UUID blueprintId,long revision) {
        var blueprint=qualify(siteId,blueprintId,revision);
        var mixing=blueprint.units().stream().filter(x->x.id().endsWith("-MIX")).findFirst().orElseThrow();
        var equipment=mixing.equipment().stream().filter(x->x.id().equals("water-doser"))
                .findFirst().orElseThrow(()->new IllegalArgumentException("DEMO_SENSOR_TARGET_MISSING"));
        return registry.list(siteId,blueprintId,revision).stream()
                .filter(x->x.signalKey().equals(SIGNAL_KEY) && x.unitId().equals(mixing.id()))
                .findFirst().orElseGet(()->registry.registerSimulated(new SignalDefinition(UUID.randomUUID(),
                        siteId,blueprintId,revision,mixing.id(),equipment.id(),
                        SIGNAL_KEY,"bar",SourceKind.SIMULATED,Duration.ofMinutes(2))));
    }
    @PostMapping("/simulate")
    @ResponseStatus(HttpStatus.OK)
    public SimulationResult simulate(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                     @PathVariable long revision,@RequestBody SimulationRequest request) {
        var state=status(siteId,blueprintId,revision);
        if(!state.active())throw new IllegalArgumentException("DEMO_REQUIRES_ACTIVE_ENGINEERING_CONFIGURATION");
        String scenario=request.scenario();
        if(!Set.of("NORMAL","ALARM").contains(scenario))
            throw new IllegalArgumentException("INVALID_DEMO_SCENARIO");
        var binding=demoSignal(siteId,blueprintId,revision);
        BigDecimal value="ALARM".equals(scenario)?new BigDecimal("6.2"):new BigDecimal("2.5");
        String eventId="demo-"+UUID.randomUUID();
        ingestion.ingestSimulated(siteId,blueprintId,revision,binding.id(),
                new TelemetryIngestionService.Incoming(eventId,value,"bar",
                        Instant.now().minusSeconds(1),Quality.GOOD));
        return new SimulationResult(scenario,binding.unitId(),binding.id(),
                eventId,value,"bar");
    }
}
