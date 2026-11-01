package com.wingman.core.api

import kotlinx.serialization.json.Json

/** The one Json configuration used for the wire format, by both the HTTP client and fixtures. */
val WingmanJson: Json = Json {
    // Contract: response objects may gain new optional properties in /v1; clients must ignore them.
    ignoreUnknownKeys = true
    // Optional request fields left null are omitted (contract treats absent == null for them).
    encodeDefaults = false
    // Required-but-nullable fields (e.g. Draft.text) have no Kotlin default, so a missing key
    // is a decoding error, exactly like the contract's `required`.
    explicitNulls = true
}
