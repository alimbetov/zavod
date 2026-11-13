package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only receipt-matched audit for a committed engineering activation. */
@Entity
@Table(name="activation_transition",uniqueConstraints={
    @UniqueConstraint(name="uq_activation_transition_command",columnNames={"command_id"})
})
public class ActivationTransitionEntity {
    @Id @Column(name="id",nullable=false,length=36) private String id;
    @Column(name="command_id",nullable=false,length=36) private String commandId;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="actor_id",nullable=false,length=128) private String actorId;
    @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="revision_no",nullable=false) private long revisionNo;
    @Column(name="previous_set_id",length=36) private String previousSetId;
    @Column(name="new_set_id",nullable=false,length=36) private String newSetId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ActivationTransitionEntity(){}
    public ActivationTransitionEntity(String id,String commandId,String siteId,String actorId,
                                      String blueprintId,long revisionNo,String previousSetId,
                                      String newSetId) {
        this.id=id;this.commandId=commandId;this.siteId=siteId;this.actorId=actorId;
        this.blueprintId=blueprintId;this.revisionNo=revisionNo;
        this.previousSetId=previousSetId;this.newSetId=newSetId;this.createdAt=Instant.now();
    }
    public String newSetId(){return newSetId;}
}
