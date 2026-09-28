package dev.modstreaming.backend.e2e.streamed

import dev.modstreaming.api.Streamed
import dev.modstreaming.backend.e2e.HelloService

/** Implementation that is packaged into the streamed artifact for the end-to-end test. */
@Streamed
class StreamedHello : HelloService {
    override fun hello(name: String): String = "Hello, $name! (streamed)"
}
