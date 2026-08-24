package dev.user.homeland.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomSettingsTest {

    @Test
    void rollsBackLatestFailedUpdate() {
        CustomSettings settings = new CustomSettings();
        CustomSettings.Update update = settings.update("ICE_MELT", true);

        assertTrue(settings.get("ICE_MELT", false));
        assertTrue(settings.rollback(update));
        assertFalse(settings.get("ICE_MELT", false));
    }

    @Test
    void staleRollbackDoesNotOverwriteNewerState() {
        CustomSettings settings = new CustomSettings();
        CustomSettings.Update stale = settings.update("ICE_MELT", true);
        settings.update("ICE_MELT", false);

        assertFalse(settings.rollback(stale));
        assertFalse(settings.get("ICE_MELT", true));
    }

    @Test
    void rollbackOfOneKeyPreservesNewerUpdateToAnotherKey() {
        CustomSettings settings = new CustomSettings();
        CustomSettings.Update failed = settings.update("ICE_MELT", true);
        settings.update("SNOW_MELT", true);

        assertTrue(settings.rollback(failed));
        assertFalse(settings.get("ICE_MELT", false));
        assertTrue(settings.get("SNOW_MELT", false));
    }

    @Test
    void successfulRollbackCannotBeAppliedTwice() {
        CustomSettings settings = new CustomSettings();
        CustomSettings.Update update = settings.update("ICE_MELT", true);

        assertTrue(settings.rollback(update));
        assertFalse(settings.rollback(update));
    }

    @Test
    void databaseUpdateOnlyChangesRequestedKey() {
        String updated = CustomSettings.updateJson(
                "{\"CONFIRMED\":true}", "SNOW_MELT", true);

        assertEquals("{\"CONFIRMED\":true,\"SNOW_MELT\":true}", updated);
        assertFalse(CustomSettings.fromJson(updated).get("ICE_MELT", false));
    }

    @Test
    void malformedOrNonBooleanJsonDoesNotCreateSettings() {
        CustomSettings malformed = CustomSettings.fromJson("{not-json");
        CustomSettings wrongType = CustomSettings.fromJson(
                "{\"PREVENT_ICE_SNOW_MELTING\":\"true\"}");

        assertFalse(malformed.get("PREVENT_ICE_SNOW_MELTING", false));
        assertFalse(wrongType.get("PREVENT_ICE_SNOW_MELTING", false));
    }
}
