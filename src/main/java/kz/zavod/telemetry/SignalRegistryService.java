package kz.zavod.telemetry;

import java.util.List;
import java.util.UUID;
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
    public SignalRegistryService(SignalBindingRepository repository,StudioBlueprintService blueprints) {
        this.repository=repository;this.blueprints=blueprints;
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
        return repository.saveAndFlush(new SignalBindingEntity(definition)).definition();
    }
    @Transactional(readOnly=true)
    public List<SignalDefinition> list(String siteId,UUID blueprintId,long revision) {
        blueprints.revision(siteId,blueprintId,revision);
        return repository.findBySiteIdAndBlueprintIdAndRevisionNoOrderByUnitIdAscEquipmentIdAscSignalKeyAsc(
                siteId,blueprintId.toString(),revision).stream()
                .map(SignalBindingEntity::definition).toList();
    }
}
