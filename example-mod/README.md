# Example mod (Stonecutter + Loom)

A Fabric client mod for Minecraft 1.21.1 and 1.21.4 that keeps `GreetingService` (interface),
`GreetingHook` and `TitleScreenMixin` public while `SecretGreetingService` is `@Streamed`.

```
src/main/java/com/example/streamdemo/
  api/GreetingService.java          public interface
  hook/GreetingHook.java            public hook: Bridge.get(GreetingService.class).ifPresent(...)
  mixin/TitleScreenMixin.java       calls the hook (mixins stay local)
  impl/SecretGreetingService.java   @Streamed implementation (+ nested Scrambler) - NOT in the public jar
  StreamDemoStreaming.java          optional "modstreaming" entrypoint (credentials, result callback)
```

## Build

This is a separate Gradle build (it downloads Minecraft). First publish the framework:

```bash
../gradlew publishToMavenLocal      # from the repository root: ./gradlew publishToMavenLocal
./gradlew :1.21.1:build             # or :1.21.4:build; "Set active project to 1.21.4" switches the IDE view
```

Results per version (`versions/<mc>/build/`):

* `libs/streamdemo-1.0.0+<mc>.jar` – public jar, without `com/example/streamdemo/impl/**`, with
  `META-INF/modstreaming/stream-config.json` and the nested `modstreaming-fabric` runtime.
* `modstreaming/streamdemo-1.0.0+<mc>-streamed-remapped.jar` – the remapped streamed jar (input of `packageStreamed`).
* `modstreaming/split-report.json` – what was moved and which services were detected.

Verify: `unzip -l versions/1.21.1/build/libs/streamdemo-1.0.0+1.21.1.jar | grep impl` prints nothing.

## Stream it

```bash
./gradlew generateStreamedKeyPair                  # once; put the public key into gradle.properties
export MODSTREAMING_PUBLISH_TOKEN=<token from backend config>
./gradlew :1.21.1:packageStreamed                  # signs + encrypts -> versions/1.21.1/build/modstreaming/*.msa
./gradlew :1.21.1:publishStreamed                  # uploads to streaming.endpoint (default http://localhost:8080)
```

Players drop `config/modstreaming/example-mod.json` with their credentials into the game
directory, e.g. `{ "credentials": { "licenseKey": "EXAMPLE-LICENSE-KEY-1" } }` for the
`license-key` provider of the reference backend. On the title screen the streamed greeting is
logged; without authorization the hook silently does nothing.

`gradle.properties` contains a demo public key so the project builds out of the box; the matching
private key is not part of the repository, so generate your own pair before packaging.
