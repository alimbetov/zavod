package kz.zavod.studio;

import kz.zavod.blueprint.domain.ProcessBlueprint;
import java.util.HashSet;
import java.util.Objects;

/** Bound HTTP input; it is not the product's active-line quota. */
public final class StudioInputLimits {
    private StudioInputLimits(){}
    public static void check(ProcessBlueprint snapshot){
        Objects.requireNonNull(snapshot);
        if(snapshot.units().size()>2000||snapshot.transfers().size()>10000
                ||snapshot.dependencies().size()>10000||snapshot.sharedAssets().size()>5000)
            throw new IllegalArgumentException("BLUEPRINT_INPUT_LIMIT");
        long equipment=0,connections=0;
        var unitIds=new HashSet<String>();
        for(var unit:snapshot.units()){
            if(!unitIds.add(unit.id()))throw new IllegalArgumentException("DUPLICATE_UNIT");
            equipment+=unit.equipment().size();connections+=unit.connections().size();
            if(unit.equipment().size()>2000||unit.connections().size()>10000
                    ||unit.boundaries().size()>2000||unit.resources().size()>2000)
                throw new IllegalArgumentException("PROCESS_UNIT_INPUT_LIMIT:"+unit.id());
        }
        if(equipment>20000||connections>50000)throw new IllegalArgumentException("BLUEPRINT_INPUT_LIMIT");
    }
}
