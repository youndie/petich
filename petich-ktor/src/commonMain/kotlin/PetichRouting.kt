package ru.workinprogress.petich.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichResult
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.isTerminal

fun Route.petichRouting(
    engine: PetichEngine,
    readOnlyRepository: PetichRepository,
) {
    route("/api/v1/petiches") {
        post {
            val request = call.receive<CreatePetichRequest>()

            val initialPetich =
                Petich(
                    id = request.id,
                    type = request.type,
                    status = PetichStatus.PROCESSING,
                    payload = request.payload,
                )

            processAndRespond(call, engine, initialPetich)
        }

        post("/{id}/resume") {
            val id =
                call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing petich id"))

            val request = call.receive<ResumePetichRequest>()

            val existingPetich =
                readOnlyRepository.findById(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Petich not found"))

            if (existingPetich.status.isTerminal()) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Petich is already in terminal state", existingPetich.status.name),
                )
            }

            val updatedPetich =
                existingPetich.copy(
                    payload = request.payload ?: existingPetich.payload,
                    resumePayload = request.resumePayload,
                )

            processAndRespond(call, engine, updatedPetich)
        }

        get("/{id}") {
            val id =
                call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing petich id"))

            val petich =
                readOnlyRepository.findById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Petich not found"))

            call.respond(HttpStatusCode.OK, petich.toResponse())
        }
    }
}

private suspend fun processAndRespond(
    call: ApplicationCall,
    engine: PetichEngine,
    petich: Petich,
) {
    when (val result = engine.process(petich)) {
        is PetichResult.Success -> {
            call.respond(HttpStatusCode.OK, result.petich.toResponse())
        }

        is PetichResult.ActionRequired -> {
            call.respond(
                HttpStatusCode.Accepted,
                result.petich.toResponse(requiredAction = result.actionType),
            )
        }

        is PetichResult.Error -> {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                PetichResponse(
                    id = petich.id,
                    status = PetichStatus.REJECTED.name,
                    error = result.reason,
                ),
            )
        }

        is PetichResult.SystemFailure -> {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal system error", result.details),
            )
        }
    }
}
