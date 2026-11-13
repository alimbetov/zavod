package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;

@Entity
@Table(name="site_member",uniqueConstraints=@UniqueConstraint(
    name="uq_site_member",columnNames={"site_id","actor_id"}))
public class SiteMemberEntity {
    public enum Role { VIEWER, REVIEWER, PUBLISHER, ACTIVATOR, ADMIN }
    @Id @Column(name="id",nullable=false,length=36) private String id;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="actor_id",nullable=false,length=128) private String actorId;
    @Enumerated(EnumType.STRING) @Column(name="role",nullable=false,length=32) private Role role;
    protected SiteMemberEntity(){}
    public SiteMemberEntity(String id,String siteId,String actorId,Role role){
        this.id=id;this.siteId=siteId;this.actorId=actorId;this.role=role;
    }
    public Role role(){return role;}
}
