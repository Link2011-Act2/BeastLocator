package jp.linkserver.beastlocator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationInputParserTest {
    @Test
    fun parsesAsciiCoordinates() {
        assertEquals(
            Destination(35.665554, 139.669717),
            DestinationInputParser.parse("35.665554", "139.669717")
        )
    }

    @Test
    fun parsesFullWidthAndLocaleDecimalCoordinates() {
        assertEquals(
            Destination(-35.5, 139.25),
            DestinationInputParser.parse("－３５，５", "１３９，２５")
        )
    }

    @Test
    fun acceptsCoordinateBoundaries() {
        assertEquals(Destination(-90.0, 180.0), DestinationInputParser.parse("-90", "180"))
        assertEquals(Destination(90.0, -180.0), DestinationInputParser.parse("90", "-180"))
    }

    @Test
    fun roundTripsFullPrecisionMapCoordinates() {
        val original = Destination(35.1234567890123, 139.9876543210987)

        assertEquals(
            original,
            DestinationInputParser.parse(original.lat.toString(), original.lng.toString())
        )
    }

    @Test
    fun rejectsInvalidCoordinates() {
        assertNull(DestinationInputParser.parse("90.0001", "0"))
        assertNull(DestinationInputParser.parse("0", "-180.0001"))
        assertNull(DestinationInputParser.parse("NaN", "0"))
        assertNull(DestinationInputParser.parse("", "0"))
    }
}
