package kz.zavod.blueprint.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BCQ-G4 slice: authorization-aware, site-serialized and receipt-backed
 * activation; idempotency and transition audit commit with active pointer.
 * This is a pure internal service; no external network endpoint or PLC command.
 */
@Service
public class ActivationCommandService {
    public record Receipt(String activationSetId,boolean replayed) {}
    public enum OutcomeState { COMMITTED, NOT_OBSERVED_RETRY }
    public record Observation(OutcomeState state,String activationSetId) {}
    private final SiteRepository sites;
    private final SiteMemberRepository members;
    private final ActivationCommandRepository commands;
    private final ActivationTransitionRepository transitions;
    private final BlueprintLifecycleService lifecycle;
    private final PlantActivationSetRepository sets;
    private final BusinessTransitionLedger ledger;
    private final BusinessTransitionRepository businessTransitions;

    public ActivationCommandService(SiteRepository sites,SiteMemberRepository members,
                                    ActivationCommandRepository commands,
                                    ActivationTransitionRepository transitions,
                                    BlueprintLifecycleService lifecycle,
                                    PlantActivationSetRepository sets,
                                    BusinessTransitionLedger ledger,
                                    BusinessTransitionRepository businessTransitions) {
        this.sites=sites;this.members=members;this.commands=commands;
        this.transitions=transitions;this.lifecycle=lifecycle;this.sets=sets;
        this.ledger=ledger;this.businessTransitions=businessTransitions;
    }

    @Transactional
    public Receipt activate(String siteId,String commandKey,UUID blueprintId,
                            long revision,String expectedPreviousSetId) {
        validateKey(commandKey);
        Objects.requireNonNull(blueprintId,"blueprintId");
        if(revision<1)throw new IllegalArgumentException("INVALID_REVISION");
        String actor=authorize(siteId);
        // All idempotency decisions for this Site are serialized by the row lock.
        // The lock is held until COMMIT along with the business mutation and audit.
        sites.lockSite(siteId).orElseThrow(()->new BlueprintLifecycleException("UNKNOWN_SITE"));
        // Explicit null/value encoding avoids replay collisions such as null vs "<none>".
        String previousToken=expectedPreviousSetId==null?"N":
                "V"+expectedPreviousSetId.length()+":"+expectedPreviousSetId;
        String payloadHash=hash("ACTIVATE|"+blueprintId+"|"+revision+"|"+previousToken);
        var prior=commands.findBySiteIdAndCommandKey(siteId,commandKey);
        if(prior.isPresent()) {
            var existing=prior.orElseThrow();
            if(!actor.equals(existing.actorId())||!payloadHash.equals(existing.payloadHash()))
                throw new BlueprintLifecycleException("IDEMPOTENCY_KEY_CONFLICT");
            verifyCommittedReceipt(siteId,commandKey,existing);
            return new Receipt(existing.resultSetId(),true);
        }
        String activated=lifecycle.activateWithCommand(siteId,blueprintId,revision,expectedPreviousSetId,commandKey);
        String id=existingId(siteId,commandKey);
        commands.saveAndFlush(new ActivationCommandEntity(id,siteId,commandKey,actor,payloadHash,activated));
        transitions.saveAndFlush(new ActivationTransitionEntity(UUID.randomUUID().toString(),id,
                siteId,actor,blueprintId.toString(),revision,expectedPreviousSetId,activated));
        return new Receipt(activated,false);
    }
    @Transactional(readOnly=true)
    public Receipt reconcile(String siteId,String commandKey) {
        validateKey(commandKey);
        String actor=authorize(siteId);
        var receipt=commands.findBySiteIdAndCommandKey(siteId,commandKey)
                .orElseThrow(()->new BlueprintLifecycleException("COMMAND_OUTCOME_NOT_OBSERVED_RETRY"));
        if(!receipt.actorId().equals(actor))
            throw new SecurityException("SITE_ACCESS_DENIED");
        verifyCommittedReceipt(siteId,commandKey,receipt);
        return new Receipt(receipt.resultSetId(),true);
    }
    /**
     * A missing receipt is NOT proof of rollback: an in-flight transaction may
     * still commit. Caller may safely retry using the SAME key and payload.
     * PostgreSQL connection failure propagates; never guess a negative outcome.
     */
    @Transactional(readOnly=true)
    public Observation observe(String siteId,String commandKey) {
        validateKey(commandKey);
        String actor=authorize(siteId);
        var found=commands.findBySiteIdAndCommandKey(siteId,commandKey);
        if(found.isEmpty())return new Observation(OutcomeState.NOT_OBSERVED_RETRY,null);
        var existing=found.orElseThrow();
        if(!actor.equals(existing.actorId()))throw new SecurityException("SITE_ACCESS_DENIED");
        verifyCommittedReceipt(siteId,commandKey,existing);
        return new Observation(OutcomeState.COMMITTED,existing.resultSetId());
    }

    /** Verify receipt, matching append-only audit and persisted activation target.
     * Never turn a partial/corrupted transaction into a successful reconciliation.
     */
    private void verifyCommittedReceipt(String siteId,String commandKey,ActivationCommandEntity receipt) {
        var audit=transitions.findByCommandId(existingId(siteId,commandKey))
                .orElseThrow(()->new IllegalStateException("MISSING_ACTIVATION_AUDIT"));
        var selected=sets.findById(receipt.resultSetId())
                .orElseThrow(()->new IllegalStateException("MISSING_ACTIVATION_SELECTION"));
        ledger.verifySite(siteId);
        var evidence=businessTransitions.findBySiteIdAndCommandKey(siteId,commandKey)
                .orElseThrow(()->new IllegalStateException("MISSING_BUSINESS_TRANSITION"));
        if(evidence.eventType()!=BusinessTransitionEntity.EventType.ACTIVATE
                ||!receipt.resultSetId().equals(evidence.newSetId())
                ||!receipt.actorId().equals(evidence.actorId())
                ||!siteId.equals(evidence.siteId()))
            throw new IllegalStateException("BUSINESS_TRANSITION_MISMATCH");
        if(!siteId.equals(receipt.siteId())
                ||!siteId.equals(audit.siteId())
                ||!siteId.equals(selected.siteId())
                ||!receipt.actorId().equals(audit.actorId())
                ||!receipt.resultSetId().equals(audit.newSetId())
                ||!selected.blueprintId().equals(audit.blueprintId())
                ||selected.revisionNo()!=audit.revisionNo()
                ||!Objects.equals(selected.previousSetId(),audit.previousSetId()))
            throw new IllegalStateException("ACTIVATION_AUDIT_MISMATCH");
    }
    private String authorize(String siteId) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()||
                "anonymousUser".equals(authentication.getPrincipal()))
            throw new SecurityException("UNAUTHENTICATED");
        String actor=authentication.getName();
        var member=members.findBySiteIdAndActorId(siteId,actor)
                .orElseThrow(()->new SecurityException("SITE_ACCESS_DENIED"));
        if(member.role()!=SiteMemberEntity.Role.ACTIVATOR
                &&member.role()!=SiteMemberEntity.Role.ADMIN)
            throw new SecurityException("SITE_ROLE_DENIED");
        return actor;
    }
    private static void validateKey(String key) {
        if(key==null||key.isBlank()||key.length()>128)
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_KEY");
    }
    private static String existingId(String site,String key) {
        return UUID.nameUUIDFromBytes((site.length()+":"+site+":"+key).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String hash(String input) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
