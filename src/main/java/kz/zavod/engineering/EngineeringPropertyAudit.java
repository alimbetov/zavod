package kz.zavod.engineering;

import kz.zavod.catalog.EquipmentType;
import java.math.BigDecimal;
import java.util.*;

/** Strict approval diagnostics; incomplete design assumptions remain saveable as DRAFT. */
public final class EngineeringPropertyAudit {
    private EngineeringPropertyAudit(){}
    public record Finding(String code,String propertyKey) {}
    public static List<Finding> inspect(EquipmentType template,List<EngineeringPropertyValue> values) {
        Objects.requireNonNull(template);Objects.requireNonNull(values);
        Map<String,EngineeringPropertyDefinition> definitions=new HashMap<>();
        for(EngineeringPropertyDefinition d:template.properties())definitions.put(d.key(),d);
        Set<String> seen=new HashSet<>();
        List<Finding> findings=new ArrayList<>();
        for(EngineeringPropertyValue value:values) {
            String key=value.key();
            if(!seen.add(key)) {
                findings.add(new Finding("DUPLICATE_ENGINEERING_PROPERTY",key));
                continue;
            }
            EngineeringPropertyDefinition definition=definitions.get(key);
            if(definition==null) {
                findings.add(new Finding("UNKNOWN_ENGINEERING_PROPERTY",key));
                continue;
            }
            if(value.quantity().dimension()!=definition.dimension()) {
                findings.add(new Finding("PROPERTY_UNIT_DIMENSION_MISMATCH",key));
                continue;
            }
            BigDecimal normalized=value.quantity().in(definition.canonicalUnit()).value();
            if((definition.minInclusive()!=null&&normalized.compareTo(definition.minInclusive())<0)
                    ||(definition.maxInclusive()!=null&&normalized.compareTo(definition.maxInclusive())>0))
                findings.add(new Finding("ENGINEERING_PROPERTY_OUT_OF_RANGE",key));
            if(value.source()==EngineeringPropertyValue.Source.PROJECT_ASSUMPTION
                    ||value.source()==EngineeringPropertyValue.Source.IMPORT_SUGGESTION)
                findings.add(new Finding("ENGINEERING_PROPERTY_NOT_VERIFIED",key));
            else if(definition.evidenceRequiredForApproval()
                    &&(value.evidenceRef()==null||value.evidenceRef().isBlank()))
                findings.add(new Finding("ENGINEERING_PROPERTY_EVIDENCE_REQUIRED",key));
        }
        for(EngineeringPropertyDefinition definition:template.properties())
            if(definition.required()&&!seen.contains(definition.key()))
                findings.add(new Finding("REQUIRED_ENGINEERING_PROPERTY_MISSING",definition.key()));
        findings.sort(Comparator.comparing(Finding::propertyKey).thenComparing(Finding::code));
        return List.copyOf(findings);
    }
}
