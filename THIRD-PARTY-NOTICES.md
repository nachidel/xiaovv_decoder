# Third-party notices

Xiaovv Decoder contains or depends on third-party software. The PolyForm
Noncommercial License 1.0.0 applies only to Xiaovv Decoder's own code and does
not replace the licenses of third-party components.

Packaged distributions include a `THIRD-PARTY-LICENSES/` directory containing
license texts, attribution notices and a component-to-license map.

## Direct runtime dependencies

The versions below are declared by the current `build.gradle.kts` or normally
resolved by the Kotlin JVM plugin used by this project.

| Component | Version | License |
|---|---:|---|
| SLF4J API | 2.0.18 | MIT |
| Logback Classic / Core | 1.6.3 | EPL-2.0 or LGPL-2.1 |
| Kotlin Coroutines Core JVM | 1.10.2 | Apache-2.0 |
| Eclipse Paho MQTT Java Client | 1.2.5 | EPL-2.0 / EDL-1.0 dual licensing |
| ChromeCast Java API V2 | 0.12.20 | Apache-2.0 |
| Kotlin standard library | 2.4.0 (normally supplied by Kotlin plugin) | Apache-2.0 plus upstream third-party notices |

## Known runtime transitives

`chromecast-java-api-v2:0.12.20` declares Jackson, Protobuf Javalite, JmDNS and
SLF4J as compile dependencies. The current static license bundle therefore also
contains notices/licenses for:

- Jackson Annotations 2.20;
- Jackson Databind 2.20.0;
- Jackson Core 2.20.0, including its shaded FastDoubleParser notices;
- Protobuf Javalite 4.33.2;
- JmDNS 3.6.3;
- JetBrains annotations used by Kotlin/Coroutines.

See `THIRD-PARTY-LICENSES/COMPONENTS.md` for the detailed mapping.

## Xiaovv favicon

`src/main/resources/favicon.webp` is the unchanged favicon served by the
official Xiaovv website, https://www.xiaovv.net/ (retrieved 2026-10-04).
The Xiaovv mark remains the property of its owner; the project's software
license does not grant rights to that mark.

## Native Java runtime

The Windows and Linux `jpackage` distributions contain a Java runtime.
`jpackage` preserves that runtime's own legal files under `runtime/legal/`.
That directory must not be removed from redistributed native packages.

## Dependency changes

This notice reflects the dependency versions checked when this file was
prepared. Gradle may resolve a different version if dependencies, constraints or
plugin versions are changed later. When upgrading dependencies, review and
update `THIRD-PARTY-LICENSES/` as part of the release process.

Where an upstream component's original license or notice conflicts with this
summary, the upstream license/notice controls.
