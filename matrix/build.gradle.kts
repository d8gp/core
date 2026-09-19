plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    // JVM only for now: the desktop server holds the Matrix session and the
    // iPhone is a remote. Android adds androidTarget() when it consumes this,
    // which also means bumping its Kotlin to match (see gradle/libs.versions.toml).
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":matrix-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.trixnity.client)
            implementation(libs.trixnity.client.cryptodriver.vodozemac)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.trixnity.client.repository.exposed)
            implementation(libs.trixnity.client.media.okio)
            implementation(libs.ktor.client.java)
            implementation(libs.exposed.r2dbc)
            implementation(libs.r2dbc.h2)
            implementation(libs.h2)
        }
    }
}
