package kz.zavod.blueprint.persistence;

import jakarta.persistence.*;

/** Small lockable head row; revision snapshots remain append-only. */
@Entity
@Table(name="blueprint_head")
public class BlueprintHeadEntity {
    @Id @Column(name="blueprint_id",nullable=false,length=36)
    private String blueprintId;
    @Column(name="revision_no",nullable=false)
    private long revisionNo;
    @Version @Column(name="row_version",nullable=false)
    private long rowVersion;
    protected BlueprintHeadEntity(){}
    public BlueprintHeadEntity(String id,long revision) {
        this.blueprintId=id;
        this.revisionNo=revision;
    }
    public String blueprintId(){return blueprintId;}
    public long revisionNo(){return revisionNo;}
}
