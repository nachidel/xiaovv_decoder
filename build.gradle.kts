plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "fr.nachidel.xiaovv"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("fr.nachidel.xiaovv.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}