// The consumer's own code. It has to NAME the library's types rather than merely depend on it:
// a build that only declares a dependency proves the resolution, and this is meant to prove the
// klib is usable from native code — that it compiles against it and that the linker is satisfied.
//
// Nothing is executed beyond a print. Running a saga needs a store, and a native store is what
// B-09 is for; the bar here is resolve, compile, link.

import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.isTerminal
import io.github.youndie.petich.timeoutMs

// A payload of the consumer's own, which is how every application uses the engine. Not
// @Serializable: this one is never written to a store, and the probe deliberately does not apply
// the serialization plugin — one plugin fewer between the question and the answer.
private object ProbePayload : PetichPayload()

fun main() {
    val clock = PetichClock { 0L }

    val petich =
        Petich(
            id = "probe",
            type = "native-consumer-probe",
            status = PetichStatus.DRAFT,
            payload = ProbePayload,
        )

    println(
        "petich resolved, compiled and linked for linuxX64: " +
            "${petich.id} phase=${petich.currentPhase} " +
            "terminal=${petich.status.isTerminal()} " +
            "enrichmentTimeoutMs=${PetichPhase.ENRICHMENT.timeoutMs} " +
            "clock=${clock.nowEpochMs()}",
    )
}
