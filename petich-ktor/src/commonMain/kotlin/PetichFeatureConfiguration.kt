package ru.workinprogress.petich.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
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
