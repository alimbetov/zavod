package kz.zavod.blueprint.domain;

import kz.zavod.lab.PlantGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** One-way bridge from R0.4 synthetic fixtures. Never used to issue PLC commands. */
public final class LabBlueprintAdapter {
    private LabBlueprintAdapter() {}

    public static ProcessBlueprint fromLab(UUID blueprintId, long revision, PlantGraph.Plant lab) {
        List<ProcessUnit> units = new ArrayList<>();
        for (PlantGraph.Line line : lab.lines()) {
            var equipment = line.nodes().stream().map(n -> new Equipment(n.id(),n.typeKey(),1,n.ports().stream()
                    .map(p->new Port(p.key(), Direction.valueOf(p.direction().name()),
                            FlowKind.valueOf(p.kind().name()),p.medium(),p.minLinks(),p.maxLinks()))
                    .toList())).toList();
            var edges = line.edges().stream().map(e->new Connection(e.sourceId(),e.sourcePort(),e.targetId(),e.targetPort())).toList();
            // R0.4 boundaries have no physical port binding. Preserve them as unbound DRAFT interfaces.
            var boundaries = line.boundaries().stream().map(b->new Boundary(b.key(),Direction.valueOf(b.direction().name()),
                    FlowKind.valueOf(b.kind().name()),b.medium(),null,null)).toList();
            var resources = line.demands().stream().map(d->new ResourceUse(d.assetId(),d.units())).toList();
            var measurements = line.measurements().stream().map(m->new Measurement(m.sensorId(),m.monitoredNodeId(),m.signalKey())).toList();
            units.add(new ProcessUnit(line.id(),UnitKind.LINE,line.profile().name(),equipment,edges,boundaries,resources,measurements,false));
        }
        var assets = lab.assets().stream().map(a->new SharedAsset(a.id(),a.capacityPerTick(),AssetMode.SHARED_CAPACITY)).toList();
        var transfers = lab.transfers().stream().map(t->new Transfer(t.fromLine(),t.fromPort(),t.toLine(),t.toPort())).toList();
        var deps = lab.dependencies().stream().map(d->new Dependency(d.fromLine(),d.toLine())).toList();
        return new ProcessBlueprint(blueprintId,revision,lab.maxActiveLines(),units,assets,transfers,deps);
    }
}
