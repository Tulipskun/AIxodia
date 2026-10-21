package com.tulipskun.aixodia.ui.chat

/**
 * Turns a raw error from the ai-engine daemon into a short English message the
 * user can act on. Local failures get this app's wording; a provider failure
 * keeps the provider's own message, because it is the only text that says what
 * the provider actually rejected. Pure function, no Android types, so it is
 * unit-tested on the JVM.
 */
fun friendlyError(raw: String): String {
    val s = raw.lowercase()
    val upstream = providerFailure(raw)
    return when {
        raw.isBlank() ->
            "An unknown error occurred. Please try again."

        "insufficient" in s || "balance" in s || "credit" in s ->
            "This provider is out of credit. Try another model in Settings."

        // OpenCode's free tier refuses a request it does not recognise as its
        // own client's — an x-opencode-request header is enough to turn an
        // otherwise identical request from 200 into 403 FreeTierError, which is
        // why this section sits in front of the key section. For the month this
        // was misread as a rejected key, the user went to check a key that was
        // never the problem.
        "free tier" in s || "can only be used from within" in s ->
            "The free-tier provider rejected the request; this is not a key issue. Try again or switch models."

        "no api keys" in s ->
            "No key set for this provider. Add a key in Settings."

        isMissingSession(raw) ->
            "This chat is no longer on the daemon. Start a new chat."

        // The engine prefixes every upstream failure with the status and keeps
        // the provider's own message after it. Replacing that with "temporarily
        // unavailable" hid the one line the user could act on — a rejected
        // parameter, a model this key cannot use, a quota, a content refusal.
        // Show the provider's words; only a failure with no message at all falls
        // through to the generic line below.
        upstream != null -> upstream

        "401" in s || "403" in s || "unauthorized" in s || "forbidden" in s || "invalid api key" in s ->
            "The provider rejected the key. Check the key in Settings."

        "unknown model" in s || "model is required" in s || "unknown provider" in s ->
            "The selected model or provider was not found. Check the model settings."

        "429" in s || "rate limit" in s || "capacity" in s || "too many" in s ->
            "The provider is busy. It will retry automatically, or send again in a few seconds."

        "deadline exceeded" in s || "timeout" in s || "timed out" in s ->
            "The provider took too long to respond. Please try again."

        "tunnel" in s || "connection refused" in s || "no such host" in s ->
            "Cannot reach the daemon. Check that the machine running ai-engine is still up."

        "502" in s || "503" in s || "504" in s || "upstream" in s || "unavailable" in s ->
            "The provider is temporarily unavailable. Try again later."

        else ->
            "Failed to send the message. Please try again."
    }
}

/**
 * The provider's own failure, when the engine reported one. Returns null for
 * errors that did not come from an upstream call, so the callers can keep their
 * local wording for those.
 */
private fun providerFailure(raw: String): String? {
    val match = Regex(
        """upstream HTTP\s+(\d{3})(?::\s*([\s\S]*))?""",
        RegexOption.IGNORE_CASE,
    ).find(raw) ?: return null
    val status = match.groupValues[1]
    val detail = match.groupValues[2].trim()
    return if (detail.isBlank()) {
        "The provider returned HTTP $status."
    } else {
        "The provider returned HTTP $status: $detail"
    }
}

/**
 * True when the daemon answered that it does not know this session. History
 * lives on the daemon, so a chat the phone still lists can be gone there; the
 * send fails with a 404, and retrying it cannot work. Requires both words so an
 * upstream "model not found" is not mistaken for it.
 */
fun isMissingSession(raw: String): Boolean {
    val s = raw.lowercase()
    return "session" in s && ("not found" in s || "not_found" in s)
}
