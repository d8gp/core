plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The Matrix shapes a remote renders, with no SDK behind them: the iPhone links
// these, the server fills them in. Kept apart from :matrix so iOS never pulls
// in Trixnity.
kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
