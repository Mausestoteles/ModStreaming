package dev.modstreaming.fabric;

import dev.modstreaming.api.auth.CredentialsProvider;
import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.log.StreamLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fabric client entrypoint.
 *
 * <pre>
 * Fabric → Core → Verification → Artifact → ClassLoader → Bridge
 * </pre>
 *
 * <p>Discovers every loaded mod that embeds {@code META-INF/modstreaming/stream-config.json}
 * (written by the Gradle plugin), collects the mod's optional {@code modstreaming} entrypoints and
 * starts the streaming pipeline for each. Missing authorization, an offline backend or a rejected
 * artifact never crash Minecraft: the mod's hooks simply stay no-ops.</p>
 */
public final class ModStreamingFabric implements ClientModInitializer {

    public static final String MOD_ID = "modstreaming";
    public static final String ENTRYPOINT_KEY = "modstreaming";

    private static final StreamLogger LOG = FabricLogging.create("ModStreaming");
    private static volatile StreamingBootstrap bootstrap;

    /** The running bootstrap, once {@link #onInitializeClient()} ran. */
    public static Optional<StreamingBootstrap> bootstrap() {
        return Optional.ofNullable(bootstrap);
    }

    @Override
    public void onInitializeClient() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            List<StreamedMod> mods = discover(loader);
            if (mods.isEmpty()) {
                LOG.debug("no mods with an embedded stream config found");
                return;
            }
            StreamingBootstrap instance = new StreamingBootstrap(LOG, ModStreamingFabric.class.getClassLoader());
            bootstrap = instance;
            LOG.info("streaming " + mods.size() + " project(s): " + mods.stream().map(StreamedMod::projectId).toList());
            instance.start(mods);
        } catch (Throwable t) {
            LOG.error("ModStreaming failed to initialize; streamed features stay disabled", t);
        }
    }

    static List<StreamedMod> discover(FabricLoader loader) {
        Map<String, List<ModStreamingEntrypoint>> entrypoints = new HashMap<>();
        try {
            for (EntrypointContainer<ModStreamingEntrypoint> container
                    : loader.getEntrypointContainers(ENTRYPOINT_KEY, ModStreamingEntrypoint.class)) {
                String modId = container.getProvider().getMetadata().getId();
                try {
                    entrypoints.computeIfAbsent(modId, k -> new ArrayList<>()).add(container.getEntrypoint());
                } catch (Throwable t) {
                    LOG.error("failed to instantiate modstreaming entrypoint of mod " + modId, t);
                }
            }
        } catch (Throwable t) {
            LOG.error("failed to collect modstreaming entrypoints", t);
        }

        String minecraftVersion = loader.getModContainer("minecraft")
                .map(mc -> mc.getMetadata().getVersion().getFriendlyString()).orElse(null);
        Path configDir = loader.getConfigDir();

        List<StreamedMod> mods = new ArrayList<>();
        for (ModContainer container : loader.getAllMods()) {
            String modId = container.getMetadata().getId();
            Optional<Path> configPath = container.findPath(ModStreaming.CONFIG_RESOURCE);
            if (configPath.isEmpty()) {
                continue;
            }
            try {
                StreamConfig config;
                try (InputStream in = Files.newInputStream(configPath.get())) {
                    config = StreamConfig.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (minecraftVersion != null && !minecraftVersion.equals(config.coordinates().minecraftVersion())) {
                    LOG.warn(modId + ": stream config targets Minecraft " + config.coordinates().minecraftVersion()
                            + " but " + minecraftVersion + " is running");
                }
                List<ModStreamingEntrypoint> modEntrypoints = entrypoints.getOrDefault(modId, List.of());
                CredentialsProvider credentials = null;
                for (ModStreamingEntrypoint entrypoint : modEntrypoints) {
                    try {
                        credentials = entrypoint.credentials();
                    } catch (Throwable t) {
                        LOG.error(modId + ": credentials() of " + entrypoint.getClass().getName() + " threw", t);
                    }
                    if (credentials != null) {
                        break;
                    }
                }
                if (credentials == null) {
                    FileCredentialsProvider fileProvider = FileCredentialsProvider.forProject(configDir, config.projectId());
                    fileProvider.writeTemplateIfMissing();
                    credentials = fileProvider;
                }
                mods.add(new StreamedMod(modId, config, credentials, modEntrypoints));
            } catch (Throwable t) {
                LOG.error(modId + ": embedded stream config is invalid; streamed features stay disabled", t);
            }
        }
        return mods;
    }
}
