package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="blueprint_approval",uniqueConstraints=@UniqueConstraint(
    name="uq_blueprint_approval_rev",columnNames={"blueprint_id","revision_no"}))
public class BlueprintApprovalEntity {
    public enum State { APPROVED, PUBLISHED }
    @Id @Column(name="id",nullable=false,length=64) private String id;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="revision_no",nullable=false) private long revisionNo;
    @Column(name="selected_units_json",nullable=false,columnDefinition="text") private String selectedUnitsJson;
    @Enumerated(EnumType.STRING) @Column(name="state",nullable=false,length=32) private State state;
    @Column(name="approved_by",nullable=false,length=128) private String approvedBy;
    @Column(name="approved_at",nullable=false) private Instant approvedAt;
    @Column(name="published_by",length=128) private String publishedBy;
    @Column(name="published_at") private Instant publishedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected BlueprintApprovalEntity(){}
    public BlueprintApprovalEntity(String site,String blueprint,long revision,String json,String actor){
        this.id=blueprint+":"+revision;
        this.siteId=site;this.blueprintId=blueprint;this.revisionNo=revision;
        this.selectedUnitsJson=json;this.state=State.APPROVED;this.approvedBy=actor;this.approvedAt=Instant.now();
    }
    public String siteId(){return siteId;}
    public long revisionNo(){return revisionNo;}
    public String selectedUnitsJson(){return selectedUnitsJson;}
    public State state(){return state;}
    public void publish(String actor) {
        if(state!=State.APPROVED)throw new BlueprintLifecycleException("NOT_APPROVED");
        state=State.PUBLISHED;publishedBy=actor;publishedAt=Instant.now();
    }
}
