package io.github.youndie.petich.conformance

import io.github.youndie.petich.PetichPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The payload the corpus stores.
 *
 * A store keeps `Petich.payload` as polymorphic JSON, so the corpus cannot use an anonymous type:
 * whatever it writes has to be `@Serializable` and has to carry a `@SerialName`, or the format
 * would depend on where this class happens to live.
 *
 * WHAT A SUBJECT MUST DO ABOUT IT. Polymorphic serialisation is registration, not reflection, on
 * every platform. A store handed a `Json` that does not know this type will fail every case in
 * `PetichStoreConformance` with a serialisation error rather than with a rule — so the subject
 * wires a `SerializersModule` registering [ConformancePayload] under [PetichPayload] before it
 * hands the corpus a repository. That is a property of the corpus, not a defect in the store, and
 * it is the first thing to check when every case fails at once.
 */
@Serializable
@SerialName("conformance_payload")
public data class ConformancePayload(
    val marker: String,
) : PetichPayload()
