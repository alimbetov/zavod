package kz.zavod.studio;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Metadata for an immutable-revision Blueprint workspace.
 * Archive is reversible only in a future reviewed workflow; never deletes history.
 */
@Entity
@Table(name="engineering_project",indexes={
    @Index(name="idx_engineering_project_site",columnList="site_id,status")
})
public class EngineeringProjectEntity {
    public enum Status { ACTIVE, ARCHIVED }
    @Id @Column(name="project_id",nullable=false,length=36) private String projectId;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="name",nullable=false,length=160) private String name;
    @Column(name="description",nullable=false,length=2000) private String description;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=16)
    private Status status;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Column(name="archived_at") private Instant archivedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;

    protected EngineeringProjectEntity(){}
    public EngineeringProjectEntity(String projectId,String siteId,String name,String description) {
        this.projectId=projectId;this.siteId=siteId;this.name=name;this.description=description;
        this.status=Status.ACTIVE;this.createdAt=Instant.now();this.updatedAt=createdAt;
    }
    public String projectId(){return projectId;}
    public String siteId(){return siteId;}
    public String name(){return name;}
    public String description(){return description;}
    public Status status(){return status;}
    public Instant createdAt(){return createdAt;}
    public Instant updatedAt(){return updatedAt;}
    public Instant archivedAt(){return archivedAt;}
    public long rowVersion(){return rowVersion;}
}
