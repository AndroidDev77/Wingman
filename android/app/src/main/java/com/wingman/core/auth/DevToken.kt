package com.wingman.core.auth

/**
 * DEBUG BUILDS ONLY: validation of a token pasted from the backend dev-stub CLI
 * (`uv run python -m app.security.dev_stub --sub alice`, docs/auth.md).
 *
 * The app never holds the dev SECRET; it only stores the pasted, already-signed token, and the
 * backend verifies it as usual. Every entry point refuses unless `debugBuild` is true (callers pass
 * `BuildConfig.DEBUG`), and the paste UI itself exists only in the `debug` source set.
 */
object DevToken {
    enum class Rejection(val message: String) {
        NOT_DEBUG_BUILD("Developer tokens are only accepted in debug builds."),
        EMPTY("Paste a token first."),
        NOT_A_JWT("That is not a JWT (expected three dot-separated parts)."),
        NO_EXPIRY("The token has no exp claim."),
        EXPIRED("The token has expired. Mint a new one with the dev-stub CLI."),
    }

    sealed interface Result {
        data class Accepted(val tokens: AuthTokens) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    fun accept(raw: String, debugBuild: Boolean, nowEpochMs: Long): Result {
        if (!debugBuild) return Result.Rejected(Rejection.NOT_DEBUG_BUILD)
        val token = raw.trim().removePrefix("Bearer ").trim()
        if (token.isEmpty()) return Result.Rejected(Rejection.EMPTY)
        if (Jwt.payload(token) == null) return Result.Rejected(Rejection.NOT_A_JWT)
        val exp = Jwt.expiryEpochMs(token) ?: return Result.Rejected(Rejection.NO_EXPIRY)
        if (exp <= nowEpochMs) return Result.Rejected(Rejection.EXPIRED)
        return Result.Accepted(AuthTokens(accessToken = token, expiresAtEpochMs = exp, source = TokenSource.DEV))
    }
}
