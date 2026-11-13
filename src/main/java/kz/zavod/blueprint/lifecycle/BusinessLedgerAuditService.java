package kz.zavod.blueprint.lifecycle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit offline full-ledger audit. Never invoked on command-replay hot paths.
 * Reports the chain tip for independent storage/retention by the operator.
 * Not a cryptographic signature: a trusted external checkpoint store is required
 * to detect deletion or rewriting of the entire database history.
 */
@Service
public class BusinessLedgerAuditService {
    public record Anchor(long sequenceNo,String eventHash) {}
    public record SiteResult(String siteId,String status,long sequenceNo,
                             String eventHash,String errorCode) {}
    public record AuditReport(Instant checkedAt,List<SiteResult> sites,boolean passed) {}

    private final BusinessTransitionLedger ledger;
    private final BusinessTransitionRepository events;
    private final SiteRepository sites;
    public BusinessLedgerAuditService(BusinessTransitionLedger ledger,
                                      BusinessTransitionRepository events,
                                      SiteRepository sites) {
        this.ledger=ledger;this.events=events;this.sites=sites;
    }

    @Transactional(readOnly=true)
    public AuditReport auditAll(Map<String,Anchor> trustedAnchors) {
        var results=new ArrayList<SiteResult>();
        var allSites=sites.findAll().stream().map(SiteEntity::siteId).sorted().toList();
        var known=Set.copyOf(allSites);
        // A missing previously anchored site must never disappear silently.
        for(String id:trustedAnchors.keySet()) {
            if(!known.contains(id))
                results.add(new SiteResult(id,"FAIL",0,"","ANCHOR_SITE_MISSING"));
        }
        for(String site:allSites) {
            try {
                ledger.verifySite(site);
                var tip=events.findTopBySiteIdOrderBySequenceNoDesc(site);
                long seq=tip.map(BusinessTransitionEntity::sequenceNo).orElse(0L);
                String hash=tip.map(BusinessTransitionEntity::eventHash).orElse("");
                var anchor=trustedAnchors.get(site);
                if(anchor!=null) {
                    if(seq<anchor.sequenceNo())throw new IllegalStateException("ANCHOR_HISTORY_TRUNCATED");
                    if(anchor.sequenceNo()>0) {
                        var pinned=events.findBySiteIdAndSequenceNo(site,anchor.sequenceNo())
                                .orElseThrow(()->new IllegalStateException("ANCHOR_EVENT_MISSING"));
                        if(!pinned.eventHash().equals(anchor.eventHash()))
                            throw new IllegalStateException("ANCHOR_HASH_MISMATCH");
                    } else if(!anchor.eventHash().isEmpty()) {
                        throw new IllegalStateException("ANCHOR_INVALID");
                    }
                }
                results.add(new SiteResult(site,"PASS",seq,hash,null));
            } catch(RuntimeException error) {
                // Do not leak SQL, credentials, or unbounded exception text to audit reports.
                String code=error.getMessage();
                if(code==null||!code.matches("[A-Z][A-Z0-9_]{2,80}"))
                    code="LEDGER_AUDIT_FAILED";
                results.add(new SiteResult(site,"FAIL",0,"",code));
            }
        }
        return new AuditReport(Instant.now(),List.copyOf(results),
                results.stream().allMatch(x->"PASS".equals(x.status())));
    }
}
