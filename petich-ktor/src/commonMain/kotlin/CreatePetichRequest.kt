package ru.workinprogress.petich.ktor

import kotlinx.serialization.Serializable
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.ResumePayload

@Serializable
data class CreatePetichRequest(
    val id: String,
    val type: String,
    val payload: PetichPayload,
)

@Serializable
data class ResumePetichRequest(
    val payload: PetichPayload? = null,
    val resumePayload: ResumePayload? = null,
)

@Serializable
data class PetichResponse(
    val id: String,
    val status: String,
    val requiredAction: String? = null,
    val error: String? = null,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null,
)

fun Petich.toResponse(
    requiredAction: String? = null,
    error: String? = null,
) = PetichResponse(
    id = this.id,
    status = this.status.name,
    requiredAction = requiredAction,
    error = error,
)
