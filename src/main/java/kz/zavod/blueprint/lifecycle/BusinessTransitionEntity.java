package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One append-only, site-sequenced business transition. Every row is part of a
 * tamper-evident hash chain; database privileges/trigger provide write protection.
 */
@Entity
@Table(name="business_transition",uniqueConstraints={
    @UniqueConstraint(name="uq_business_transition_sequence",columnNames={"site_id","sequence_no"}),
    @UniqueConstraint(name="uq_business_transition_command",columnNames={"site_id","command_key"})
})
public class BusinessTransitionEntity {
    public enum EventType { APPROVE, PUBLISH, ACTIVATE, REVOKE, RESTORE }
    @Id @Column(name="id",nullable=false,length=36) private String id;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="sequence_no",nullable=false) private long sequenceNo;
    @Enumerated(EnumType.STRING) @Column(name="event_type",nullable=false,length=24)
    private EventType eventType;
    @Column(name="actor_id",nullable=false,length=128) private String actorId;
    @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="revision_no",nullable=false) private long revisionNo;
    @Column(name="previous_set_id",length=36) private String previousSetId;
    @Column(name="new_set_id",length=36) private String newSetId;
    @Column(name="reason",length=1024) private String reason;
    @Column(name="command_key",length=128) private String commandKey;
    @Column(name="content_hash",nullable=false,length=64) private String contentHash;
    @Column(name="previous_event_hash",nullable=false,length=64) private String previousEventHash;
    @Column(name="event_hash",nullable=false,length=64) private String eventHash;
    @Column(name="created_at",nullable=false,columnDefinition="timestamptz") private Instant createdAt;
    protected BusinessTransitionEntity() {}
    public BusinessTransitionEntity(String id,String siteId,long sequenceNo,EventType type,
                                    String actorId,String blueprintId,long revisionNo,
                                    String previousSetId,String newSetId,String reason,String commandKey,
                                    String contentHash,String previousEventHash,String eventHash,Instant createdAt) {
        this.id=id;this.siteId=siteId;this.sequenceNo=sequenceNo;this.eventType=type;
        this.actorId=actorId;this.blueprintId=blueprintId;this.revisionNo=revisionNo;
        this.previousSetId=previousSetId;this.newSetId=newSetId;this.reason=reason;
        this.commandKey=commandKey;this.contentHash=contentHash;
        this.previousEventHash=previousEventHash;this.eventHash=eventHash;this.createdAt=createdAt;
    }
    public String id(){return id;}
    public String siteId(){return siteId;}
    public long sequenceNo(){return sequenceNo;}
    public EventType eventType(){return eventType;}
    public String actorId(){return actorId;}
    public String blueprintId(){return blueprintId;}
    public long revisionNo(){return revisionNo;}
    public String previousSetId(){return previousSetId;}
    public String newSetId(){return newSetId;}
    public String reason(){return reason;}
    public String commandKey(){return commandKey;}
    public String contentHash(){return contentHash;}
    public String previousEventHash(){return previousEventHash;}
    public String eventHash(){return eventHash;}
    public Instant createdAt(){return createdAt;}
}
