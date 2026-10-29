package com.slowva.player.keys

import com.slowva.player.keys.DeviceProfile.Companion.detect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test fun unihertzModelsByName() {
        assertEquals(DeviceProfile.TITAN, detect("Unihertz", "Titan", 1440, 1440))
        assertEquals(DeviceProfile.TITAN_POCKET, detect("UNIHERTZ", "Titan pocket", 716, 720))
        assertEquals(DeviceProfile.TITAN_SLIM, detect("unihertz", "Titan Slim", 768, 1280))
        assertEquals(DeviceProfile.TITAN_2, detect("Unihertz", "Titan 2", 1440, 1440))
        assertEquals(DeviceProfile.TITAN_2, detect("Unihertz", "Titan2", 1440, 1440))
        assertEquals(DeviceProfile.TITAN_2_ELITE, detect("Unihertz", "Titan 2 Elite", 1080, 1200))
    }

    @Test fun otherPhonesFallBackToAspect() {
        assertEquals(DeviceProfile.GENERIC_SQUARE, detect("Google", "Pixel", 1000, 1000))
        assertEquals(DeviceProfile.GENERIC_SQUARE, detect("Unihertz", "Jelly Max", 1180, 1000))
        assertEquals(DeviceProfile.OFF, detect("Google", "Pixel 8", 1080, 2400))
        assertEquals(DeviceProfile.OFF, detect("Google", "Pixel 8", 2400, 1080))
        assertEquals(DeviceProfile.OFF, detect("Titan Corp", "Titan", 1080, 2400))
        assertEquals(DeviceProfile.OFF, detect(null, null, 0, 0))
    }

    @Test fun flags() {
        assertTrue(DeviceProfile.TITAN_POCKET.isSquare && DeviceProfile.TITAN_POCKET.isKeyboardPhone)
        assertFalse(DeviceProfile.TITAN_SLIM.isSquare)
        assertTrue(DeviceProfile.TITAN_SLIM.isKeyboardPhone)
        assertTrue(DeviceProfile.GENERIC_SQUARE.isSquare)
        assertFalse(DeviceProfile.GENERIC_SQUARE.isKeyboardPhone)
        assertFalse(DeviceProfile.OFF.isSquare || DeviceProfile.OFF.isKeyboardPhone)
    }
}
