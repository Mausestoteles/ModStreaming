package dev.modstreaming.backend

import dev.modstreaming.backend.config.BackendConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

/**
 * Starts the reference backend.
 *
 * Environment:
 * - `MODSTREAMING_BACKEND_CONFIG` path to a JSON config (see `backend.example.json`)
 * - `MODSTREAMING_BACKEND_PORT`, `MODSTREAMING_BACKEND_STORAGE`, `MODSTREAMING_AUTH_PROVIDER` overrides
 * - `MODSTREAMING_PUBLISHER_TOKEN` a publisher token valid for every project
 */
fun main() {
    val config = BackendConfig.load()
    val deps = BackendDependencies.fromConfig(config)
    LoggerFactory.getLogger("ModStreaming.Backend").info(
        "starting on {}:{} with authorization provider '{}' and storage '{}'",
        config.host, config.port, config.authorization.provider, config.storageDir,
    )
    embeddedServer(CIO, port = config.port, host = config.host) {
        modStreamingBackend(deps)
    }.start(wait = true)
}
