package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stable receipt of a committed activation business command.
 * The unique (site,commandKey) constraint is the authoritative replay key.
 */
@Entity
@Table(name="activation_command",uniqueConstraints={
    @UniqueConstraint(name="uq_activation_site_key",columnNames={"site_id","command_key"})
})
public class ActivationCommandEntity {
    @Id @Column(name="id",nullable=false,length=36) private String id;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="command_key",nullable=false,length=128) private String commandKey;
    @Column(name="actor_id",nullable=false,length=128) private String actorId;
    @Column(name="payload_hash",nullable=false,length=64) private String payloadHash;
    @Column(name="result_set_id",nullable=false,length=36) private String resultSetId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ActivationCommandEntity(){}
    public ActivationCommandEntity(String id,String siteId,String commandKey,String actorId,
                                   String payloadHash,String resultSetId) {
        this.id=id;this.siteId=siteId;this.commandKey=commandKey;this.actorId=actorId;
        this.payloadHash=payloadHash;this.resultSetId=resultSetId;this.createdAt=Instant.now();
    }
    public String actorId(){return actorId;}
    public String payloadHash(){return payloadHash;}
    public String resultSetId(){return resultSetId;}
}
