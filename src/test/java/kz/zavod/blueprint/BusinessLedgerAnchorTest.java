package kz.zavod.blueprint;

import kz.zavod.blueprint.lifecycle.BusinessLedgerAuditService.Anchor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure defensive contract for externally supplied audit checkpoint anchors. */
class BusinessLedgerAnchorTest {
    @Test void zeroGenesisAndValidPositiveCheckpointAreAccepted(){
        assertEquals(0,new Anchor(0,"").sequenceNo());
        assertEquals(42,new Anchor(42,"a".repeat(64)).sequenceNo());
    }
    @Test void malformedOrCounterfeitCheckpointIsRejected(){
        assertThrows(IllegalArgumentException.class,()->new Anchor(-1,""));
        assertThrows(IllegalArgumentException.class,()->new Anchor(0,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new Anchor(1,""));
        assertThrows(IllegalArgumentException.class,()->new Anchor(1,"abc"));
        assertThrows(IllegalArgumentException.class,()->new Anchor(1,"A".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new Anchor(1,null));
    }
}
