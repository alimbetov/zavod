package kz.zavod.blueprint.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import kz.zavod.blueprint.domain.BlueprintPublicationGate;
import kz.zavod.catalog.EquipmentCatalog;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Server-authorized engineering approval, publication and selection activation.
 * No operational PLC write or OPC-UA command path exists.
 */
@Service
public class BlueprintLifecycleService {
    private final SiteRepository sites;
    private final SiteMemberRepository members;
    private final BlueprintSiteRepository assignments;
    private final BlueprintApprovalRepository approvals;
    private final PlantActivationSetRepository sets;
    private final BlueprintDraftService drafts;
    private final ObjectMapper mapper;
    private final EquipmentCatalog catalog=EquipmentCatalog.standard();

    public BlueprintLifecycleService(SiteRepository sites,SiteMemberRepository members,
            BlueprintSiteRepository assignments,BlueprintApprovalRepository approvals,
            PlantActivationSetRepository sets,BlueprintDraftService drafts,ObjectMapper mapper){
        this.sites=sites;this.members=members;this.assignments=assignments;
        this.approvals=approvals;this.sets=sets;this.drafts=drafts;this.mapper=mapper;
    }

    @Transactional
    public void approve(String siteId,UUID blueprintId,long revision,Set<String> selectedUnitIds){
        String actor=authorize(siteId,SiteMemberEntity.Role.REVIEWER);
        verifyOwned(siteId,blueprintId);
        SiteEntity site=sites.findById(siteId).orElseThrow(()->new BlueprintLifecycleException("UNKNOWN_SITE"));
        var snapshot=drafts.loadRevision(blueprintId,revision);
        var findings=BlueprintPublicationGate.preflight(snapshot,Set.copyOf(selectedUnitIds),site.maxActiveLines(),catalog);
        if(!findings.isEmpty())throw new BlueprintLifecycleException(
                "PUBLISH_PREFLIGHT_BLOCKED:"+findings.get(0).code()+":"+findings.get(0).entity());
        String key=key(blueprintId,revision);
        if(approvals.existsById(key))throw new BlueprintLifecycleException("ALREADY_APPROVED");
        try {
            approvals.saveAndFlush(new BlueprintApprovalEntity(siteId,blueprintId.toString(),revision,
                    encode(selectedUnitIds),actor));
        } catch(DataIntegrityViolationException | OptimisticLockingFailureException ex) {
            // Two reviewers may pass the pre-check concurrently: the database owns
            // uniqueness, and callers receive a stable business conflict.
            throw new BlueprintLifecycleException("ALREADY_APPROVED");
        }
    }

    @Transactional
    public void publish(String siteId,UUID blueprintId,long revision){
        String actor=authorize(siteId,SiteMemberEntity.Role.PUBLISHER);
        verifyOwned(siteId,blueprintId);
        var approval=approvals.lockApproval(key(blueprintId,revision))
                .orElseThrow(()->new BlueprintLifecycleException("NOT_APPROVED"));
        if(!siteId.equals(approval.siteId()))throw new SecurityException("SITE_ACCESS_DENIED");
        // Publication must have a second accountable human, including when both actors are ADMIN.
        if(actor.equals(approval.approvedBy()))throw new BlueprintLifecycleException("FOUR_EYES_REQUIRED");
        approval.publish(actor);
        approvals.saveAndFlush(approval);
    }

    /**
     * expectedPreviousSetId is the caller's last observed activation pointer (null for first activation).
     * Site row lock + transaction atomically commit the immutable set and update the current pointer.
     */
    @Transactional
    public String activate(String siteId,UUID blueprintId,long revision,String expectedPreviousSetId){
        String actor=authorize(siteId,SiteMemberEntity.Role.ACTIVATOR);
        verifyOwned(siteId,blueprintId);
        SiteEntity site=sites.lockSite(siteId)
                .orElseThrow(()->new BlueprintLifecycleException("UNKNOWN_SITE"));
        if(!Objects.equals(expectedPreviousSetId,site.activeSetId()))
            throw new BlueprintLifecycleException("STALE_ACTIVATION_SET");
        var approved=approvals.findById(key(blueprintId,revision))
                .orElseThrow(()->new BlueprintLifecycleException("NOT_PUBLISHED"));
        if(!siteId.equals(approved.siteId())||approved.state()!=BlueprintApprovalEntity.State.PUBLISHED)
            throw new BlueprintLifecycleException("NOT_PUBLISHED");
        if(site.activeSetId()!=null) {
            PlantActivationSetEntity current=sets.findById(site.activeSetId())
                    .orElseThrow(()->new BlueprintLifecycleException("INVALID_ACTIVATION_POINTER"));
            requireOwnedActivation(siteId,current);
            if(current.blueprintId().equals(blueprintId.toString())
                    &&current.revisionNo()==revision
                    &&current.selectedUnitsJson().equals(approved.selectedUnitsJson()))
                throw new BlueprintLifecycleException("ALREADY_ACTIVE");
        }
        Set<String> selected=decode(approved.selectedUnitsJson());
        var blueprint=drafts.loadRevision(blueprintId,revision);
        var findings=BlueprintPublicationGate.preflight(blueprint,selected,site.maxActiveLines(),catalog);
        if(!findings.isEmpty())throw new BlueprintLifecycleException(
                "ACTIVATION_BLOCKED:"+findings.get(0).code()+":"+findings.get(0).entity());
        String activationId=UUID.randomUUID().toString();
        sets.saveAndFlush(new PlantActivationSetEntity(activationId,siteId,blueprintId.toString(),
                revision,approved.selectedUnitsJson(),site.activeSetId(),actor));
        site.activate(activationId);
        sites.saveAndFlush(site);
        return activationId;
    }

    /**
     * A revoked publication cannot be activated or restored. An already active
     * publication cannot be revoked until a replacement is selected.
     * Site lock serializes this check against concurrent activation/restore.
     */
    @Transactional
    public void revoke(String siteId,UUID blueprintId,long revision,String reason) {
        String actor=authorize(siteId,SiteMemberEntity.Role.PUBLISHER);
        verifyOwned(siteId,blueprintId);
        requireReason(reason);
        SiteEntity site=sites.lockSite(siteId)
                .orElseThrow(()->new BlueprintLifecycleException("UNKNOWN_SITE"));
        var approval=approvals.lockApproval(key(blueprintId,revision))
                .orElseThrow(()->new BlueprintLifecycleException("NOT_PUBLISHED"));
        if(!siteId.equals(approval.siteId()))throw new SecurityException("SITE_ACCESS_DENIED");
        if(site.activeSetId()!=null) {
            var current=sets.findById(site.activeSetId())
                    .orElseThrow(()->new BlueprintLifecycleException("INVALID_ACTIVATION_POINTER"));
            requireOwnedActivation(siteId,current);
            if(current.blueprintId().equals(blueprintId.toString())&&current.revisionNo()==revision)
                throw new BlueprintLifecycleException("ACTIVE_PUBLICATION_CANNOT_REVOKE");
        }
        approval.revoke(actor,reason.strip());
        approvals.saveAndFlush(approval);
    }

    /**
     * Restore means a new immutable engineering selection pointing at a
     * previously activated set, never a rewind/mutation of history or a PLC action.
     * The target must remain published and must pass today's site policy.
     */
    @Transactional
    public String restore(String siteId,String historicalSetId,String expectedPreviousSetId,String reason) {
        String actor=authorize(siteId,SiteMemberEntity.Role.ACTIVATOR);
        requireReason(reason);
        SiteEntity site=sites.lockSite(siteId)
                .orElseThrow(()->new BlueprintLifecycleException("UNKNOWN_SITE"));
        if(!Objects.equals(expectedPreviousSetId,site.activeSetId()))
            throw new BlueprintLifecycleException("STALE_ACTIVATION_SET");
        if(site.activeSetId()==null)throw new BlueprintLifecycleException("NO_ACTIVE_CONFIGURATION");
        var current=sets.findById(site.activeSetId())
                .orElseThrow(()->new BlueprintLifecycleException("INVALID_ACTIVATION_POINTER"));
        requireOwnedActivation(siteId,current);
        var historical=sets.findById(historicalSetId)
                .orElseThrow(()->new BlueprintLifecycleException("UNKNOWN_RESTORE_TARGET"));
        if(!siteId.equals(historical.siteId()))throw new SecurityException("SITE_ACCESS_DENIED");
        if(current.blueprintId().equals(historical.blueprintId())
                &&current.revisionNo()==historical.revisionNo()
                &&current.selectedUnitsJson().equals(historical.selectedUnitsJson()))
            throw new BlueprintLifecycleException("ALREADY_ACTIVE");
        UUID blueprintId=UUID.fromString(historical.blueprintId());
        verifyOwned(siteId,blueprintId);
        var approval=approvals.findById(key(blueprintId,historical.revisionNo()))
                .orElseThrow(()->new BlueprintLifecycleException("NOT_PUBLISHED"));
        if(!siteId.equals(approval.siteId())||approval.state()!=BlueprintApprovalEntity.State.PUBLISHED)
            throw new BlueprintLifecycleException("NOT_PUBLISHED");
        if(!approval.selectedUnitsJson().equals(historical.selectedUnitsJson()))
            throw new BlueprintLifecycleException("HISTORICAL_SELECTION_MISMATCH");
        var blueprint=drafts.loadRevision(blueprintId,historical.revisionNo());
        var findings=BlueprintPublicationGate.preflight(blueprint,
                decode(historical.selectedUnitsJson()),site.maxActiveLines(),catalog);
        if(!findings.isEmpty())
            throw new BlueprintLifecycleException("RESTORE_BLOCKED:"+findings.get(0).code()+":"+findings.get(0).entity());
        String restoredId=UUID.randomUUID().toString();
        sets.saveAndFlush(new PlantActivationSetEntity(restoredId,siteId,historical.blueprintId(),
                historical.revisionNo(),historical.selectedUnitsJson(),site.activeSetId(),
                actor,historicalSetId,reason.strip()));
        site.activate(restoredId);
        sites.saveAndFlush(site);
        return restoredId;
    }

    private static void requireReason(String reason) {
        if(reason==null||reason.isBlank()||reason.length()>1024)
            throw new BlueprintLifecycleException("REASON_REQUIRED");
    }

    @Transactional(readOnly=true)
    public PlantActivationSetEntity active(String siteId){
        authorize(siteId,SiteMemberEntity.Role.VIEWER);
        SiteEntity site=sites.findById(siteId)
                .orElseThrow(()->new BlueprintLifecycleException("UNKNOWN_SITE"));
        if(site.activeSetId()==null)throw new BlueprintLifecycleException("NOT_ACTIVATED");
        PlantActivationSetEntity selected=sets.findById(site.activeSetId())
                .orElseThrow(()->new BlueprintLifecycleException("INVALID_ACTIVATION_POINTER"));
        requireOwnedActivation(siteId,selected);
        return selected;
    }

    private void requireOwnedActivation(String siteId,PlantActivationSetEntity selected) {
        if(!siteId.equals(selected.siteId()))throw new BlueprintLifecycleException("INVALID_ACTIVATION_POINTER");
        BlueprintSiteEntity owner=assignments.findById(selected.blueprintId())
                .orElseThrow(()->new BlueprintLifecycleException("INVALID_ACTIVATION_POINTER"));
        if(!siteId.equals(owner.siteId()))throw new BlueprintLifecycleException("INVALID_ACTIVATION_POINTER");
    }

    private void verifyOwned(String siteId,UUID blueprintId){
        BlueprintSiteEntity owned=assignments.findById(blueprintId.toString())
                .orElseThrow(()->new SecurityException("SITE_ACCESS_DENIED"));
        if(!siteId.equals(owned.siteId()))throw new SecurityException("SITE_ACCESS_DENIED");
    }

    private String authorize(String siteId,SiteMemberEntity.Role required){
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()
                ||"anonymousUser".equals(authentication.getPrincipal()))
            throw new SecurityException("UNAUTHENTICATED");
        String actor=authentication.getName();
        SiteMemberEntity member=members.findBySiteIdAndActorId(siteId,actor)
                .orElseThrow(()->new SecurityException("SITE_ACCESS_DENIED"));
        if(member.role()!=SiteMemberEntity.Role.ADMIN && member.role()!=required
                && !(required==SiteMemberEntity.Role.VIEWER))
            throw new SecurityException("SITE_ROLE_DENIED");
        return actor;
    }

    private static String key(UUID id,long revision){return id+":"+revision;}
    private String encode(Set<String> selected){
        try{return mapper.writeValueAsString(new TreeSet<>(selected));}
        catch(JsonProcessingException ex){throw new IllegalStateException("INVALID_UNIT_SELECTION",ex);}
    }
    private Set<String> decode(String json){
        try{return Set.copyOf(mapper.readValue(json,new TypeReference<List<String>>(){}));}
        catch(JsonProcessingException ex){throw new IllegalStateException("INVALID_SAVED_UNIT_SELECTION",ex);}
    }
}
