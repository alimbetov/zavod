package kz.zavod.telemetry;

import java.util.List;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import kz.zavod.studio.StudioBlueprintService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kz.zavod.telemetry.TelemetryContracts.*;

/**
 * Internal registry only. Explicitly no public ingestion, PLC connection,
 * remote tag reads, credentials, or cross-site signal lookup.
 */
@Service
public class SignalRegistryService {
    private final SignalBindingRepository repository;
    private final StudioBlueprintService blueprints;
    private final JdbcTemplate jdbc;
    public SignalRegistryService(SignalBindingRepository repository,StudioBlueprintService blueprints,
                                 JdbcTemplate jdbc) {
        this.repository=repository;this.blueprints=blueprints;this.jdbc=jdbc;
    }
    @Transactional
    public SignalDefinition registerSimulated(SignalDefinition definition) {
        if(definition.sourceKind()!=SourceKind.SIMULATED)
            throw new IllegalArgumentException("OT_CONNECTOR_NOT_QUALIFIED");
        var revision=blueprints.revision(definition.siteId(),definition.blueprintId(),
                definition.blueprintRevision());
        var unit=revision.units().stream().filter(u->u.id().equals(definition.unitId()))
                .findFirst().orElseThrow(()->new IllegalArgumentException("UNKNOWN_SIGNAL_UNIT"));
        if(unit.equipment().stream().noneMatch(e->e.id().equals(definition.equipmentId())))
            throw new IllegalArgumentException("UNKNOWN_SIGNAL_EQUIPMENT");
        // Client-assigned UUIDs are NOT a license to JPA-merge an existing
        // mapping. Any in-place update could reattribute historic telemetry.
        // Database uniqueness is authoritative under concurrent registrations.
        int inserted=jdbc.update(
                "insert into telemetry_signal_binding "+
                "(id,site_id,blueprint_id,revision_no,unit_id,equipment_id,signal_key,"+
                "unit_code,source_kind,stale_after_ms,created_at) "+
                "values (?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing",
                definition.id().toString(),definition.siteId(),definition.blueprintId().toString(),
                definition.blueprintRevision(),definition.unitId(),definition.equipmentId(),
                definition.signalKey(),definition.unitCode(),definition.sourceKind().name(),
                definition.staleAfter().toMillis(),Timestamp.from(Instant.now()));
        if(inserted==1)return definition;
        var previous=repository.findById(definition.id().toString());
        if(previous.isPresent() && previous.orElseThrow().definition().equals(definition))
            return previous.orElseThrow().definition(); // exact retry only
        throw new TelemetryIngestionConflict("SIGNAL_BINDING_ID_OR_MAPPING_CONFLICT");
    }
    @Transactional(readOnly=true)
    public List<SignalDefinition> list(String siteId,UUID blueprintId,long revision) {
        blueprints.revision(siteId,blueprintId,revision);
        return repository.findBySiteIdAndBlueprintIdAndRevisionNoOrderByUnitIdAscEquipmentIdAscSignalKeyAsc(
                siteId,blueprintId.toString(),revision).stream()
                .map(SignalBindingEntity::definition).toList();
    }
}
