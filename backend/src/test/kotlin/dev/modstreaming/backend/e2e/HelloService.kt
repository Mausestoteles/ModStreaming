package dev.modstreaming.backend.e2e

/** Public-side service interface for the end-to-end test. */
interface HelloService {
    fun hello(name: String): String
}
