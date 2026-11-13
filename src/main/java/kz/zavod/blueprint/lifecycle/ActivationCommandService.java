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
    private final SiteRepository sites;
    private final SiteMemberRepository members;
    private final ActivationCommandRepository commands;
    private final ActivationTransitionRepository transitions;
    private final BlueprintLifecycleService lifecycle;

    public ActivationCommandService(SiteRepository sites,SiteMemberRepository members,
                                    ActivationCommandRepository commands,
                                    ActivationTransitionRepository transitions,
                                    BlueprintLifecycleService lifecycle) {
        this.sites=sites;this.members=members;this.commands=commands;
        this.transitions=transitions;this.lifecycle=lifecycle;
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
        String payloadHash=hash(blueprintId+":"+revision+":"+
                (expectedPreviousSetId==null?"<none>":expectedPreviousSetId));
        var prior=commands.findBySiteIdAndCommandKey(siteId,commandKey);
        if(prior.isPresent()) {
            var existing=prior.orElseThrow();
            if(!actor.equals(existing.actorId())||!payloadHash.equals(existing.payloadHash()))
                throw new BlueprintLifecycleException("IDEMPOTENCY_KEY_CONFLICT");
            // Avoid depending on generated IDs: audit/command record is verified by matching result.
            // Find receipt by deterministic command primary key.
            var audit=transitions.findByCommandId(existingId(siteId,commandKey))
                    .orElseThrow(()->new IllegalStateException("MISSING_ACTIVATION_AUDIT"));
            if(!audit.newSetId().equals(existing.resultSetId()))
                throw new IllegalStateException("ACTIVATION_AUDIT_MISMATCH");
            return new Receipt(existing.resultSetId(),true);
        }
        String activated=lifecycle.activate(siteId,blueprintId,revision,expectedPreviousSetId);
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
                .orElseThrow(()->new BlueprintLifecycleException("COMMAND_OUTCOME_NOT_COMMITTED"));
        if(!receipt.actorId().equals(actor))
            throw new SecurityException("SITE_ACCESS_DENIED");
        var audit=transitions.findByCommandId(existingId(siteId,commandKey))
                .orElseThrow(()->new IllegalStateException("MISSING_ACTIVATION_AUDIT"));
        if(!audit.newSetId().equals(receipt.resultSetId()))
            throw new IllegalStateException("ACTIVATION_AUDIT_MISMATCH");
        return new Receipt(receipt.resultSetId(),true);
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
        return UUID.nameUUIDFromBytes((site+"|"+key).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String hash(String input) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
