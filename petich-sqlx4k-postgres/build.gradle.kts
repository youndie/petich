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
        // bash and not sh: the readiness check below opens a TCP connection through /dev/tcp, which
        // dash does not have — and checking readiness the way the TESTS connect is the whole point
        // of this rewrite.
        "bash",
        "-c",
        """
        docker rm -f $postgresContainer >/dev/null 2>&1 || true
        docker run --rm -d --name $postgresContainer \
            -e POSTGRES_USER=petich -e POSTGRES_PASSWORD=petich -e POSTGRES_DB=petich \
            -p $postgresPort:5432 postgres:18-alpine >/dev/null
        # READY MEANS "ANSWERS ON THE PORT THE TESTS USE".
        #
        # This used to be `docker exec … pg_isready`, which asks the server from INSIDE the
        # container and says nothing about the published port — the one every test connects to.
        # Between "ready inside" and "the mapping accepts" there is a window, and a driver that
        # connects into it fails with an error about I/O rather than about a database that is not
        # up yet. That is what B-17 is about, and this closes the half of it that is ours.
        #
        # Two questions, both from the host: the port accepts a connection, and Postgres answers the
        # startup protocol on it.
        for attempt in ${'$'}(seq 1 90); do
            if (exec 3<>/dev/tcp/127.0.0.1/$postgresPort) 2>/dev/null &&
               docker run --rm --network host postgres:18-alpine \
                   pg_isready -h 127.0.0.1 -p $postgresPort -U petich -d petich >/dev/null 2>&1; then
                exit 0
            fi
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
