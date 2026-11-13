package kz.zavod.blueprint.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.MDC;
import java.util.UUID;
import kz.zavod.blueprint.persistence.BlueprintDraftService;
import kz.zavod.blueprint.persistence.BlueprintRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic business ledger. Call only after locking Site row and inside the same
 * lifecycle transaction. NEVER use REQUIRES_NEW: an audit-only commit is unsafe.
 */
@Service
public class BusinessTransitionLedger {
    private final BusinessTransitionRepository events;
    private final BlueprintRevisionRepository revisions;
    private final BlueprintDraftService drafts;
    public BusinessTransitionLedger(BusinessTransitionRepository events,
                                    BlueprintRevisionRepository revisions,
                                    BlueprintDraftService drafts) {
        this.events=events;this.revisions=revisions;this.drafts=drafts;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void append(String siteId,BusinessTransitionEntity.EventType type,String actor,
                       String blueprintId,long revision,String previousSetId,String newSetId,
                       String reason,String commandKey) {
        // Verify immutable revision bytes before anchoring the ledger hash.
        drafts.loadRevision(UUID.fromString(blueprintId),revision);
        String contentHash=revisions.findByBlueprintIdAndRevisionNo(blueprintId,revision)
                .orElseThrow(()->new IllegalStateException("MISSING_BLUEPRINT_REVISION")).contentHash();
        var previous=events.findTopBySiteIdOrderBySequenceNoDesc(siteId);
        long sequence=previous.map(p->p.sequenceNo()+1).orElse(1L);
        String previousHash=previous.map(BusinessTransitionEntity::eventHash).orElse("");
        String eventId=UUID.randomUUID().toString();
        String reasonCode=type.name()+"_TRANSITION";
        // Prefer trusted request correlation when supplied; otherwise mint server-side ID.
        String supplied=MDC.get("correlationId");
        String correlationId=(supplied!=null&&!supplied.isBlank()&&supplied.length()<=128)
                ?supplied:eventId;
        Instant at=Instant.now().truncatedTo(ChronoUnit.MICROS);
        String eventHash=fingerprint(eventId,siteId,Long.toString(sequence),type.name(),actor,
                blueprintId,Long.toString(revision),previousSetId,newSetId,reason,
                commandKey,reasonCode,correlationId,contentHash,previousHash,at.toString());
        events.saveAndFlush(new BusinessTransitionEntity(eventId,siteId,sequence,type,
                actor,blueprintId,revision,previousSetId,newSetId,reason,commandKey,
                reasonCode,correlationId,contentHash,previousHash,eventHash,at));
    }

    /**
     * Bounded synchronous qualification of an idempotent activation receipt.
     * Verify the target, its immediate chain links and their revision digests.
     * Complexity is O(1) per replay; full history integrity remains an explicit
     * offline verifySite() operation and MUST be scheduled independently.
     */
    @Transactional(readOnly=true)
    public void verifyReceiptEvent(String siteId,String commandKey) {
        var target=events.findBySiteIdAndCommandKey(siteId,commandKey)
                .orElseThrow(()->new IllegalStateException("MISSING_BUSINESS_TRANSITION"));
        validateEvent(siteId,target);
        if(target.sequenceNo()==1) {
            if(!target.previousEventHash().isEmpty())
                throw new IllegalStateException("BUSINESS_LEDGER_CHAIN_BROKEN");
        } else {
            var previous=events.findBySiteIdAndSequenceNo(siteId,target.sequenceNo()-1)
                    .orElseThrow(()->new IllegalStateException("BUSINESS_LEDGER_CHAIN_BROKEN"));
            validateEvent(siteId,previous);
            if(!previous.eventHash().equals(target.previousEventHash()))
                throw new IllegalStateException("BUSINESS_LEDGER_CHAIN_BROKEN");
        }
        events.findBySiteIdAndSequenceNo(siteId,target.sequenceNo()+1).ifPresent(next->{
            validateEvent(siteId,next);
            if(!target.eventHash().equals(next.previousEventHash()))
                throw new IllegalStateException("BUSINESS_LEDGER_CHAIN_BROKEN");
        });
    }

    private void validateEvent(String siteId,BusinessTransitionEntity event) {
        String calculated=fingerprint(event.id(),event.siteId(),Long.toString(event.sequenceNo()),
                event.eventType().name(),event.actorId(),event.blueprintId(),
                Long.toString(event.revisionNo()),event.previousSetId(),event.newSetId(),
                event.reason(),event.commandKey(),event.reasonCode(),event.correlationId(),
                event.contentHash(),event.previousEventHash(),event.createdAt().toString());
        if(!event.siteId().equals(siteId)||!calculated.equals(event.eventHash()))
            throw new IllegalStateException("BUSINESS_LEDGER_HASH_MISMATCH");
        UUID revisionId=UUID.fromString(event.blueprintId());
        drafts.loadRevision(revisionId,event.revisionNo());
        String authoritative=revisions.findByBlueprintIdAndRevisionNo(
                event.blueprintId(),event.revisionNo())
                .orElseThrow(()->new IllegalStateException("MISSING_BLUEPRINT_REVISION"))
                .contentHash();
        if(!authoritative.equals(event.contentHash()))
            throw new IllegalStateException("BUSINESS_LEDGER_REVISION_HASH_MISMATCH");
    }

    @Transactional(readOnly=true)
    public void verifySite(String siteId) {
        long expected=1L;
        String previousHash="";
        for(var event:events.findBySiteIdOrderBySequenceNoAsc(siteId)) {
            if(event.sequenceNo()!=expected||!previousHash.equals(event.previousEventHash()))
                throw new IllegalStateException("BUSINESS_LEDGER_CHAIN_BROKEN");
            validateEvent(siteId,event);
            previousHash=event.eventHash();
            expected++;
        }
    }

    private static String fingerprint(String... fields) {
        var input=new StringBuilder("ZAVOD-G4.2-LEDGER-V1|");
        for(String field:fields) {
            if(field==null)input.append("N|");
            else input.append("V").append(field.length()).append(':').append(field).append('|');
        }
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
