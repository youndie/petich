plugins {
    kotlin("multiplatform")
    // The saga's payload is stored as polymorphic JSON, so this module serialises it — the same
    // job PetichTable does with Exposed's json column on the other side.
    kotlin("plugin.serialization")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // The target this module exists for. petich-postgres is Exposed over JDBC, and JDBC is a JVM
    // interface rather than a protocol: it does not travel. Until this module a Kotlin/Native
    // service could build the engine and had nowhere to put a saga.
    //
    // `jvm()` as well, and not out of symmetry: the conformance corpus runs on both targets against
    // the same SQL, which is what makes a finding a property of the store rather than of a platform.
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // api on all four: this module implements their interfaces, so a consumer needs to
                // be able to name the supertypes at compile time.
                api(projects.petichCore)
                api(projects.petichOutboxCore)
                api(projects.petichIdempotency)
                api(projects.petichScheduler)

                // `sqlx4k`, NOT `sqlx4k-postgres`, and this is the load-bearing line of the file.
                //
                // The plain artefact is the database-agnostic half — Driver, Transaction,
                // Statement, ResultSet — and that is all this store touches: it is handed a driver
                // the application opened and never opens one. The Postgres part of this module is
                // its SQL, not its dependency.
                //
                // Taking the driver instead would put its Rust runtime on every consumer, and a
                // Kotlin/Native binary that links two sqlx4k drivers does not link at all: they
                // define the same symbols (`duplicate symbol: std::panicking::EMPTY_PANIC`), paid
                // for in a neighbouring repository. A store that carries no driver cannot cause
                // that collision, whichever one the application brings.
                api(libs.sqlx4k.core)

                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                // The corpus, and a driver to run it against. Both belong to the test: an
                // application wiring up this store already has a driver of its own.
                implementation(projects.petichConformance)
                implementation(libs.sqlx4k.postgres)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// A REAL POSTGRES FOR BOTH TARGETS, AND WHY IT IS WIRED HERE.
//
// Half of what this module promises is SQL: the version predicate, `ON CONFLICT DO NOTHING` as an
// atomic claim, the filters behind findExpired and findDue. None of it exists anywhere but in a
// database, so the suite needs one — and `linuxX64Test` cannot use Testcontainers, which is a JVM
// library. So the container is started by Gradle, once, and both test tasks are pointed at it.
//
// Docker was already required to run this repository's tests (petich-chronik's transactional test
// uses Testcontainers), so this asks for nothing new. What it does not do is skip: a test that
// quietly passes when there is no database is how a store ships untested.
val postgresContainer = "petich-sqlx4k-postgres-test"
val postgresPort = 54329
// The credentials are separate because the driver takes them separately; a URL carrying them is
// parsed by one half of sqlx4k and not the other.
val postgresUrl = "postgresql://127.0.0.1:$postgresPort/petich"
val postgresUser = "petich"
val postgresPassword = "petich"

val startTestPostgres by tasks.registering(Exec::class) {
    description = "Start the Postgres the store's tests run against."
    commandLine(
        "sh",
        "-c",
        """
        docker rm -f $postgresContainer >/dev/null 2>&1 || true
        docker run --rm -d --name $postgresContainer \
            -e POSTGRES_USER=petich -e POSTGRES_PASSWORD=petich -e POSTGRES_DB=petich \
            -p $postgresPort:5432 postgres:18-alpine >/dev/null
        # READY MEANS "POSTGRES ANSWERS ON THE ADDRESS THE TESTS USE", and both halves of that
        # sentence were wrong here once. B-17 is the flake they caused.
        #
        # It was `docker exec … pg_isready`: the server asked from INSIDE the container. Measured on
        # this image, that answers about 0.7-0.9 s before Postgres answers on the published port
        # (1.65 s vs 2.32 s, 1.65 vs 2.47, 2.05 vs 2.94 over three fresh containers) — the window a
        # driver connects into and comes back from with `Io :: Unexpected error occurred`.
        #
        # A TCP connect to the mapped port does NOT close it, and that is the part worth knowing:
        # docker's proxy accepts the connection whether or not anything is listening behind it. Right
        # after the old check said ready, the port accepted 8 times out of 8 while Postgres answered
        # 0 of those 8. A check that looks like evidence and is not is worse than no check.
        #
        # So the question is asked in the protocol, from the host, through the published port —
        # `pg_isready` in a container on the host network, which needs no client on the runner.
        for attempt in ${'$'}(seq 1 90); do
            docker run --rm --network host postgres:18-alpine \
                pg_isready -h 127.0.0.1 -p $postgresPort -U petich -d petich >/dev/null 2>&1 && exit 0
            sleep 1
        done
        echo "postgres did not answer on 127.0.0.1:$postgresPort in 90s" >&2
        exit 1
        """.trimIndent(),
    )
}

val stopTestPostgres by tasks.registering(Exec::class) {
    description = "Stop the Postgres started for the store's tests."
    isIgnoreExitValue = true
    commandLine("sh", "-c", "docker rm -f $postgresContainer >/dev/null 2>&1 || true")
}

// Every test task of this module, whichever target: the URL arrives through the environment because
// that is the one channel both a JVM test and a Kotlin/Native test can read.
tasks.withType<org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest>().configureEach {
    dependsOn(startTestPostgres)
    finalizedBy(stopTestPostgres)
    environment("PETICH_TEST_POSTGRES_URL", postgresUrl)
    environment("PETICH_TEST_POSTGRES_USER", postgresUser)
    environment("PETICH_TEST_POSTGRES_PASSWORD", postgresPassword)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(startTestPostgres)
    finalizedBy(stopTestPostgres)
    environment("PETICH_TEST_POSTGRES_URL", postgresUrl)
    environment("PETICH_TEST_POSTGRES_USER", postgresUser)
    environment("PETICH_TEST_POSTGRES_PASSWORD", postgresPassword)
}
