package com.d8gp.core.matrix

import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.store.repository.exposed.exposed
import io.r2dbc.h2.H2ConnectionConfiguration
import io.r2dbc.h2.H2ConnectionFactory
import java.io.File
import okio.Path.Companion.toPath
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig

// An embedded H2 file database per account. Built from a configuration rather
// than an r2dbc URL so the file path needs no escaping.
actual suspend fun matrixRepositoriesModule(dataPath: String): RepositoriesModule {
    val dir = File(dataPath).apply { mkdirs() }
    val factory = H2ConnectionFactory(
        H2ConnectionConfiguration.builder()
            .file(File(dir, "matrix").absolutePath)
            .username("d8gp")
            .password("")
            .build(),
    )
    return RepositoriesModule.exposed(R2dbcDatabase.connect(factory, R2dbcDatabaseConfig.Builder()))
}

actual suspend fun matrixMediaStoreModule(mediaPath: String): MediaStoreModule {
    File(mediaPath).mkdirs()
    return MediaStoreModule.okio(mediaPath.toPath())
}
