package com.llamamc.llama;

import com.llamamc.llama.config.LlamaConfig;
import com.llamamc.llama.config.PermissionsConfig;
import com.llamamc.llama.config.WorldsConfig;
import com.llamamc.llama.console.Console;
import com.llamamc.llama.console.ConsoleCommand;
import com.llamamc.llama.crash.CrashReport;
import com.llamamc.llama.crash.ThreadDumps;
import com.llamamc.llama.event.EventBus;
import com.llamamc.llama.network.minecraft.MinecraftNetworkServer;
import com.llamamc.llama.network.minecraft.ServerStatus;
import com.llamamc.llama.network.minecraft.protocol.MinecraftProtocolVersion;
import com.llamamc.llama.registry.MinecraftRegistries;
import com.llamamc.llama.tick.TickLoop;
import com.llamamc.llama.tick.TickMetrics;
import com.llamamc.llama.watchdog.GcPauseTracker;
import com.llamamc.llama.watchdog.Watchdog;
import com.llamamc.llama.watchdog.WatchdogSettings;
import com.llamamc.llama.world.WorldManager;
import com.llamamc.llamaapi.ILlama;
import com.llamamc.llamaapi.config.ConfigFactory;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.network.INetworkServer;
import com.llamamc.llamaapi.registry.IRegistries;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.WorldType;
import com.llamamc.llamaapi.world.IWorldManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Llama implements ILlama {
    private static final Logger LOGGER = LoggerFactory.getLogger("Llama");
    private static final Duration TICK_STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HUNG_TICK = Duration.ofSeconds(5);
    private static final Duration HUNG_TICK_STOP_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(60);

    private final Path crashReportDirectory = Path.of("crash-reports");
    private final IEventBus eventBus = new EventBus();
    private final CountDownLatch shutdownRequested = new CountDownLatch(1);
    private final CountDownLatch shutdownComplete = new CountDownLatch(1);
    private final AtomicReference<ShutdownRequest> shutdownRequest = new AtomicReference<>();

    private LlamaConfig llamaConfig;
    private PermissionsConfig permissionsConfig;
    private WorldsConfig worldsConfig;
    private GcPauseTracker gcPauseTracker;
    private TickLoop tickLoop;
    private Watchdog watchdog;
    private Console console;
    private INetworkServer server;
    private WorldManager worldManager;
    private MinecraftRegistries registries;

    public void start() {
        var startedAt = System.nanoTime();
        LOGGER.info("Starting Llama");
        loadConfigs();

        var protocolVersion = MinecraftProtocolVersion.parse(llamaConfig.getMinecraftVersion());
        registries = new MinecraftRegistries(protocolVersion);
        worldManager = new WorldManager(worldsConfig, Path.of("worlds"));
        server = new MinecraftNetworkServer(new ServerStatus(llamaConfig.getMotd(), llamaConfig.getMaxPlayers()),
                protocolVersion, worldManager.mainWorld(), registries);
        server.start(new InetSocketAddress(llamaConfig.getHost(), llamaConfig.getPort()));
        gcPauseTracker = GcPauseTracker.install();
        tickLoop = new TickLoop(gcPauseTracker::totalPauseNanos, this::onTickError);
        watchdog = new Watchdog(tickLoop, gcPauseTracker::totalPauseNanos, WatchdogSettings.defaults(), reason -> restart(reason, null));
        console = new Console(() -> requestShutdown(ExitCode.STOP, "Ctrl+C in console", null, false));
        registerConsoleCommands();

        tickLoop.start();
        watchdog.start();
        console.start();
        LOGGER.info("Done ({})! Type 'help' for a list of commands.", String.format(Locale.ROOT, "%.3fs", (System.nanoTime() - startedAt) / 1e9));
    }

    private void loadConfigs() {
        this.llamaConfig = ConfigFactory.load(LlamaConfig.class);
        if (llamaConfig.getHost() == null) {
            llamaConfig.setHost("127.0.0.1");
        }
        if (llamaConfig.getPort() == 0) {
            llamaConfig.setPort(25565);
        }
        if (llamaConfig.getMotd() == null) {
            llamaConfig.setMotd("A Llama server");
        }
        if (llamaConfig.getMaxPlayers() == 0) {
            llamaConfig.setMaxPlayers(10);
        }
        if (!llamaConfig.isOnlineMode()) {
            llamaConfig.setOnlineMode(false);
        }
        if (llamaConfig.getMinecraftVersion() == null) {
            llamaConfig.setMinecraftVersion("26.3");
        }
        this.permissionsConfig = ConfigFactory.load(PermissionsConfig.class);
        if (permissionsConfig.getUsers() == null) {
            var users = new HashMap<String, Map<String, List<String>>>();
            var user = new HashMap<String, List<String>>();
            user.put("permissions", List.of("llamamc.command.example"));
            user.put("groups", List.of("admin"));
            var zeroUUID = "00000000-0000-0000-0000-000000000000";
            users.put(zeroUUID, user);
            permissionsConfig.setUsers(users);
        }
        if (permissionsConfig.getGroups() == null) {
            var groups = new HashMap<String, List<String>>();
            groups.put("admin", List.of("llamamc.command.example"));
            permissionsConfig.setGroups(groups);
        }
        this.worldsConfig = ConfigFactory.load(WorldsConfig.class);
        if (this.worldsConfig.getWorlds() == null) {
            var worlds = new HashMap<String, WorldsConfig.ConfigWorld>();
            worlds.put("main", new WorldsConfig.ConfigWorld("_llama", WorldType.NORMAL, DimensionType.OVERWORLD));
            worlds.put("building", new WorldsConfig.ConfigWorld("_minecraft", WorldType.FLAT, DimensionType.THE_END));
            this.worldsConfig.setWorlds(worlds);
        }
        this.worldsConfig.getWorlds().values().forEach(world -> {
            if (world.getSeed() == null) {
                world.setSeed("0");
            }
        });
        ConfigFactory.save(llamaConfig);
        ConfigFactory.save(permissionsConfig);
        ConfigFactory.save(worldsConfig);
    }

    private void registerConsoleCommands() {
        console.register(new ConsoleCommand("stop", "Stops the server", _ -> requestShutdown(ExitCode.STOP, "stop command", null, false)));
        console.register(new ConsoleCommand("restart", "Restarts the server (requires start.sh)", _ -> requestShutdown(ExitCode.RESTART, "restart command", null, false)));
        console.register(new ConsoleCommand("tps", "Shows ticks per second and tick times", _ -> printTps()));
    }

    private void printTps() {
        var snapshot = tickLoop.metrics().snapshot(System.nanoTime());
        var text = new StringBuilder(String.format(Locale.ROOT,
                "TPS (5s, 1m, 5m): %.2f, %.2f, %.2f%nMSPT (1m): avg %.2f ms, p95 %.2f ms, max %.2f ms",
                snapshot.tps5s(), snapshot.tps1m(), snapshot.tps5m(),
                snapshot.msptAverage(), snapshot.msptP95(), snapshot.msptMax()));
        snapshot.phaseAverages().forEach((phase, millis) ->
                text.append(String.format(Locale.ROOT, "%n  %s: avg %.3f ms", phase, millis)));
        var abandoned = tickLoop.abandonedThreads();
        if (!abandoned.isEmpty()) {
            text.append("\nHung tick threads still alive: ").append(abandoned.size())
                    .append(" (a restart is recommended)");
        }
        LOGGER.info(text.toString());
    }

    private void onTickError(String phase, Throwable error) {
        if (error instanceof OutOfMemoryError) {
            restart("out of memory in tick phase '" + phase + "'", error);
            return;
        }
        LOGGER.error("Error in tick phase '{}'", phase, error);
        watchdog.reportCoreError();
    }

    /**
     * Stops the server and asks {@code start.sh} to start it again. A crash report is written.
     */
    public void restart(String reason, Throwable cause) {
        requestShutdown(ExitCode.RESTART, reason, cause, true);
    }

    @Override
    public void shutdown() {
        requestShutdown(ExitCode.STOP, "shutdown requested", null, false);
    }

    /**
     * Requests the server to stop. Only the first request counts. The shutdown itself runs on the thread that
     * called {@link #awaitShutdown()}, so it never depends on the tick thread, which may be the one that hangs.
     */
    public void requestShutdown(ExitCode exitCode, String reason, Throwable cause, boolean crashReport) {
        if (!shutdownRequest.compareAndSet(null, new ShutdownRequest(exitCode, reason, cause, crashReport))) {
            return;
        }
        Thread.ofPlatform().name("Llama-Shutdown-Timeout").daemon().start(() -> {
            try {
                if (shutdownComplete.await(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("Shutdown did not finish within " + SHUTDOWN_TIMEOUT.toSeconds()
                    + " s, halting the JVM");
            Runtime.getRuntime().halt(exitCode.code());
        });
        shutdownRequested.countDown();
    }

    public ExitCode awaitShutdown() throws InterruptedException {
        shutdownRequested.await();
        var request = shutdownRequest.get();
        try {
            runShutdown(request);
        } finally {
            shutdownComplete.countDown();
        }
        return request.exitCode();
    }

    public boolean awaitShutdownComplete(Duration timeout) throws InterruptedException {
        return shutdownComplete.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runShutdown(ShutdownRequest request) {
        logShutdown(request);
        if (console != null) {
            console.close();
        }
        if (watchdog != null) {
            watchdog.stop();
        }
        if (tickLoop != null) {
            var tickStart = tickLoop.tickStartNanos();
            var hung = tickStart != 0 && System.nanoTime() - tickStart > HUNG_TICK.toNanos();
            var timeout = hung ? HUNG_TICK_STOP_TIMEOUT : TICK_STOP_TIMEOUT;
            if (!tickLoop.stop(timeout)) {
                LOGGER.error("Tick thread did not stop within {} s", timeout.toSeconds());
            }
        }
        if (server != null) {
            server.close();
        }
        if (worldManager != null) {
            try {
                worldManager.close();
            } catch (IOException error) {
                LOGGER.error("Could not close worlds", error);
            }
        }
        if (request.crashReport()) {
            writeCrashReport(request);
        }
        if (gcPauseTracker != null) {
            gcPauseTracker.close();
        }
        LOGGER.info("Server stopped");
    }

    private void logShutdown(ShutdownRequest request) {
        if (request.exitCode() != ExitCode.RESTART) {
            LOGGER.info("Stopping server ({})", request.reason());
            return;
        }

        LOGGER.warn("Restarting server: {}", request.reason());
    }

    private void writeCrashReport(ShutdownRequest request) {
        try {
            var report = new CrashReport(request.reason()).cause(request.cause());
            if (tickLoop != null) {
                var metrics = tickLoop.metrics().snapshot(System.nanoTime());
                var tick = new StringBuilder(String.format(Locale.ROOT,
                        "TPS (5s, 1m, 5m): %.2f, %.2f, %.2f%nMSPT (1m): avg %.2f, p95 %.2f, max %.2f%n"
                                + "Ticks: %d%nCurrent phase: %s%n",
                        metrics.tps5s(), metrics.tps1m(), metrics.tps5m(), metrics.msptAverage(),
                        metrics.msptP95(), metrics.msptMax(), metrics.totalTicks(), tickLoop.currentPhase()));
                tick.append("\nTick thread:\n").append(ThreadDumps.dump(tickLoop.tickThread()));
                for (Thread abandoned : tickLoop.abandonedThreads()) {
                    tick.append("\nAbandoned tick thread:\n").append(ThreadDumps.dump(abandoned));
                }
                report.section("Tick", tick.toString());
            }
            var file = report.write(crashReportDirectory);
            LOGGER.error("Crash report saved to {}", file.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not write crash report", e);
        }
    }

    public TickLoop tickLoop() {
        return tickLoop;
    }

    @Override
    public INetworkServer server() {
        return server;
    }

    @Override
    public IEventBus events() {
        return eventBus;
    }

    @Override
    public IWorldManager worlds() {
        return worldManager;
    }

    @Override
    public IRegistries registries() {
        return registries;
    }

    public LlamaConfig getLlamaConfig() {
        return llamaConfig;
    }

    public PermissionsConfig getPermissionsConfig() {
        return permissionsConfig;
    }

    public WorldsConfig getWorldsConfig() {
        return worldsConfig;
    }

    private record ShutdownRequest(ExitCode exitCode, String reason, Throwable cause, boolean crashReport) {
    }
}
