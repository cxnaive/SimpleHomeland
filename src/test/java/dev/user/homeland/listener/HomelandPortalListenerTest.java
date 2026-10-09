package dev.user.homeland.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HomelandPortalListenerTest {
    @Test
    void touchingSeveralPortalBlocksDoesNotSkipDelayAndLeavingResetsProgress() {
        var ticks = new HomelandPortalListener.PortalTicks();
        for (int tick = 100; tick < 179; tick++) {
            assertFalse(ticks.advance(tick, 80));
            assertFalse(ticks.advance(tick, 80));
        }
        assertTrue(ticks.advance(179, 80));
        assertFalse(ticks.advance(179, 80));
        assertFalse(ticks.advance(200, 80));
        assertTrue(new HomelandPortalListener.PortalTicks().advance(100, 1));
    }
}
