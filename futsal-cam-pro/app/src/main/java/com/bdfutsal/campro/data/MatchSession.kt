package com.bdfutsal.campro.data

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/**
 * A match shared between the two phones. Nothing is sent over the network: the
 * link only carries the match name, a short code and the planned start time, and
 * each phone uses its own clock to start recording.
 */
data class MatchSession(
    val name: String,
    val code: String,
    val startEpochMs: Long,
) {
    fun toLink(): String =
        "$SCHEME://match?c=${enc(code)}&t=$startEpochMs&n=${enc(name)}"

    companion object {
        const val SCHEME = "bdfutsalcam"
        const val DEFAULT_NAME = "Futsal match"
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private val LINK = Regex("""$SCHEME://match\?\S+""")
        private val CODE = Regex("[A-Z0-9]{3,12}")

        /** New session starting [delaySeconds] from [now], rounded up to a whole second. */
        fun create(
            name: String,
            delaySeconds: Int,
            now: Long = System.currentTimeMillis(),
            random: Random = Random.Default,
        ): MatchSession {
            val code = (1..5).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            val start = ((now + delaySeconds * 1000L + 999L) / 1000L) * 1000L
            return MatchSession(cleanName(name), code, start)
        }

        /** Finds a match link anywhere in [text] (e.g. a whole pasted share message). */
        fun parse(text: String): MatchSession? {
            val link = LINK.find(text)?.value ?: return null
            val params = link.substringAfter('?').split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to dec(part.substring(i + 1))
            }.toMap()
            val code = params["c"]?.uppercase()?.takeIf { CODE.matches(it) } ?: return null
            val start = params["t"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return MatchSession(cleanName(params["n"].orEmpty()), code, start)
        }

        private fun cleanName(name: String) =
            name.trim().replace(Regex("\\s+"), " ").take(80).ifEmpty { DEFAULT_NAME }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }
    }
}
