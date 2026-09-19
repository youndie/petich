package io.github.youndie.petich.conformance

import io.github.youndie.petich.PetichStepRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a member of the corpus's saga claims to have done.
 *
 * The same registration rule as [ConformancePayload], and for the same reason: `Petich.stepRecords`
 * is stored as polymorphic JSON keyed by the member's key, so a subject's `Json` has to know this
 * type under [PetichStepRecord] or the record cases fail at serialisation rather than at a rule.
 */
@Serializable
@SerialName("conformance_record")
public data class ConformanceRecord(
    val marker: String,
) : PetichStepRecord()
