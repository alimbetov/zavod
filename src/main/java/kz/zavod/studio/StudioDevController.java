package kz.zavod.studio;

import java.util.*;
import kz.zavod.blueprint.domain.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.catalog.*;
import kz.zavod.engineering.EngineeringPropertyDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static kz.zavod.studio.StudioViewLayout.*;

/**
 * Local-only R0.6A engineering bridge. Never load it with a production profile.
 * No PLC endpoints, no engineer approval/publishing network endpoints.
 */
@RestController
@Profile("dev-local")
@RequestMapping("/api/v1")
public class StudioDevController {
    private final SiteRepository sites;
    private final StudioBlueprintService blueprints;
    private final StudioLayoutService layouts;
    private final EquipmentCatalog catalog=EquipmentCatalog.standard();

    public StudioDevController(SiteRepository sites,StudioBlueprintService blueprints,
                               StudioLayoutService layouts) {
        this.sites=sites;this.blueprints=blueprints;this.layouts=layouts;
    }

    public record SiteRequest(int maxActiveLines) {}
    public record SiteResponse(String siteId,int maxActiveLines) {}
    public record SaveDraftRequest(long expectedRevision,ProcessBlueprint snapshot) {}
    public record Diagnostic(String code,String severity,String entity,String detail) {}
    public record EquipmentTypeDto(String key,int version,String label,
            Set<EquipmentType.Capability> capabilities,Set<EquipmentType.Mode> modes,
            List<EquipmentType.Port> ports,List<EngineeringPropertyDefinition> properties) {
        public static EquipmentTypeDto from(EquipmentType value) {
            return new EquipmentTypeDto(value.key(),value.version(),value.label(),
                    value.capabilities(),value.modes(),value.ports(),value.properties());
        }
    }

    /** Explicit one-user local-demo bootstrap, not remote tenant provisioning. */
    @PostMapping("/dev/sites")
    @ResponseStatus(HttpStatus.CREATED)
    public SiteResponse createSite(@RequestBody SiteRequest request){
        if(request.maxActiveLines()<1||request.maxActiveLines()>1000)
            throw new IllegalArgumentException("INVALID_SITE_POLICY");
        String id="local-"+UUID.randomUUID();
        sites.saveAndFlush(new SiteEntity(id,request.maxActiveLines()));
        return new SiteResponse(id,request.maxActiveLines());
    }

    @GetMapping("/sites/{siteId}/equipment-types")
    public List<EquipmentTypeDto> equipmentTypes(@PathVariable String siteId) {
        blueprints.requireSite(siteId);
        return catalog.versions().values().stream()
                .sorted(Comparator.comparing(EquipmentType::key).thenComparingInt(EquipmentType::version))
                .map(EquipmentTypeDto::from).toList();
    }

    @PostMapping("/sites/{siteId}/blueprints")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcessBlueprint createBlueprint(@PathVariable String siteId){
        return blueprints.create(siteId);
    }

    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}")
    public ProcessBlueprint latest(@PathVariable String siteId,@PathVariable UUID blueprintId){
        return blueprints.load(siteId,blueprintId);
    }

    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/revisions/{revision}")
    public ProcessBlueprint revision(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                     @PathVariable long revision){
        return blueprints.revision(siteId,blueprintId,revision);
    }

    @PutMapping("/sites/{siteId}/blueprints/{blueprintId}/draft")
    public ProcessBlueprint saveDraft(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                      @RequestBody SaveDraftRequest request){
        if(request.snapshot()==null)throw new IllegalArgumentException("MISSING_BLUEPRINT_SNAPSHOT");
        return blueprints.saveDraft(siteId,blueprintId,request.expectedRevision(),request.snapshot());
    }

    /** Draft validation may report warnings; it never publishes or activates. */
    @PostMapping("/sites/{siteId}/blueprints/{blueprintId}/validate")
    public List<Diagnostic> validate(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                     @RequestBody ProcessBlueprint snapshot){
        blueprints.requireOwner(siteId,blueprintId);
        if(!blueprintId.equals(snapshot.id()))throw new IllegalArgumentException("BLUEPRINT_ID_MISMATCH");
        StudioInputLimits.check(snapshot);
        List<Diagnostic> errors=new ArrayList<>();
        for(BlueprintValidator.Finding f:BlueprintValidator.validate(snapshot))
            errors.add(new Diagnostic(f.code(),f.severity().name(),f.entity(),"TOPOLOGY"));
        for(BlueprintCatalogAudit.Finding f:BlueprintCatalogAudit.inspect(snapshot,catalog))
            errors.add(new Diagnostic(f.code(),"ERROR",f.unitId()+"/"+f.equipmentId(),f.detail()));
        errors.sort(Comparator.comparing(Diagnostic::entity).thenComparing(Diagnostic::code));
        return List.copyOf(errors);
    }

    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/impact")
    public BlueprintImpactAnalyzer.Impact impact(@PathVariable String siteId,
            @PathVariable UUID blueprintId,@RequestParam long from,@RequestParam long to){
        if(from<1||to<=from)throw new IllegalArgumentException("INVALID_REVISION_RANGE");
        var before=blueprints.revision(siteId,blueprintId,from);
        var after=blueprints.revision(siteId,blueprintId,to);
        return BlueprintImpactAnalyzer.analyzeChange(before,after);
    }

    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/layouts/{viewId}")
    public LayoutResponse getLayout(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                    @PathVariable String viewId){
        return layouts.load(siteId,blueprintId,viewId);
    }

    @PutMapping("/sites/{siteId}/blueprints/{blueprintId}/layouts/{viewId}")
    public LayoutResponse putLayout(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                    @PathVariable String viewId,@RequestBody SaveLayout request){
        return layouts.save(siteId,blueprintId,viewId,request);
    }
}
