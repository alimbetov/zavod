package kz.zavod.telemetry;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA metadata for managed schema validation; writes use PostgreSQL
 * ON CONFLICT through JDBC to make concurrent retries atomic.
 */
@Entity
@Table(name="telemetry_sample",indexes={
    @Index(name="idx_telemetry_sample_time",columnList="signal_id,source_time,source_event_id")
})
@IdClass(TelemetrySampleEntity.Key.class)
public class TelemetrySampleEntity {
    public static class Key implements Serializable {
        public String signalId;
        public String sourceEventId;
        public Key(){}
        public Key(String signalId,String sourceEventId) {
            this.signalId=signalId;this.sourceEventId=sourceEventId;
        }
        @Override public boolean equals(Object o) {
            if(this==o)return true;
            if(!(o instanceof Key key))return false;
            return Objects.equals(signalId,key.signalId)
                    &&Objects.equals(sourceEventId,key.sourceEventId);
        }
        @Override public int hashCode(){return Objects.hash(signalId,sourceEventId);}
    }
    @Id @Column(name="signal_id",nullable=false,length=36)
    private String signalId;
    @Id @Column(name="source_event_id",nullable=false,length=128)
    private String sourceEventId;
    @Column(name="source_kind",nullable=false,length=32)
    private String sourceKind;
    @Column(name="sample_value",nullable=false,precision=38,scale=18)
    private BigDecimal sampleValue;
    @Column(name="unit_code",nullable=false,length=32)
    private String unitCode;
    @Column(name="source_time",nullable=false,columnDefinition="timestamptz")
    private Instant sourceTime;
    @Column(name="received_at",nullable=false,columnDefinition="timestamptz")
    private Instant receivedAt;
    @Column(name="quality",nullable=false,length=32)
    private String quality;
    protected TelemetrySampleEntity(){}
}
