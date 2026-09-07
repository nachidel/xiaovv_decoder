# Third-party notices

Xiaovv Decoder contains or depends on third-party software. The PolyForm Noncommercial License 1.0.0 applies to Xiaovv Decoder's own code and does not replace the licenses of third-party components.

The versions below correspond to the direct runtime dependencies declared in `build.gradle.kts` at the time this notice was prepared. The Gradle/Kotlin runtime may also resolve additional transitive components. Packaged distributions must include the license and notice files collected from the actual resolved runtime dependencies.

## Direct runtime dependencies

### SLF4J API 2.0.18

- Artifact: `org.slf4j:slf4j-api:2.0.18`
- License: MIT License
- Project license: https://www.slf4j.org/license.html

### Logback Classic 1.6.3 / Logback Core 1.6.3

- Artifact: `ch.qos.logback:logback-classic:1.6.3`
- Runtime component: `ch.qos.logback:logback-core:1.6.3`
- License: dual-licensed under Eclipse Public License 2.0 or GNU Lesser General Public License 2.1, at the licensee's choice
- Project license: https://logback.qos.ch/license.html

### Kotlin Coroutines 1.10.2

- Artifact: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`
- License: Apache License 2.0
- Project: https://github.com/Kotlin/kotlinx.coroutines

### Eclipse Paho MQTT Java Client 1.2.5

- Artifact: `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`
- Maven metadata license: Eclipse Public License 2.0
- Source headers also reference the Eclipse Distribution License 1.0 where applicable
- Project: https://projects.eclipse.org/projects/iot.paho
- EPL 2.0: https://www.eclipse.org/legal/epl-2.0/

### ChromeCast Java API V2 0.12.20

- Artifact: `de.sfuhrm:chromecast-java-api-v2:0.12.20`
- License: Apache License 2.0
- Project: https://github.com/sfuhrm/chromecast-java-api-v2

### Kotlin standard library

- The Kotlin JVM plugin normally adds the Kotlin standard library to the runtime classpath.
- Version: the version actually resolved by Gradle for the build
- License: Apache License 2.0 for JetBrains-owned Kotlin code; some portions of the Kotlin repository may carry additional third-party notices
- Project: https://github.com/JetBrains/kotlin

## Transitive dependencies

`chromecast-java-api-v2` and other components may bring additional runtime dependencies. Do not treat the list above as a complete legal inventory of every class contained in the final fat JAR.

The build should collect `LICENSE`, `NOTICE`, `COPYING` and similar files from every resolved runtime dependency and ship them under `THIRD-PARTY-LICENSES/`.

Where a dependency's own packaged notice conflicts with this summary, the dependency's original license and notice files control.
