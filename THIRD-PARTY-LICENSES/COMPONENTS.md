# Third-party component/license map

This directory is shipped with Xiaovv Decoder distributions to keep license
and attribution information readable outside the fat JAR.

## Direct runtime components

| Component | Version | License / notice files |
|---|---:|---|
| org.slf4j:slf4j-api | 2.0.18 | `SLF4J-MIT.txt` |
| ch.qos.logback:logback-classic | 1.6.3 | `LOGBACK-LICENSE.txt`, `EPL-2.0.txt`, `LGPL-2.1.txt` |
| ch.qos.logback:logback-core | 1.6.3 | `LOGBACK-LICENSE.txt`, `EPL-2.0.txt`, `LGPL-2.1.txt` |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm | 1.10.2 | `APACHE-2.0.txt` |
| org.eclipse.paho:org.eclipse.paho.client.mqttv3 | 1.2.5 | `PAHO-LICENSE.txt`, `EPL-2.0.txt`, `EDL-1.0.txt` |
| de.sfuhrm:chromecast-java-api-v2 | 0.12.20 | `APACHE-2.0.txt` |
| org.jetbrains.kotlin:kotlin-stdlib | 2.4.0 (normally resolved by Kotlin plugin) | `APACHE-2.0.txt`, `KOTLIN-STDLIB-NOTICES.txt`, `THREETENBP-BSD-3-CLAUSE.txt`, `BOOST-1.0.txt` |

## Known runtime transitives

`chromecast-java-api-v2:0.12.20` declares the following compile dependencies
that are relevant to the runtime package:

| Component | Version declared by Chromecast | License / notice files |
|---|---:|---|
| com.fasterxml.jackson.core:jackson-annotations | 2.20 | `APACHE-2.0.txt`, `JACKSON-NOTICE.txt` |
| com.fasterxml.jackson.core:jackson-databind | 2.20.0 | `APACHE-2.0.txt`, `JACKSON-NOTICE.txt` |
| com.fasterxml.jackson.core:jackson-core | 2.20.0 (via databind) | `APACHE-2.0.txt`, `JACKSON-NOTICE.txt`, `FASTDOUBLEPARSER-NOTICE.txt`, `FASTDOUBLEPARSER-MIT.txt`, `FASTFLOAT-MIT.txt`, `BIGINT-BSD-2-CLAUSE.txt` |
| com.google.protobuf:protobuf-javalite | 4.33.2 | `PROTOBUF-BSD-3-CLAUSE.txt` |
| org.jmdns:jmdns | 3.6.3 | `APACHE-2.0.txt`, `JMDNS-NOTICE.txt` |
| org.slf4j:slf4j-api | 2.0.17 declared; Xiaovv directly requests 2.0.18 | `SLF4J-MIT.txt` |

Coroutines and Kotlin stdlib also require JetBrains annotations; these are
Apache-2.0 licensed and are covered by `APACHE-2.0.txt`.

## Native Java runtime

The Windows/Linux `jpackage` app image includes a Java runtime. `jpackage`
preserves that runtime's own legal files under `runtime/legal/`. Do not remove
that directory from native distributions.

## Important

This map reflects the dependency declarations checked for the versions above.
Gradle can resolve version conflicts or dependency metadata differently if the
build file is changed. When dependencies are upgraded, update this file and
`THIRD-PARTY-NOTICES.md` accordingly.
