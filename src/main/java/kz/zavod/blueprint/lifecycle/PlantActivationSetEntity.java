package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;

/** Immutable historical activation selection; no PLC operations. */
@Entity
@Table(name="plant_activation_set")
public class PlantActivationSetEntity {
    @Id @Column(name="id",nullable=false,length=36) private String id;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="revision_no",nullable=false) private long revisionNo;
    @Column(name="selected_units_json",nullable=false,columnDefinition="text") private String selectedUnitsJson;
    @Column(name="previous_set_id",length=36) private String previousSetId;
    @Column(name="restored_from_set_id",length=36) private String restoredFromSetId;
    @Column(name="change_reason",length=1024) private String changeReason;
    @Column(name="activated_by",nullable=false,length=128) private String activatedBy;
    @Column(name="activated_at",nullable=false) private Instant activatedAt;
    protected PlantActivationSetEntity(){}
    public PlantActivationSetEntity(String id,String site,String blueprint,long revision,String units,String previous,String actor){
        this(id,site,blueprint,revision,units,previous,actor,null,null);
    }
    public PlantActivationSetEntity(String id,String site,String blueprint,long revision,String units,String previous,String actor,
                                    String restoredFromSetId,String changeReason){
        this.restoredFromSetId=restoredFromSetId;this.changeReason=changeReason;
        this.id=id;this.siteId=site;this.blueprintId=blueprint;this.revisionNo=revision;
        this.selectedUnitsJson=units;this.previousSetId=previous;this.activatedBy=actor;this.activatedAt=Instant.now();
    }
    public String id(){return id;}
    public String siteId(){return siteId;}
    public String blueprintId(){return blueprintId;}
    public long revisionNo(){return revisionNo;}
    public String selectedUnitsJson(){return selectedUnitsJson;}
    public String previousSetId(){return previousSetId;}
    public String restoredFromSetId(){return restoredFromSetId;}
    public String changeReason(){return changeReason;}
}
