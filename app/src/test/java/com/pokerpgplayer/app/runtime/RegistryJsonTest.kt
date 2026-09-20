package com.pokerpgplayer.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build-compatibility correction. Direct coverage of [RegistryJson] —
 * separate from [MirrorRuntimeWorkspaceServiceTest], which exercises it
 * only indirectly through the service's own registry read/write paths.
 * Backward compatibility with an existing on-device `registry.json`
 * (written by the previous `org.json.JSONObject`-based implementation)
 * is the one property this file cares about most: [MirrorRuntimeWorkspaceService]
 * must keep reading every real file a production build already wrote.
 */
class RegistryJsonTest {

    @Test
    fun `parses a real org-json-produced registry file exactly`() {
        // This exact text is what org.json.JSONObject.toString() produces
        // for the shape MirrorRuntimeWorkspaceService writes — captured
        // as a literal fixture so this test independently proves
        // backward compatibility rather than assuming it via round-trip.
        val realOrgJsonOutput =
            """{"550e8400-e29b-41d4-a716-446655440000":{"sourceUri":"content:\/\/fake\/tree\/document\/fake%3Agame","workspacePath":"\/data\/user\/0\/com.pokerpgplayer.app\/files\/runtime-workspace\/550e8400-e29b-41d4-a716-446655440000","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z","fileCount":4213,"totalSizeBytes":812345678,"state":"READY"}}"""

        val parsed = RegistryJson.parseObject(realOrgJsonOutput)
        @Suppress("UNCHECKED_CAST")
        val entry = parsed["550e8400-e29b-41d4-a716-446655440000"] as Map<String, Any?>

        assertEquals("content://fake/tree/document/fake%3Agame", entry["sourceUri"])
        assertEquals("/data/user/0/com.pokerpgplayer.app/files/runtime-workspace/550e8400-e29b-41d4-a716-446655440000", entry["workspacePath"])
        assertEquals("2026-01-01T00:00:00Z", entry["createdAt"])
        assertEquals(4213L, (entry["fileCount"] as Number).toLong())
        assertEquals(812345678L, (entry["totalSizeBytes"] as Number).toLong())
        assertEquals("READY", entry["state"])
    }

    @Test
    fun `parses a legacy pre-53-2 entry with no state field at all`() {
        val legacyText = """{"g1":{"sourceUri":"content://x","workspacePath":"/a/b","createdAt":"t0","updatedAt":"t0","fileCount":1,"totalSizeBytes":2}}"""

        val parsed = RegistryJson.parseObject(legacyText)
        @Suppress("UNCHECKED_CAST")
        val entry = parsed["g1"] as Map<String, Any?>

        assertEquals("content://x", entry["sourceUri"])
        assertNull("a legacy entry has no state key at all", entry["state"])
    }

    @Test
    fun `round-trips a full write then read without any data loss`() {
        val original = linkedMapOf<String, Any?>(
            "game-a" to linkedMapOf<String, Any?>(
                "sourceUri" to "content://a \"quoted\" path\nwith a newline",
                "workspacePath" to "/x/y",
                "createdAt" to "t0",
                "updatedAt" to "t1",
                "fileCount" to 42,
                "totalSizeBytes" to 9999999999L,
                "state" to "READY"
            )
        )

        val text = RegistryJson.serializeObject(original)
        val reparsed = RegistryJson.parseObject(text)

        @Suppress("UNCHECKED_CAST")
        val entry = reparsed["game-a"] as Map<String, Any?>
        assertEquals("content://a \"quoted\" path\nwith a newline", entry["sourceUri"])
        assertEquals(42L, (entry["fileCount"] as Number).toLong())
        assertEquals(9999999999L, (entry["totalSizeBytes"] as Number).toLong())
        assertEquals("READY", entry["state"])
    }

    @Test
    fun `two independent entries both survive a round trip together`() {
        val original = linkedMapOf<String, Any?>(
            "game-a" to linkedMapOf<String, Any?>("sourceUri" to "a", "state" to "READY"),
            "game-b" to linkedMapOf<String, Any?>("sourceUri" to "b", "state" to "PREPARING")
        )

        val reparsed = RegistryJson.parseObject(RegistryJson.serializeObject(original))

        assertEquals(2, reparsed.size)
        @Suppress("UNCHECKED_CAST")
        assertEquals("READY", (reparsed["game-a"] as Map<String, Any?>)["state"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("PREPARING", (reparsed["game-b"] as Map<String, Any?>)["state"])
    }

    @Test
    fun `blank input parses as an empty object rather than throwing`() {
        assertTrue(RegistryJson.parseObject("").isEmpty())
        assertTrue(RegistryJson.parseObject("   ").isEmpty())
    }

    @Test
    fun `malformed input throws rather than silently returning partial data`() {
        try {
            RegistryJson.parseObject("{not valid json at all")
            org.junit.Assert.fail("expected an exception for malformed JSON")
        } catch (e: Exception) {
            // expected — every real call site wraps this in
            // runCatching/try-catch and treats it as "no usable
            // registry," matching the prior org.json-based behavior.
        }
    }

    // ---- Sprint53.2 RegistryJVM FINAL integrity correction: trailing-content validation ----

    @Test
    fun `a valid object followed only by trailing whitespace is accepted`() {
        val parsed = RegistryJson.parseObject("""{"g1":{"sourceUri":"a"}}
	""")

        @Suppress("UNCHECKED_CAST")
        assertEquals("a", (parsed["g1"] as Map<String, Any?>)["sourceUri"])
    }

    @Test
    fun `a valid object followed by trailing garbage throws`() {
        try {
            RegistryJson.parseObject("""{"g1":{"sourceUri":"a"}} trailing-garbage""")
            org.junit.Assert.fail("expected an exception for trailing garbage after the root object")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `two concatenated JSON objects throws rather than silently reading only the first`() {
        try {
            RegistryJson.parseObject("""{"g1":{"sourceUri":"a"}}{"g2":{"sourceUri":"b"}}""")
            org.junit.Assert.fail("expected an exception for two concatenated root objects")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
