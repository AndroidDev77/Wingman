package com.wingman.core.auth

import kotlinx.coroutines.CompletableDeferred
import java.util.Base64

/** Unsigned JWT-shaped string with the given `exp` (seconds). The client never verifies signatures. */
fun fakeJwt(expSeconds: Long?, sub: String = "alice", marker: String = "sig"): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
    val claims = buildString {
        append("""{"sub":"$sub","iss":"https://dev-auth.wingman.invalid","aud":"wingman-dev"""")
        if (expSeconds != null) append(""","exp":$expSeconds""")
        append("}")
    }
    return "$header.${enc.encodeToString(claims.encodeToByteArray())}.${enc.encodeToString(marker.encodeToByteArray())}"
}

class FakeClock(var now: Long = 1_800_000_000_000L) {
    val fn: () -> Long = { now }
}

/** Scripted refresher; counts calls. [gate] lets a test hold a refresh in flight. */
class FakeRefresher(var next: (String) -> RefreshResult) : TokenRefresher {
    var calls = 0
    val seen = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun refresh(refreshToken: String): RefreshResult {
        calls++
        seen += refreshToken
        gate?.await()
        return next(refreshToken)
    }
}

class RecordingLogger : AuthLogger {
    val lines = mutableListOf<String>()
    override fun log(message: String) { synchronized(lines) { lines += message } }
}

/** Reversible but non-identity "cipher" so a test can see the store never writes plaintext. */
class XorCipher(private val k: Byte = 0x5A) : SecretCipher {
    override fun encrypt(plaintext: ByteArray) = byteArrayOf(1) + plaintext.map { (it.toInt() xor k.toInt()).toByte() }
    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.isNotEmpty() && blob[0] == 1.toByte()) { "bad blob" }
        return blob.drop(1).map { (it.toInt() xor k.toInt()).toByte() }.toByteArray()
    }
}

class MemoryBlobStore(var value: String? = null) : BlobStore {
    var deletes = 0
    override fun read() = value
    override fun write(value: String) { this.value = value }
    override fun delete() { value = null; deletes++ }
}
