import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// The JVM floor (JvmFloor.kt) for every module at once.
//
// At once is not tidiness but a Gradle requirement: a module built below the floor cannot depend on
// one advertising it, and it says so — "looking for a library compatible with JVM runtime version
// 21, but ... is only compatible with JVM runtime version 25 or newer". So it is all of them or
// none, and the number lives in one place (JvmFloor.kt).
//
// The catch is WHO advertises. The java plugin stamps org.gradle.jvm.version on its variants from
// the toolchain, so petich-postgres (kotlin("jvm")) carries it; the multiplatform plugin's jvm()
// target does not stamp it at all, so the other five published their bytecode with nothing in
// the metadata saying so. A consumer below the floor resolved them, compiled against them, and met
// UnsupportedClassVersionError at class loading — the refusal quoted above is exactly what should
// have happened and did not. The attribute is therefore set here by hand for the jvm() targets.
//
// This is not a timing problem and moving the toolchain out of afterEvaluate does not fix it: the
// attribute is still absent when the toolchain is applied at plugin-application time.
subprojects {
    afterEvaluate {
        (extensions.findByName("kotlin") as? org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension)
            ?.jvmToolchain(JVM_FLOOR)

        // Named configurations rather than a name prefix: these two are the ones published as
        // jvmApiElements-published and jvmRuntimeElements-published, and they are what a consumer's
        // resolution reads. Sources carry no bytecode and need no floor.
        configurations
            .matching { it.name == "jvmApiElements" || it.name == "jvmRuntimeElements" }
            .configureEach {
                attributes {
                    attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, JVM_FLOOR)
                }
            }
    }
}
