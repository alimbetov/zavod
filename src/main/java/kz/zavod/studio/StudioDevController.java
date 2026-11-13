package kz.zavod.studio;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private final EngineeringReadCache readCache;
    private final BlueprintApprovalRepository approvals;
    private final ObjectMapper json;
    private final EquipmentCatalog catalog=EquipmentCatalog.standard();

    public StudioDevController(SiteRepository sites,StudioBlueprintService blueprints,
                               StudioLayoutService layouts,EngineeringReadCache readCache,
                               BlueprintApprovalRepository approvals,ObjectMapper json) {
        this.sites=sites;this.blueprints=blueprints;this.layouts=layouts;
        this.readCache=readCache;this.approvals=approvals;this.json=json;
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
        return readCache.equipmentTypes(catalog);
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
        return readCache.impact(before,after);
    }

    /** Non-persisting starter: an engineer must review/save/approve explicitly. */
    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/guided/pump")
    public ProcessBlueprint guidedPump(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                       @RequestParam(defaultValue="L001") String unitId) {
        var latest=blueprints.load(siteId,blueprintId);
        if(!latest.units().isEmpty())throw new IllegalArgumentException("GUIDED_BUILDER_REQUIRES_EMPTY_BLUEPRINT");
        return GuidedBlueprintBuilder.pumpStarter(latest.id(),latest.revision()+1,
                latest.maxActiveLines(),unitId,catalog);
    }

    /** Structural constraints only: never claims physical process certification. */
    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/golden")
    public EngineeringGoldenModel.Report golden(@PathVariable String siteId,@PathVariable UUID blueprintId) {
        return EngineeringGoldenModel.inspect(blueprints.load(siteId,blueprintId),catalog);
    }

    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/explain")
    public List<ExplainableValidation.Explanation> explanations(
            @PathVariable String siteId,@PathVariable UUID blueprintId) {
        return ExplainableValidation.inspect(blueprints.load(siteId,blueprintId),catalog);
    }

    /** Diff and impact are documented. Authorship/reason are NOT fabricated. */
    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/changes")
    public EngineeringChangeManagement.Review changes(
            @PathVariable String siteId,@PathVariable UUID blueprintId,
            @RequestParam long from,@RequestParam long to) {
        if(from<1||to<=from)throw new IllegalArgumentException("INVALID_REVISION_RANGE");
        var before=blueprints.revision(siteId,blueprintId,from);
        var after=blueprints.revision(siteId,blueprintId,to);
        return EngineeringChangeManagement.compare(before,after,readCache.impact(before,after));
    }

    /** Published, explicitly selected equipment only. All rendered states are UNKNOWN. */
    @GetMapping("/sites/{siteId}/blueprints/{blueprintId}/revisions/{revision}/hmi-preview")
    public HmiPreviewGenerator.Preview hmiPreview(
            @PathVariable String siteId,@PathVariable UUID blueprintId,@PathVariable long revision) {
        var blueprint=blueprints.revision(siteId,blueprintId,revision);
        var approval=approvals.findById(blueprintId+":"+revision)
                .orElseThrow(()->new IllegalArgumentException("HMI_PREVIEW_REQUIRES_PUBLISHED_REVISION"));
        if(!approval.siteId().equals(siteId)||approval.state()!=BlueprintApprovalEntity.State.PUBLISHED)
            throw new IllegalArgumentException("HMI_PREVIEW_REQUIRES_PUBLISHED_REVISION");
        try {
            Set<String> selected=Set.copyOf(json.readValue(approval.selectedUnitsJson(),
                    new TypeReference<List<String>>(){}));
            return HmiPreviewGenerator.generate(blueprint,selected);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("CORRUPTED_APPROVED_SELECTION",e);
        }
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
