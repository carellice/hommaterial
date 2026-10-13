package app.hommaterial.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchBotTest {
    @Test
    fun requestsAreSignedAsSwitchBotAsks() {
        // Worked out apart from the app, with the recipe of the SwitchBot documentation.
        val expected = "BDFQNBEJWKVP6RHVWLYOXD4M48GPWYEFZM1IASZBWIY="
        assertEquals(expected, switchBotSign("token-abc", "secret-key", "1700000000000", "nonce-1"))
    }

    @Test
    fun remotesLearntByHandAreToldApart() {
        assertEquals(RemoteKind.TV, Remote("1", "Sala", "TV").kind)
        assertEquals(RemoteKind.TV, Remote("1", "Sala", "DIY TV").kind)
        assertEquals(RemoteKind.AIR_CONDITIONER, Remote("2", "Clima", "DIY Air Conditioner").kind)
        assertEquals(RemoteKind.CUSTOM, Remote("3", "Luci", "Others").kind)
        assertEquals(RemoteKind.OTHER, Remote("4", "Ventilatore", "Fan").kind)
    }
}
