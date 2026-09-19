# d8gp core

Kotlin Multiplatform code shared by every d8gp app: the Android app, the iOS
app, and the desktop server. The goal is that only views are platform code:
SwiftUI on iOS, Compose on Android and desktop. Protocols, models, network and
state live here once.

| Module | What | Targets |
|---|---|---|
| `plugin-protocol` | The plugin contract: manifest parsing, permissions and the network allowlist, semver, RPC v1 frames and the coroutine `RpcPeer`, the install and crash-loop state machine. Moved from `d8gp-app-android`, where it was the Kotlin twin of the RN `plugin-host-core`. | JVM, iOS |
| `ios-framework` | Builds `D8gpCore.xcframework`, the one framework the iOS app links. Exports every shared module. | iOS |

Planned next: `netcore` (D8gp Net events, NIP-44, relay pool, seed verifier,
currently vendored into the Android app from `d8gp/net`) and `remote-protocol`
(pairing, sessions and frames between the desktop server and its remotes).

## Rules

- Shared code goes in `commonMain` and may not import `java.*` or Apple
  frameworks. Anything that must differ per platform is an `expect`
  declaration with one `actual` per target, kept as small as possible.
- The URL parsing behind `hostAllowed` is deliberately per platform: each
  platform checks a URL with the parser its network stack connects with.
- Wire formats stay byte-compatible with the TypeScript plugin SDK
  (`d8gp/sdk-plugin`); the tests are ports of its suites.

## Build and test

Needs JDK 17+ and, for iOS targets, Xcode.

```sh
./gradlew jvmTest                           # JVM
./gradlew iosSimulatorArm64Test             # the same tests on the iOS simulator
./gradlew :ios-framework:assembleD8gpCoreXCFramework
```

The framework lands in `ios-framework/build/XCFrameworks/{debug,release}/`.

## Using it from another d8gp repo

Check this repo out next to the consumer, as `d8gp-core`:

```
workspace/
├── d8gp-core/
├── d8gp-app-android/
└── d8gp-server/
```

The consumer's `settings.gradle.kts` includes the build, and depends on modules
by coordinate. Gradle substitutes the local project, so there is nothing to
publish:

```kotlin
// settings.gradle.kts
includeBuild(System.getenv("D8GP_CORE_ROOT") ?: "../d8gp-core")

// build.gradle.kts
implementation("com.d8gp.core:plugin-protocol")
```

Kotlin, coroutines and serialization versions must match the consumers'
exactly, since both builds share one classpath.
