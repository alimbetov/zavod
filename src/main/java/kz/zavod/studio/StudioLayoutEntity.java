package kz.zavod.studio;

import jakarta.persistence.*;

/** Independent presentation layout. No modification of engineering Blueprint revisions. */
@Entity
@Table(name="blueprint_view_layout")
public class StudioLayoutEntity {
    @Id @Column(name="id",nullable=false,length=160) private String id;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="view_id",nullable=false,length=64) private String viewId;
    @Column(name="layout_version",nullable=false) private long layoutVersion;
    @Column(name="blueprint_revision",nullable=false) private long blueprintRevision;
    @Column(name="layout_json",nullable=false,columnDefinition="text") private String layoutJson;
    protected StudioLayoutEntity(){}
    public StudioLayoutEntity(String id,String siteId,String blueprintId,String viewId,
                              long version,long blueprintRevision,String layoutJson){
        this.id=id;this.siteId=siteId;this.blueprintId=blueprintId;this.viewId=viewId;
        this.layoutVersion=version;this.blueprintRevision=blueprintRevision;this.layoutJson=layoutJson;
    }
    public String siteId(){return siteId;}
    public String blueprintId(){return blueprintId;}
    public String viewId(){return viewId;}
    public long layoutVersion(){return layoutVersion;}
    public long blueprintRevision(){return blueprintRevision;}
    public String layoutJson(){return layoutJson;}
}
