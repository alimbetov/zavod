package kz.zavod.blueprint;

import java.util.*;
import kz.zavod.blueprint.domain.BlueprintValidator;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Seeded randomized property scenarios with reproducible exact seed.
 * Standalone JDK runner; not a replacement for jqwik/shrinking or HIL tests.
 */
public final class BlueprintFuzzSmoke {
    private BlueprintFuzzSmoke(){}
    public static void main(String[] args) {
        int count=10_000;
        for(int seed=0;seed<count;seed++) {
            Random random=new Random(0x5A70D0L+seed*31L);
            int size=2+random.nextInt(8);
            int mode=seed%4;
            List<Equipment> nodes=new ArrayList<>();
            List<Connection> links=new ArrayList<>();
            for(int i=0;i<size;i++) {
                List<Port> ports=new ArrayList<>();
                if(i>0)ports.add(new Port("in",Direction.IN,FlowKind.MATERIAL,
                        mode==1&&i==size-1?"oil":"water",1,1));
                if(i<size-1)ports.add(new Port("out",Direction.OUT,FlowKind.MATERIAL,"water",1,1));
                nodes.add(new Equipment("N"+i,"synthetic-fixture",1,ports));
                if(i>0)links.add(new Connection("N"+(i-1),"out","N"+i,"in"));
            }
            if(mode==2) links.add(links.get(0)); // duplicate connection and port capacity
            if(mode==3) links.remove(links.size()-1); // disconnected required port
            ProcessUnit unit=new ProcessUnit("unit-"+seed,UnitKind.LINE,"fuzz.v1",
                    nodes,links,List.of(),List.of(),List.of(),false);
            ProcessBlueprint b=new ProcessBlueprint(UUID.nameUUIDFromBytes(("fuzz"+seed).getBytes()),1,1,
                    List.of(unit),List.of(),List.of(),List.of());
            Set<String> codes=new HashSet<>();
            for(BlueprintValidator.Finding f:BlueprintValidator.validate(b))
                if(f.severity()==BlueprintValidator.Severity.ERROR)codes.add(f.code());
            String expected=switch(mode) {
                case 0 -> null;
                case 1 -> "INCOMPATIBLE_STREAM";
                case 2 -> "DUPLICATE_CONNECTION";
                default -> "MISSING_REQUIRED_PORT";
            };
            boolean pass=expected==null?codes.isEmpty():codes.contains(expected);
            if(!pass)throw new AssertionError("seed="+seed+", mode="+mode+", expected="+expected+", findings="+codes);
        }
        System.out.println("PASS "+count+" seeded Blueprint graph property scenarios");
    }
}
