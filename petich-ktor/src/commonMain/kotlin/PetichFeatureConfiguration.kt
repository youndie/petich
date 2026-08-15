package ru.workinprogress.petich.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.workinprogress.petich.OptimisticLockException
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichRepository

class PetichFeatureConfiguration {
    lateinit var engine: PetichEngine
    lateinit var repository: PetichRepository
}

val PetichFeature =
    createApplicationPlugin(
        name = "PetichFeature",
        createConfiguration = ::PetichFeatureConfiguration,
    ) {
        val engine = pluginConfig.engine
        val repository = pluginConfig.repository

        application.install(StatusPages) {
            exception<OptimisticLockException> { call, _ ->
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Concurrent modification", "Please retry the request"),
                )
            }
        }

        application.routing {
            petichRouting(engine, repository)
        }
    }
