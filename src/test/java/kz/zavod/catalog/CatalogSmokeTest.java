package kz.zavod.catalog;

import java.util.List;
import java.util.Set;

import static kz.zavod.catalog.EquipmentType.Capability.*;
import static kz.zavod.catalog.EquipmentType.Direction.*;
import static kz.zavod.catalog.EquipmentType.Mode.*;

/** Self-contained JDK 21 regression harness; migrate to JUnit once Maven setup is in CI. */
public final class CatalogSmokeTest {
    private static int checks = 0;

    public static void main(String[] args) {
        var catalog = EquipmentCatalog.standard();
        check(catalog.types().size() == 15, "15 equipment templates");
        check(catalog.profiles().size() == 2, "2 industry profiles");
        check(catalog.profile("batch.concrete.v1").processKind() == IndustryProfile.ProcessKind.BATCH, "batch profile");
        check(catalog.profile("continuous.pump.v1").processKind() == IndustryProfile.ProcessKind.CONTINUOUS, "continuous profile");
        var mixer = catalog.type("batch-mixer");
        check(mixer.capabilities().contains(MIX), "mixer has MIX capability");
        check(mixer.modes().contains(RECIPE_PARAMETERS), "recipe parameters are a mode, not a stored recipe");
        check(!mixer.port("water-in").key().equals(mixer.port("admixture-in").key()), "water and admixture are distinct physical ports");
        check(mixer.port("admixture-in").minConnections() == 0, "admixture optional");
        check(mixer.port("water-in").minConnections() == 1, "water required");
        check(ConnectionRules.check(catalog.type("cement-silo").port("cement-out"),
                catalog.type("cement-scale").port("cement-in")) == ConnectionRules.Verdict.OK, "cement connection");
        check(ConnectionRules.check(catalog.type("water-tank").port("water-out"),
                catalog.type("cement-scale").port("cement-in")) == ConnectionRules.Verdict.MEDIUM_MISMATCH, "incorrect medium rejected");
        check(ConnectionRules.check(catalog.type("water-tank").port("water-out"),
                catalog.type("cement-silo").port("cement-out")) == ConnectionRules.Verdict.DIRECTION_MISMATCH, "invalid direction rejected");
        rejects(() -> new EquipmentType.Port("bad", IN, "water", 2, 1), "invalid cardinality rejected");
        rejects(() -> new EquipmentType("x", 1, "dup", Set.of(MIX), Set.of(),
                List.of(new EquipmentType.Port("x", IN, "water", 0, 1),
                        new EquipmentType.Port("x", OUT, "water", 0, 1))), "duplicate port rejected");
        rejects(() -> new EquipmentCatalog(List.of(catalog.type("water-tank"), catalog.type("water-tank")), List.of()), "duplicate template rejected");
        rejects(() -> new EquipmentCatalog(List.of(catalog.type("water-tank")),
                List.of(new IndustryProfile("broken", IndustryProfile.ProcessKind.BATCH, Set.of("missing")))), "unknown profile member rejected");
        check(catalog.type("pressure-sensor").modes().isEmpty(), "passive sensor no control modes");
        check(catalog.type("water-pump").modes().contains(CONTINUOUS_SETPOINT), "continuous process mode");
        System.out.println("PASS " + checks + " catalog checks");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    private static void rejects(Runnable run, String label) {
        try { run.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }
}
