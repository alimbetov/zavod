package kz.zavod.blueprint.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
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
        Instant at=Instant.now().truncatedTo(ChronoUnit.MICROS);
        String eventHash=fingerprint(eventId,siteId,Long.toString(sequence),type.name(),actor,
                blueprintId,Long.toString(revision),previousSetId,newSetId,reason,
                commandKey,contentHash,previousHash,at.toString());
        events.saveAndFlush(new BusinessTransitionEntity(eventId,siteId,sequence,type,
                actor,blueprintId,revision,previousSetId,newSetId,reason,commandKey,
                contentHash,previousHash,eventHash,at));
    }

    @Transactional(readOnly=true)
    public void verifySite(String siteId) {
        long expected=1L;
        String previousHash="";
        for(var event:events.findBySiteIdOrderBySequenceNoAsc(siteId)) {
            if(event.sequenceNo()!=expected||!previousHash.equals(event.previousEventHash()))
                throw new IllegalStateException("BUSINESS_LEDGER_CHAIN_BROKEN");
            String calculated=fingerprint(event.id(),event.siteId(),Long.toString(event.sequenceNo()),
                    event.eventType().name(),event.actorId(),event.blueprintId(),
                    Long.toString(event.revisionNo()),event.previousSetId(),event.newSetId(),
                    event.reason(),event.commandKey(),event.contentHash(),
                    event.previousEventHash(),event.createdAt().toString());
            if(!event.siteId().equals(siteId)||!calculated.equals(event.eventHash()))
                throw new IllegalStateException("BUSINESS_LEDGER_HASH_MISMATCH");
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
