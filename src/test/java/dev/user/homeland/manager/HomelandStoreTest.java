package dev.user.homeland.manager;

import dev.user.homeland.model.Homeland;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomelandStoreTest {

    @Test
    void keepsOneCanonicalInstanceWithoutOverwritingLiveState() {
        HomelandStore store = new HomelandStore();
        UUID owner = UUID.randomUUID();
        UUID initialWorldUuid = UUID.randomUUID();
        Homeland initial = homeland(3, owner, "Home", "homeland_abc_1", initialWorldUuid);

        Homeland canonical = store.upsert(initial);
        canonical.setHasNether(true);
        store.refreshIndexes(canonical);

        Homeland laterSnapshot = homeland(
                3, owner, "Home", "homeland_abc_1", UUID.randomUUID());
        Homeland loadedAgain = store.upsert(laterSnapshot);

        assertSame(canonical, loadedAgain);
        assertTrue(loadedAgain.hasNether());
        assertEquals(initialWorldUuid, loadedAgain.getWorldUuid());
        assertSame(canonical, store.getByWorldKey("homeland_abc_1_nether"));
    }

    @Test
    void refreshesDimensionAndWorldUuidIndexes() {
        HomelandStore store = new HomelandStore();
        UUID owner = UUID.randomUUID();
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();
        Homeland homeland = store.upsert(
                homeland(5, owner, "Island", "homeland_def_2", firstUuid));

        homeland.setHasEnd(true);
        store.refreshIndexes(homeland);
        assertSame(homeland, store.getByWorldKey("homeland_def_2_the_end"));

        homeland.setHasEnd(false);
        store.refreshIndexes(homeland);
        assertNull(store.getByWorldKey("homeland_def_2_the_end"));

        store.updateWorldUuid(homeland, secondUuid);
        assertNull(store.getByWorldUuid(firstUuid));
        assertSame(homeland, store.getByWorldUuid(secondUuid));
        assertEquals(1, store.findByOwner(owner).size());

        store.remove(homeland);
        assertFalse(store.find(owner, "island").isPresent());
        assertNull(store.getByWorldKey("homeland_def_2"));
        assertNull(store.getByWorldUuid(secondUuid));
    }

    @Test
    void findsNamesGloballyWithoutCaseSensitivity() {
        HomelandStore store = new HomelandStore();
        Homeland homeland = homeland(6, UUID.randomUUID(), "firefly",
                "homeland_firefly_1", UUID.randomUUID());
        store.upsert(homeland);

        assertSame(homeland, store.findByName("FIREFLY"));
    }

    @Test
    void rejectsIdentityCollisionForSameDatabaseId() {
        HomelandStore store = new HomelandStore();
        UUID owner = UUID.randomUUID();
        store.upsert(homeland(8, owner, "One", "homeland_one_1", UUID.randomUUID()));

        assertThrows(IllegalArgumentException.class, () -> store.upsert(
                homeland(8, owner, "Two", "homeland_two_1", UUID.randomUUID())));
    }

    @Test
    void keepsPendingResetRecoveryMetadataOnCanonicalInstance() {
        UUID previousUuid = UUID.randomUUID();
        Homeland pending = new Homeland(12, UUID.randomUUID(), "Pending",
                "homeland_pending_1", null, "flat", 128,
                false, false, false, null, null, true, previousUuid);

        Homeland canonical = new HomelandStore().upsert(pending);

        assertTrue(canonical.isOverworldResetPending());
        assertEquals(previousUuid, canonical.getOverworldResetPreviousUuid());
        assertNull(canonical.getWorldUuid());
    }

    private static Homeland homeland(int id, UUID owner, String name, String worldKey,
                                     UUID worldUuid) {
        return new Homeland(id, owner, name, worldKey, worldUuid,
                "default", 128, false, false, false, null);
    }
}
