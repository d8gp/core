package com.d8gp.core.matrix

import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule

// Where a session's state lives is platform business: a JVM server writes an
// embedded database, an Android host would use Room. Keeping it behind expect/
// actual is what lets MatrixSession itself stay common.
expect suspend fun matrixRepositoriesModule(dataPath: String): RepositoriesModule

expect suspend fun matrixMediaStoreModule(mediaPath: String): MediaStoreModule
