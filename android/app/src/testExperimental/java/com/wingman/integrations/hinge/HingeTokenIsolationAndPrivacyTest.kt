package com.wingman.integrations.hinge

import com.wingman.core.model.OutboxAuthorizationOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Token isolation (D-021 item 3, ADR 0008 §3) and log privacy, statically and dynamically.
 */
class HingeTokenIsolationAndPrivacyTest {
    // WING-031 (D-026): the connector lives in src/experimental (compiled only by the experimental
    // build); the rest of the app in src/main. Both trees are scanned.
    private val appDir = File("src").let { if (it.isDirectory) File(".") else File("android/app") }
    private val src = File(appDir, "src/main/java/com/wingman")
    private val experimental = File(appDir, "src/experimental/java/com/wingman")
    private val hingeDir = File(experimental, "integrations/hinge")

    private fun kt(dir: File) = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    @Test fun `static - nothing in core can reach a Hinge type`() {
        assertTrue("source tree not found from ${File("").absolutePath}", hingeDir.isDirectory)
        val offenders = kt(File(src, "core")).filter { f -> f.readText().let { it.contains("integrations.hinge") || it.contains("HingeSession") } }
        assertEquals(emptyList<File>(), offenders)
    }

    @Test fun `static - no Hinge session type or token field is used outside integrations-hinge`() {
        // Integrator (D-026): the whole app tree, not only core/, so no screen, API client, DI or
        // other connector can carry a Hinge credential towards Wingman.
        val outside = (kt(src) + kt(experimental)).filter {
            "integrations/hinge" !in it.invariantSeparatorsPath || "integrations/hinge/notifications" in it.invariantSeparatorsPath
        }
        assertTrue(outside.size > 50)
        val forbidden = listOf("HingeSession", "HingeSessionStore", "EncryptedFileHingeSessionStore", "hingeToken", "KeystoreHingeCipher", "SendbirdChatTransport")
        val offenders = outside.flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `static - no client-identity value is committed in the connector sources`() {
        // The reference's identity (iOS user-agent, Sendbird app id and hosts, app version and build)
        // comes only from the experimental build's properties (docs/integrations/hinge.md section 3a).
        val forbidden = Regex("CFNetwork/|Darwin/[0-9]|\\.sendbird\\.com|hingeaws|x-app-version\", \"[0-9]|Jios/[0-9]", RegexOption.IGNORE_CASE)
        val offenders = kt(hingeDir).filter { f -> f.readLines().any { line -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && forbidden.containsMatchIn(line) } }
        assertEquals(emptyList<File>(), offenders)
    }

    @Test fun `static - the Wingman-facing code never touches the session`() {
        for (name in listOf("HingeBackend.kt", "HingeConsent.kt", "HingeEvents.kt")) {
            val text = File(hingeDir, name).readText()
            for (forbidden in listOf("HingeSession", "hingeToken", "chatToken", "sessionKey", "Session-Key")) {
                assertFalse("$name mentions $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test fun `static - login codes are typed, never read from SMS or notifications`() {
        val files = kt(hingeDir).filter { "notifications" !in it.path.substringAfter("integrations/hinge") && it.name != "HingeModule.kt" }
        assertTrue(files.size > 10)
        val forbidden = listOf("android.provider.Telephony", "SmsMessage", "READ_SMS", "RECEIVE_SMS", "SmsRetriever", "NotificationListenerService", "StatusBarNotification", "integrations.notifications", "AccessibilityService")
        for (f in files) for (word in forbidden) assertFalse("${f.name} uses $word", f.readText().contains(word))
    }

    @Test fun `static - excluded endpoints are not compiled in`() {
        val templates = HingeEndpoint.entries.map { it.template }
        for (bad in listOf("/rec", "/like", "/likelimit", "/rate", "/message/send", "/user", "/content", "/standouts", "/preference", "/store", "/flag", "/prompts")) {
            assertTrue("$bad is allowlisted", templates.none { it.startsWith(bad) })
        }
        assertEquals(9, HingeEndpoint.entries.size)
    }

    @Test fun `static - the session file store is excluded from backups by location (noBackupFilesDir)`() {
        val module = File(hingeDir, "android/HingeModule.kt").readText()
        assertTrue(module.contains("noBackupFilesDir"))
        assertTrue(module.contains("EncryptedFileHingeSessionStore") && module.contains("KeystoreHingeCipher"))
        assertFalse(module.contains("filesDir,") || module.contains("externalFilesDir"))
    }

    @Test fun `dynamic - a full run sends no token, code, number or session key to Wingman, and logs nothing private`() {
        val rig = HingeRig()
        try {
            rig.loginAndPair(email = true)
            rig.syncOneInbound()
            rig.backend.claimScript += claimOf(instruction())
            rig.c.sendOnce()
            rig.clock.now += 120_000
            rig.sockets.mode = FakeSockets.Mode.GUEST
            rig.backend.claimScript += claimOf(instruction(key = "send:$OUTBOUND_ID:2"))
            rig.c.sendOnce()
            rig.clock.now += 120_000
            rig.backend.claimScript += claimOf(instruction(key = "send:$OUTBOUND_ID:3", origin = OutboxAuthorizationOrigin.AUTOPILOT))
            rig.c.sendOnce()
            rig.hs.on("GET /sb/v3/users/${Sentinels.SELF_ID}/my_group_channels", json("{}", 426))
            rig.clock.now += HingeLimits.FIFTEEN_MINUTES
            rig.c.syncOnce()
            rig.c.optOut()

            assertTrue(rig.backend.wire.size > 5)
            for (body in rig.backend.wire) for (secret in Sentinels.ALL_SECRETS) {
                assertFalse("Wingman call carried $secret", body.contains(secret))
            }
            // The match's message reached Wingman (that is the point), the tokens did not.
            assertTrue(rig.backend.wire.any { it.contains("SENTINEL-TEXT") })
            assertTrue(rig.log.lines.isNotEmpty())
            for (line in rig.log.lines) for (p in Sentinels.ALL_PRIVATE) {
                assertFalse("log line leaks $p: $line", line.contains(p))
            }
        } finally {
            rig.close()
        }
    }

    @Test fun `the session file holds no plaintext and a corrupt file is discarded`() {
        val dir = Files.createTempDirectory("hinge-session").toFile()
        try {
            val file = File(dir, "s.bin")
            val store = EncryptedFileHingeSessionStore(file, XorCipher())
            store.save(HingeSession.newInstall().copy(identityId = Sentinels.SELF_ID, hingeToken = Sentinels.HINGE_TOKEN, hingeExpiresAtMillis = T0 * 2, chatToken = Sentinels.CHAT_TOKEN))
            val raw = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(raw.contains(Sentinels.HINGE_TOKEN) || raw.contains(Sentinels.CHAT_TOKEN))
            assertEquals(Sentinels.HINGE_TOKEN, EncryptedFileHingeSessionStore(file, XorCipher()).load()!!.hingeToken)
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(null, EncryptedFileHingeSessionStore(file, XorCipher()).load())
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `toString of every private carrier is redacted`() {
        val s = HingeSession.newInstall().copy(identityId = Sentinels.SELF_ID, hingeToken = Sentinels.HINGE_TOKEN, chatToken = Sentinels.CHAT_TOKEN)
        val m = SendbirdMessage("1", "MESG", "", Sentinels.TEXT, T0, Sentinels.MATCH_ID, "Riley", Sentinels.CHANNEL, false, false)
        val r = HingeResponse(200, "application/json", Sentinels.TEXT, null, emptySet())
        val e = HingeEventMapper(USER_ID, CONNECTION_ID).received(m, Sentinels.SELF_ID, T0)!!
        for (text in listOf(s.toString(), m.toString(), r.toString(), e.toString(), instruction().toString())) {
            for (p in Sentinels.ALL_PRIVATE) assertFalse("$text leaks $p", text.contains(p))
        }
    }
}
