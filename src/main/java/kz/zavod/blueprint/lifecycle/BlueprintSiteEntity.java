package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;

@Entity
@Table(name="blueprint_site")
public class BlueprintSiteEntity {
    @Id @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    protected BlueprintSiteEntity(){}
    public BlueprintSiteEntity(String blueprintId,String siteId) {
        this.blueprintId=blueprintId;this.siteId=siteId;
    }
    public String siteId(){return siteId;}
}
