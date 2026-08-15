plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// Java 25 for every module at once.
//
// At once is not tidiness but a Gradle requirement: it tags variants with the
// org.gradle.jvm.version attribute and refuses to build a module on 21 against a dependency on 25,
// saying "looking for a library compatible with JVM runtime version 21, but ... is only compatible
// with JVM runtime version 25 or newer". So it is all of them or none.
subprojects {
    afterEvaluate {
        (extensions.findByName("kotlin") as? org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension)
            ?.jvmToolchain(25)
    }
}
