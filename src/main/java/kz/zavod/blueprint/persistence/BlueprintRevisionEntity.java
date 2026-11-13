package kz.zavod.blueprint.persistence;

import jakarta.persistence.*;

/** Append-only engineering snapshot. Do not edit a committed revision in place. */
@Entity
@Table(name="blueprint_revision",uniqueConstraints={@UniqueConstraint(name="uq_blueprint_revision",columnNames={"blueprint_id","revision_no"})})
public class BlueprintRevisionEntity {
    @Id @Column(name="id",nullable=false,length=36) private String id;
    @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="revision_no",nullable=false) private long revisionNo;
    @Column(name="schema_version",nullable=false,length=32) private String schemaVersion;
    @Column(name="content_hash",nullable=false,length=64) private String contentHash;
    @Column(name="snapshot_json",nullable=false,columnDefinition="text") private String snapshotJson;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;

    protected BlueprintRevisionEntity() {}
    public BlueprintRevisionEntity(String id,String blueprintId,long revisionNo,String schemaVersion,String contentHash,String snapshotJson) {
        this.id=id;this.blueprintId=blueprintId;this.revisionNo=revisionNo;
        this.schemaVersion=schemaVersion;this.contentHash=contentHash;this.snapshotJson=snapshotJson;
    }
    public String id(){return id;}
    public String blueprintId(){return blueprintId;}
    public long revisionNo(){return revisionNo;}
    public String schemaVersion(){return schemaVersion;}
    public String contentHash(){return contentHash;}
    public String snapshotJson(){return snapshotJson;}
}
