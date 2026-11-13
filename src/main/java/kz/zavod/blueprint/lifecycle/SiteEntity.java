package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;

@Entity
@Table(name="site")
public class SiteEntity {
    @Id @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="max_active_lines",nullable=false) private int maxActiveLines;
    @Column(name="active_set_id",length=36) private String activeSetId;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected SiteEntity(){}
    public SiteEntity(String siteId,int maxActiveLines) {
        if(siteId==null||siteId.isBlank()||maxActiveLines<1)throw new IllegalArgumentException("Invalid site policy");
        this.siteId=siteId;this.maxActiveLines=maxActiveLines;
    }
    public String siteId(){return siteId;}
    public int maxActiveLines(){return maxActiveLines;}
    public String activeSetId(){return activeSetId;}
    public void activate(String id){activeSetId=id;}
}
