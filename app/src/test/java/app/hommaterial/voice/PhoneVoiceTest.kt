package app.hommaterial.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneVoiceTest {
    @Test
    fun callsAreRead() {
        assertEquals("Flavio Ceccarelli", parseCall(listOf("Chiama Flavio Ceccarelli")))
        assertEquals("mamma", parseCall(listOf("telefona a mamma per favore")))
        assertEquals("Marco", parseCall(listOf("call Marco")))
        assertNull(parseCall(listOf("accendi la luce")))
    }

    @Test
    fun destinationsAreRead() {
        assertEquals(Destination.Address("Via del corso 21 roma"), parseNavigate(listOf("Portami a Via del corso 21 roma con Google Maps")))
        assertEquals(Destination.Address("Milano"), parseNavigate(listOf("naviga verso Milano")))
        assertEquals(Destination.Contact("flavio ceccarelli"), parseNavigate(listOf("portami a casa di flavio ceccarelli con google maps")))
        assertEquals(Destination.Contact("Flavio"), parseNavigate(listOf("portami da Flavio")))
        assertEquals(Destination.Contact("Anna"), parseNavigate(listOf("take me to Anna's house")))
        assertNull(parseNavigate(listOf("spegni tutto")))
    }

    @Test
    fun contactsAreMatched() {
        assertEquals(2, contactScore("Flavio Ceccarelli", "flavio ceccarelli"))
        assertEquals(1, contactScore("Flavio Ceccarelli", "Flavio"))
        assertEquals(0, contactScore("Flavio Ceccarelli", "Marco"))
    }
}
