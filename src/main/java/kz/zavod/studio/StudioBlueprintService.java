package kz.zavod.studio;

import java.util.*;
import kz.zavod.blueprint.domain.*;
import kz.zavod.blueprint.lifecycle.*;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scoped access for the local-dev engineering API, not a production authentication system. */
@Service
public class StudioBlueprintService {
    private final SiteRepository sites;
    private final BlueprintSiteRepository assignments;
    private final BlueprintDraftService drafts;
    public StudioBlueprintService(SiteRepository sites,BlueprintSiteRepository assignments,
                                  BlueprintDraftService drafts) {
        this.sites=sites;this.assignments=assignments;this.drafts=drafts;
    }

    @Transactional
    public ProcessBlueprint create(String siteId) {
        var site=requireSite(siteId);
        UUID id=UUID.randomUUID();
        var snapshot=new ProcessBlueprint(id,1,site.maxActiveLines(),
                List.of(),List.of(),List.of(),List.of());
        assignments.saveAndFlush(new BlueprintSiteEntity(id.toString(),siteId));
        drafts.saveDraft(snapshot,0);
        return snapshot;
    }

    @Transactional(readOnly=true)
    public ProcessBlueprint load(String siteId,UUID id) {
        requireOwner(siteId,id);
        return drafts.loadLatest(id);
    }

    @Transactional(readOnly=true)
    public ProcessBlueprint revision(String siteId,UUID id,long revision){
        requireOwner(siteId,id);
        return drafts.loadRevision(id,revision);
    }

    @Transactional
    public ProcessBlueprint saveDraft(String siteId,UUID id,long expectedRevision,ProcessBlueprint input) {
        var site=requireOwner(siteId,id);
        Objects.requireNonNull(input,"snapshot");
        if(!id.equals(input.id()))throw new IllegalArgumentException("BLUEPRINT_ID_MISMATCH");
        StudioInputLimits.check(input);
        // Never trust maxActiveLines provided by a browser.
        var canonical=new ProcessBlueprint(id,input.revision(),site.maxActiveLines(),input.units(),
                input.sharedAssets(),input.transfers(),input.dependencies());
        drafts.saveDraft(canonical,expectedRevision);
        return canonical;
    }

    public SiteEntity requireOwner(String siteId,UUID id){
        var site=requireSite(siteId);
        var assignment=assignments.findById(id.toString())
                .orElseThrow(()->new StudioNotFoundException("BLUEPRINT_NOT_FOUND"));
        if(!siteId.equals(assignment.siteId()))
            throw new StudioNotFoundException("BLUEPRINT_NOT_FOUND");
        return site;
    }
    public SiteEntity requireSite(String siteId){
        return sites.findById(siteId).orElseThrow(()->new StudioNotFoundException("SITE_NOT_FOUND"));
    }
}
