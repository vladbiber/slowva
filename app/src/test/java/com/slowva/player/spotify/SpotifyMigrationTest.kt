package com.slowva.player.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMigrationTest {
    private val id = "0123456789abcdef0123456789ABCDEF"

    @Test
    fun keepsThePersonalClientIdAndItsTokens() {
        val out = SpotifyMigration.fromV2(
            mapOf(
                "api_client_id" to " $id ", "api_refresh" to "AQR", "api_access" to "BQA", "api_expires_at" to 1234L,
                "refresh" to "AQD", "access" to "BQS", "expires_at" to 99L, "from_session" to true,
            ),
        )
        assertEquals(
            mapOf("client_id" to id, "api_refresh" to "AQR", "api_access" to "BQA", "api_expires" to 1234L, "desktop_refresh" to "AQD"),
            out,
        )
    }

    @Test
    fun desktopOnlySignInKeepsTheRefreshToken() {
        assertEquals(mapOf("desktop_refresh" to "AQD"), SpotifyMigration.fromV2(mapOf("refresh" to "AQD", "access" to "BQS")))
    }

    @Test
    fun dropsTokensWithoutAValidClientId() {
        assertTrue(SpotifyMigration.fromV2(mapOf("api_client_id" to "short", "api_refresh" to "AQR")).isEmpty())
        assertTrue(SpotifyMigration.fromV2(mapOf("api_refresh" to "AQR", "api_access" to "BQA")).isEmpty())
    }

    @Test
    fun clientIdAloneMeansNotSignedInWithIt() {
        assertEquals(mapOf("client_id" to id), SpotifyMigration.fromV2(mapOf("api_client_id" to id, "api_access" to "BQA", "api_expires_at" to 5L)))
    }

    @Test
    fun emptyAfterLogoutOrFreshInstall() {
        assertTrue(SpotifyMigration.fromV2(emptyMap<String, Any>()).isEmpty())
        assertTrue(SpotifyMigration.fromV2(mapOf("refresh" to "")).isEmpty())
    }
}
