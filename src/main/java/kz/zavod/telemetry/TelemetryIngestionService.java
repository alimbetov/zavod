package kz.zavod.telemetry;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kz.zavod.engineering.EngineeringUnits;
import kz.zavod.studio.StudioBlueprintService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kz.zavod.telemetry.TelemetryContracts.*;

/**
 * TF-02: internal, read-only SIMULATED telemetry storage, not a network/PLC endpoint.
 *
 * PostgreSQL's unique constraint owns idempotency. INSERT ON CONFLICT avoids putting
 * the enclosing transaction into aborted state, so a retry can read the committed row
 * even when another concurrent writer wins the event identifier.
 */
@Service
public class TelemetryIngestionService {
    public record Incoming(String eventId,BigDecimal value,String unitCode,Instant sourceTime,Quality quality) {
        public Incoming {
            if(eventId==null||eventId.isBlank()||eventId.length()>128)
                throw new IllegalArgumentException("INVALID_EVENT_ID");
            Objects.requireNonNull(value,"value");
            Objects.requireNonNull(sourceTime,"sourceTime");
            Objects.requireNonNull(quality,"quality");
            EngineeringUnits.require(unitCode);
            if(value.precision()>38||value.scale()>18)
                throw new IllegalArgumentException("UNBOUNDED_SAMPLE_PRECISION");
        }
    }
    public record Receipt(TelemetrySample sample,boolean duplicate) {}
    private final JdbcTemplate jdbc;
    private final SignalBindingRepository bindings;
    private final StudioBlueprintService blueprints;

    public TelemetryIngestionService(JdbcTemplate jdbc,SignalBindingRepository bindings,
                                     StudioBlueprintService blueprints) {
        this.jdbc=jdbc;this.bindings=bindings;this.blueprints=blueprints;
    }

    /** Exactly-once persistence per signal/event key, not exactly-once network delivery. */
    @Transactional
    public Receipt ingestSimulated(String siteId,UUID blueprintId,long revision,
                                   UUID signalId,Incoming incoming) {
        var binding=requireBinding(siteId,blueprintId,revision,signalId);
        if(binding.sourceKind()!=SourceKind.SIMULATED)
            throw new IllegalArgumentException("OT_INGESTION_NOT_QUALIFIED");
        Instant received=Instant.now();
        if(incoming.sourceTime().isAfter(received.plusSeconds(30)))
            throw new IllegalArgumentException("FUTURE_SIGNAL_TIMESTAMP");
        if(EngineeringUnits.dimension(incoming.unitCode())!=
                EngineeringUnits.dimension(binding.unitCode()))
            throw new IllegalArgumentException("INCOMPATIBLE_SIGNAL_DIMENSION");

        int inserted=jdbc.update(
                "insert into telemetry_sample "+
                "(signal_id,source_event_id,source_kind,sample_value,unit_code,source_time,received_at,quality) "+
                "values (?,?,?,?,?,?,?,?) on conflict (signal_id,source_event_id) do nothing",
                signalId.toString(),incoming.eventId(),SourceKind.SIMULATED.name(),
                incoming.value(),incoming.unitCode(),utc(incoming.sourceTime()),utc(received),
                incoming.quality().name());

        TelemetrySample stored=jdbc.queryForObject(
                "select signal_id,source_event_id,source_kind,sample_value,unit_code,"+
                "source_time,received_at,quality from telemetry_sample "+
                "where signal_id=? and source_event_id=?",
                (rs,n)->sample(rs),signalId.toString(),incoming.eventId());
        if(stored==null)throw new IllegalStateException("MISSING_INGESTION_RECEIPT");
        if(stored.sourceKind()!=SourceKind.SIMULATED
                ||stored.value().compareTo(incoming.value())!=0
                ||!stored.unitCode().equals(incoming.unitCode())
                ||!stored.sourceTime().equals(incoming.sourceTime())
                ||stored.quality()!=incoming.quality())
            throw new TelemetryIngestionConflict("EVENT_ID_PAYLOAD_CONFLICT");
        return new Receipt(stored,inserted==0);
    }

    @Transactional(readOnly=true)
    public List<TelemetrySample> history(String siteId,UUID blueprintId,long revision,
                                         UUID signalId,Instant from,Instant to,int limit) {
        requireBinding(siteId,blueprintId,revision,signalId);
        if(from==null||to==null||!from.isBefore(to)||limit<1||limit>500)
            throw new IllegalArgumentException("INVALID_TELEMETRY_QUERY");
        return List.copyOf(jdbc.query(
                "select signal_id,source_event_id,source_kind,sample_value,unit_code,"+
                "source_time,received_at,quality from telemetry_sample "+
                "where signal_id=? and source_time>=? and source_time<? "+
                "order by source_time asc,source_event_id asc limit ?",
                (rs,n)->sample(rs),signalId.toString(),utc(from),utc(to),limit));
    }

    @Transactional(readOnly=true)
    public Reading latest(String siteId,UUID blueprintId,long revision,UUID signalId,Instant now) {
        var definition=requireBinding(siteId,blueprintId,revision,signalId);
        Objects.requireNonNull(now,"now");
        var samples=jdbc.query(
                "select signal_id,source_event_id,source_kind,sample_value,unit_code,"+
                "source_time,received_at,quality from telemetry_sample "+
                "where signal_id=? and source_time<=? "+
                "order by source_time desc,received_at desc,source_event_id desc limit 1",
                (rs,n)->sample(rs),signalId.toString(),utc(now));
        return TelemetryQualityEvaluator.evaluate(definition,
                samples.isEmpty()?null:samples.get(0),now);
    }

    private SignalDefinition requireBinding(String siteId,UUID blueprintId,long revision,UUID signalId) {
        blueprints.revision(siteId,blueprintId,revision); // authoritative site/snapshot ownership
        var definition=bindings.findById(signalId.toString())
                .orElseThrow(()->new IllegalArgumentException("SIGNAL_NOT_FOUND")).definition();
        if(!definition.siteId().equals(siteId)
                ||!definition.blueprintId().equals(blueprintId)
                ||definition.blueprintRevision()!=revision)
            throw new IllegalArgumentException("SIGNAL_NOT_FOUND");
        return definition;
    }
    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
    private static TelemetrySample sample(ResultSet rs) throws SQLException {
        return new TelemetrySample(UUID.fromString(rs.getString("signal_id")),
                SourceKind.valueOf(rs.getString("source_kind")),
                rs.getString("source_event_id"),rs.getBigDecimal("sample_value"),
                rs.getString("unit_code"),
                rs.getObject("source_time",OffsetDateTime.class).toInstant(),
                rs.getObject("received_at",OffsetDateTime.class).toInstant(),
                Quality.valueOf(rs.getString("quality")));
    }
}
