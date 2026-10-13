package app.hommaterial.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationTest {
    private fun automation(trigger: String, threshold: Double = 0.0) =
        Automation("1", "a5", trigger, threshold, MacroAction(ACTION_POWER, "a4", "on"), "", "")

    @Test
    fun aThresholdHoldsOnlyPastIt() {
        val hot = automation(TRIGGER_TEMPERATURE_ABOVE, 25.0)
        assertEquals(true, hot.holds(DeviceState(temperature = 25.1)))
        assertEquals(false, hot.holds(DeviceState(temperature = 25.0)))
        val cold = automation(TRIGGER_TEMPERATURE_BELOW, 18.0)
        assertEquals(true, cold.holds(DeviceState(temperature = 17.5)))
        assertEquals(false, cold.holds(DeviceState(temperature = 18.5)))
        assertEquals(true, automation(TRIGGER_HUMIDITY_ABOVE, 60.0).holds(DeviceState(humidity = 61)))
    }

    @Test
    fun itIsReadyAgainOnlyWellClearOfTheThreshold() {
        val hot = automation(TRIGGER_TEMPERATURE_ABOVE, 25.0)
        // Just under the threshold it neither holds nor is ready again: it waits.
        assertEquals(false, hot.cleared(DeviceState(temperature = 24.9)))
        assertEquals(true, hot.cleared(DeviceState(temperature = 24.6)))
    }

    @Test
    fun switchingIsWaitedForToo() {
        assertEquals(true, automation(TRIGGER_ON).holds(DeviceState(power = true)))
        assertEquals(true, automation(TRIGGER_ON).cleared(DeviceState(power = false)))
        assertEquals(true, automation(TRIGGER_OFF).holds(DeviceState(power = false)))
    }

    @Test
    fun whatIsNotKnownSaysNothing() {
        val hot = automation(TRIGGER_TEMPERATURE_ABOVE, 25.0)
        assertEquals(null, hot.holds(DeviceState()))
        assertEquals(null, hot.holds(DeviceState(temperature = 30.0, reachable = false)))
        assertEquals(null, automation(TRIGGER_ON).holds(DeviceState()))
    }
}
