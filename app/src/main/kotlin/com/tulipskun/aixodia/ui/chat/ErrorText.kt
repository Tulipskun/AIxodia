package com.tulipskun.aixodia.ui.chat

/**
 * Turns a raw error from the ai-engine daemon into a short English message the
 * user can act on. The raw text is never shown: it is Go error output and
 * changes with every upstream provider. Pure function, no Android types, so it
 * is unit-tested on the JVM.
 */
fun friendlyError(raw: String): String {
    val s = raw.lowercase()
    return when {
        raw.isBlank() ->
            "An unknown error occurred. Please try again."

        "insufficient" in s || "balance" in s || "credit" in s ->
            "This provider is out of credit. Try another model in Settings."

        "429" in s || "rate limit" in s || "capacity" in s || "too many" in s ->
            "The provider is busy. It will retry automatically, or send again in a few seconds."

        // OpenCode's free tier refuses a request it does not recognise as its
        // own client's — an x-opencode-request header is enough to turn an
        // otherwise identical request from 200 into 403 FreeTierError, which is
        // why this section sits in front of the key section. For the month this
        // was misread as a rejected key, the user went to check a key that was
        // never the problem.
        "free tier" in s || "can only be used from within" in s ->
            "The free-tier provider rejected the request; this is not an API key issue. Try again or switch models."

        "401" in s || "403" in s || "unauthorized" in s || "forbidden" in s || "invalid api key" in s ->
            "The provider rejected the API key. Check the key in Settings."

        "no api keys" in s ->
            "No API key set for this provider. Add a key in Settings."

        "unknown model" in s || "model is required" in s || "unknown provider" in s ->
            "The selected model or provider was not found. Check the model settings."

        "deadline exceeded" in s || "timeout" in s || "timed out" in s ->
            "The provider took too long to respond. Please try again."

        "502" in s || "503" in s || "504" in s || "upstream" in s || "unavailable" in s ->
            "The provider is temporarily unavailable. Try again later."

        "tunnel" in s || "connection refused" in s || "no such host" in s ->
            "Cannot reach the daemon. Check that the machine running ai-engine is still up."

        isMissingSession(raw) ->
            "This chat is no longer on the daemon. Start a new chat."

        else ->
            "Failed to send the message. Please try again."
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
