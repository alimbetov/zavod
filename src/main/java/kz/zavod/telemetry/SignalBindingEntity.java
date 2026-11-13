package kz.zavod.telemetry;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import static kz.zavod.telemetry.TelemetryContracts.*;

/** Version-pinned, read-only signal mapping. No PLC credentials or live data. */
@Entity
@Table(name="telemetry_signal_binding",uniqueConstraints={
    @UniqueConstraint(name="uq_telemetry_signal_mapping",
        columnNames={"site_id","blueprint_id","revision_no","unit_id","equipment_id","signal_key"})
})
public class SignalBindingEntity {
    @Id @Column(name="id",nullable=false,length=36) private String id;
    @Column(name="site_id",nullable=false,length=64) private String siteId;
    @Column(name="blueprint_id",nullable=false,length=36) private String blueprintId;
    @Column(name="revision_no",nullable=false) private long revisionNo;
    @Column(name="unit_id",nullable=false,length=128) private String unitId;
    @Column(name="equipment_id",nullable=false,length=128) private String equipmentId;
    @Column(name="signal_key",nullable=false,length=128) private String signalKey;
    @Column(name="unit_code",nullable=false,length=32) private String unitCode;
    @Enumerated(EnumType.STRING) @Column(name="source_kind",nullable=false,length=32)
    private SourceKind sourceKind;
    @Column(name="stale_after_ms",nullable=false) private long staleAfterMs;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected SignalBindingEntity() {}
    public SignalBindingEntity(SignalDefinition def) {
        id=def.id().toString();siteId=def.siteId();blueprintId=def.blueprintId().toString();
        revisionNo=def.blueprintRevision();unitId=def.unitId();equipmentId=def.equipmentId();
        signalKey=def.signalKey();unitCode=def.unitCode();sourceKind=def.sourceKind();
        staleAfterMs=def.staleAfter().toMillis();createdAt=Instant.now();
    }
    public String siteId(){return siteId;}
    public SignalDefinition definition() {
        return new SignalDefinition(UUID.fromString(id),siteId,UUID.fromString(blueprintId),
                revisionNo,unitId,equipmentId,signalKey,unitCode,sourceKind,
                java.time.Duration.ofMillis(staleAfterMs));
    }
}
