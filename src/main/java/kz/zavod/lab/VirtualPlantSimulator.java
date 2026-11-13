package kz.zavod.lab;

import java.util.*;
import static kz.zavod.lab.PlantGraph.*;

/** Discrete-tick virtual availability emulator, NOT a physical simulation or PLC runtime. */
public final class VirtualPlantSimulator {
    private VirtualPlantSimulator() {}
    public record Result(int ticks,int lines,int totalUnits,int upstreamWaits,int resourceWaits,
                         int idleTicks,Map<String,Integer> unitsByLine) {
        public Result { unitsByLine=Map.copyOf(unitsByLine); }
    }

    public static Result run(Plant plant,int ticks) {
        if(ticks<1)throw new IllegalArgumentException("ticks must be positive");
        List<Finding> findings=PlantGraph.validate(plant);
        if(!findings.isEmpty())throw new IllegalArgumentException("Invalid plant: "+findings);
        Map<String,Integer> produced=new LinkedHashMap<>();
        for(Line line:plant.lines()) produced.put(line.id(),0);
        Map<Transfer,Integer> buffers=new HashMap<>();
        for(Transfer tr:plant.transfers())buffers.put(tr,0);
        Map<String,List<Transfer>> inbound=new HashMap<>(),outbound=new HashMap<>();
        for(Transfer t:plant.transfers()) {
            inbound.computeIfAbsent(t.toLine(),k->new ArrayList<>()).add(t);
            outbound.computeIfAbsent(t.fromLine(),k->new ArrayList<>()).add(t);
        }
        Map<String,List<String>> deps=new HashMap<>();
        for(Dependency d:plant.dependencies())deps.computeIfAbsent(d.toLine(),k->new ArrayList<>()).add(d.fromLine());
        int upstreamWaits=0,resourceWaits=0,idleTicks=0;
        for(int tick=0;tick<ticks;tick++) {
            Map<String,Integer> remaining=new HashMap<>();
            for(SharedAsset asset:plant.assets())remaining.put(asset.id(),asset.capacityPerTick());
            int executed=0;
            for(int pos=0;pos<plant.lines().size();pos++) {
                Line l=plant.lines().get((pos+tick)%plant.lines().size());
                if(deps.getOrDefault(l.id(),List.of()).stream().anyMatch(up->produced.get(up)==0)
                   || inbound.getOrDefault(l.id(),List.of()).stream().anyMatch(t->buffers.get(t)==0)) {
                    upstreamWaits++;continue;
                }
                if(l.demands().stream().anyMatch(r->remaining.get(r.assetId())<r.units())) {
                    resourceWaits++;continue;
                }
                for(ResourceDemand d:l.demands())remaining.merge(d.assetId(),-d.units(),Integer::sum);
                for(Transfer t:inbound.getOrDefault(l.id(),List.of()))buffers.merge(t,-1,Integer::sum);
                produced.merge(l.id(),1,Integer::sum);
                for(Transfer t:outbound.getOrDefault(l.id(),List.of()))buffers.merge(t,1,Integer::sum);
                executed++;
            }
            if(executed==0)idleTicks++;
        }
        return new Result(ticks,plant.lines().size(),produced.values().stream().mapToInt(Integer::intValue).sum(),
                upstreamWaits,resourceWaits,idleTicks,produced);
    }
}
