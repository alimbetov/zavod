package kz.zavod.blueprint;

import org.junit.jupiter.api.Test;

/**
 * Run the same existing pure-domain oracles under Maven/JaCoCo, without introducing
 * different expected results. These tests are NOT additional independent assertions.
 */
class BlueprintPureDomainJUnitTest {
    @Test void equipmentAndTopology() {
        BlueprintCoreSmoke.main(new String[0]);
    }
    @Test void independentHardeningOracles() {
        BlueprintHardeningSmoke.main(new String[0]);
    }
    @Test void seededGraphFuzz() {
        BlueprintFuzzSmoke.main(new String[0]);
    }
    @Test void engineeringQuantitiesAndProperties() {
        EngineeringPropertiesSmoke.main(new String[0]);
    }
    @Test void independentlyDefinedBusinessRules() {
        BlueprintBusinessCoreContractSmoke.main(new String[0]);
    }
}
