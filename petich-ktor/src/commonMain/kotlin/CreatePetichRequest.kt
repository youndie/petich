package io.github.youndie.petich.ktor

import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.ResumePayload
import kotlinx.serialization.Serializable

@Serializable
public data class CreatePetichRequest(
    val id: String,
    val type: String,
    val payload: PetichPayload,
)

@Serializable
public data class ResumePetichRequest(
    val payload: PetichPayload? = null,
    val resumePayload: ResumePayload? = null,
)

@Serializable
public data class PetichResponse(
    val id: String,
    val status: String,
    val requiredAction: String? = null,
    val error: String? = null,
)

@Serializable
public data class ErrorResponse(
    val error: String,
    val details: String? = null,
)

public fun Petich.toResponse(
    requiredAction: String? = null,
    error: String? = null,
): PetichResponse =
    PetichResponse(
        id = this.id,
        status = this.status.name,
        requiredAction = requiredAction,
        error = error,
    )
