package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Server-side activation quota. Draft contents must never determine site policy. */
public final class BlueprintActivationPolicy {
    private BlueprintActivationPolicy(){}
    public record Finding(String code,String subject) {}
    public static List<Finding> validate(ProcessBlueprint blueprint,Set<String> requestedActiveIds,int serverMaxActiveLines) {
        if(serverMaxActiveLines<1)throw new IllegalArgumentException("Invalid server policy");
        Objects.requireNonNull(requestedActiveIds);
        Map<String,ProcessUnit> byId=new HashMap<>();
        for(ProcessUnit unit:blueprint.units())byId.put(unit.id(),unit);
        List<Finding> findings=new ArrayList<>();
        long activeLines=0;
        for(String id:new TreeSet<>(requestedActiveIds)) {
            ProcessUnit unit=byId.get(id);
            if(unit==null)findings.add(new Finding("UNKNOWN_ACTIVE_UNIT",id));
            else if(unit.kind()==UnitKind.LINE)activeLines++;
        }
        if(activeLines>serverMaxActiveLines)
            findings.add(new Finding("ACTIVE_LINE_QUOTA_EXCEEDED",
                    "requested="+activeLines+",limit="+serverMaxActiveLines));
        return List.copyOf(findings);
    }
}
