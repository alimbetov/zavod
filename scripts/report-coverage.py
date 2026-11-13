#!/usr/bin/env python3
"""JaCoCo report summary: Maven/JUnit coverage only; pure javac smoke is separate."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

path = Path(sys.argv[1] if len(sys.argv)>1 else "target/site/jacoco/jacoco.xml")
if not path.is_file():
    raise SystemExit(f"Missing JaCoCo XML report: {path}")
root = ET.parse(path).getroot()

def fmt(node, kind):
    counters=[x for x in node.findall("counter") if x.attrib.get("type")==kind]
    if not counters:
        return "n/a"
    x=counters[0]
    covered=int(x.get("covered","0"))
    missed=int(x.get("missed","0"))
    return f"{covered}/{covered+missed} ({100*covered/(covered+missed):.1f}%)" if covered+missed else "n/a"

print("JaCoCo / Maven JUnit: coverage is NOT the share of business rules proved.")
print(f"TOTAL: lines {fmt(root,'LINE')}, branches {fmt(root,'BRANCH')}")
targets = [
    "kz/zavod/blueprint/domain/BlueprintValidator",
    "kz/zavod/blueprint/domain/BlueprintPublicationGate",
    "kz/zavod/blueprint/domain/BlueprintImpactAnalyzer",
    "kz/zavod/blueprint/persistence/BlueprintDraftService",
    "kz/zavod/blueprint/lifecycle/BlueprintLifecycleService",
    "kz/zavod/blueprint/domain/BlueprintReadiness",
    "kz/zavod/studio/StudioBlueprintService",
    "kz/zavod/studio/StudioLayoutService",
    "kz/zavod/engineering/EngineeringUnits",
    # G4/TF03 resilience, audit trust boundary and industrial-safety-adjacent code:
    "kz/zavod/blueprint/lifecycle/ActivationCommandService",
    "kz/zavod/blueprint/lifecycle/BusinessTransitionLedger",
    "kz/zavod/blueprint/lifecycle/BusinessLedgerAuditService",
    "kz/zavod/blueprint/lifecycle/SiteSecurityConfiguration",
    "kz/zavod/telemetry/TelemetryQualityEvaluator",
    "kz/zavod/telemetry/TelemetryIngestionService",
    "kz/zavod/telemetry/SignalRegistryService",
    "kz/zavod/studio/PlantDemoDevController",
]
found=set()
for pkg in root.findall("package"):
    for cls in pkg.findall("class"):
        name=cls.get("name","")
        # Select the exact outer class. Nested records can contain only one line,
        # making a misleading 100% when they appear first in the XML.
        if name in targets and name not in found:
            print(f"{name.rsplit('/',1)[-1]}: lines {fmt(cls,'LINE')}, branches {fmt(cls,'BRANCH')}")
            found.add(name)
missing=set(targets)-found
if missing:
    raise SystemExit("Coverage data missing for critical classes: "+", ".join(sorted(missing)))
print("Note: scripts/test-blueprint-core.sh standalone smoke and fuzz executions are not JaCoCo-instrumented.")
