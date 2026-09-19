package com.d8gp.plugins.protocol

import kotlinx.serialization.json.Json

// House JSON policy: absent optionals are omitted, exactly like JSON.stringify
// dropping undefined fields on the JS side.
val ProtocolJson: Json = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
    encodeDefaults = false
}
