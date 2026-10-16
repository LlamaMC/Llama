package com.llamamc.llama.plugin;

import com.llamamc.llama.command.CommandRegistry;
import com.llamamc.llama.config.PermissionsConfig;
import com.llamamc.llama.event.EventBus;
import com.llamamc.llama.permission.PermissionService;
import com.llamamc.llama.tick.TickLoop;
import com.llamamc.llama.tick.TickScheduler;
import com.llamamc.llamaapi.ILlama;
import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.command.ICommandSender;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.network.INetworkServer;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.player.IPlayerRegistry;
import com.llamamc.llamaapi.plugin.IPlugin;
import com.llamamc.llamaapi.registry.IRegistries;
import com.llamamc.llamaapi.scheduler.IScheduler;
import com.llamamc.llamaapi.world.IWorldManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixture plugins are compiled here and packed into real jars. Their classes are not on the test class path, so
 * they can only be loaded from the jar.
 */
@Timeout(120)
class PluginLoaderTest {

    private static final Map<String, String> FIXTURES = Map.of(
            "Lifecycle", """
                    package fixture;

                    import com.llamamc.llama.plugin.PluginTestSupport;
                    import com.llamamc.llamaapi.plugin.IPlugin;
                    import com.llamamc.llamaapi.plugin.IPluginContext;
                    import org.slf4j.Logger;

                    public final class Lifecycle implements IPlugin {
                        public Lifecycle(IPluginContext context, Logger logger) {
                            PluginTestSupport.record("construct:" + context.pluginId());
                            PluginTestSupport.record("logger:" + logger.getName());
                        }

                        @Override
                        public void load() {
                            PluginTestSupport.record("load:lifecycle");
                        }

                        @Override
                        public void enable() {
                            PluginTestSupport.record("enable:lifecycle");
                        }

                        @Override
                        public void disable() {
                            PluginTestSupport.record("disable:lifecycle");
                        }
                    }
                    """,
            "Provider", """
                    package fixture;

                    import com.llamamc.llama.plugin.PluginTestSupport;
                    import com.llamamc.llama.plugin.PluginTestSupport.Greeting;
                    import com.llamamc.llamaapi.plugin.IPlugin;
                    import com.llamamc.llamaapi.plugin.IPluginContext;

                    public final class Provider implements IPlugin {
                        public Provider(IPluginContext context) {
                            context.provide(Greeting.class, () -> "hello");
                            PluginTestSupport.record("provide:provider");
                        }

                        @Override
                        public void enable() {
                            PluginTestSupport.record("enable:provider");
                        }

                        @Override
                        public void disable() {
                            PluginTestSupport.record("disable:provider");
                        }
                    }
                    """,
            "Consumer", """
                    package fixture;

                    import com.llamamc.llama.plugin.PluginTestSupport;
                    import com.llamamc.llama.plugin.PluginTestSupport.Greeting;
                    import com.llamamc.llamaapi.plugin.IPlugin;

                    public final class Consumer implements IPlugin {
                        public Consumer(Greeting greeting) {
                            PluginTestSupport.record("construct:consumer:" + greeting.text());
                        }

                        @Override
                        public void enable() {
                            PluginTestSupport.record("enable:consumer");
                        }

                        @Override
                        public void disable() {
                            PluginTestSupport.record("disable:consumer");
                        }
                    }
                    """,
            "LlamaAware", """
                    package fixture;

                    import com.llamamc.llama.plugin.PluginTestSupport;
                    import com.llamamc.llamaapi.ILlama;
                    import com.llamamc.llamaapi.plugin.IPlugin;

                    public final class LlamaAware implements IPlugin {
                        public LlamaAware(ILlama llama) {
                            PluginTestSupport.receiveLlama(llama);
                            PluginTestSupport.record("construct:llama");
                        }

                        @Override
                        public void enable() {
                            PluginTestSupport.record("enable:llama");
                        }
                    }
                    """,
            "Broken", """
                    package fixture;

                    import com.llamamc.llamaapi.plugin.IPlugin;
                    import java.time.Clock;

                    public final class Broken implements IPlugin {
                        public Broken(Clock clock) {
                        }
                    }
                    """,
            "Cleanup", """
                    package fixture;

                    import com.llamamc.llama.plugin.PluginTestSupport;
                    import com.llamamc.llamaapi.command.ICommandRegistry;
                    import com.llamamc.llamaapi.event.IEventBus;
                    import com.llamamc.llamaapi.plugin.IPlugin;

                    public final class Cleanup implements IPlugin {
                        private final IEventBus events;
                        private final ICommandRegistry commands;

                        public Cleanup(IEventBus events, ICommandRegistry commands) {
                            this.events = events;
                            this.commands = commands;
                        }

                        @Override
                        public void enable() {
                            events.on(PluginTestSupport.Ping.class, ping -> PluginTestSupport.record("ping"));
                            commands.register(this, new CleanupHandler());
                        }

                        @Override
                        public void disable() {
                            PluginTestSupport.record("disable:cleanup");
                            throw new IllegalStateException("disable failed");
                        }
                    }
                    """,
            "CleanupHandler", """
                    package fixture;

                    import com.llamamc.llama.plugin.PluginTestSupport;
                    import com.llamamc.llamaapi.command.Command;
                    import com.llamamc.llamaapi.command.ICommandContext;

                    public final class CleanupHandler {
                        @Command(name = "cleanup")
                        public void run(ICommandContext context) {
                            PluginTestSupport.record("command");
                        }
                    }
                    """);

    @TempDir
    Path root;

    @BeforeEach
    void clearEvents() {
        PluginTestSupport.clear();
    }

    @Test
    void loadsEnablesAndDisablesInOrderAndInjectsPublishedServices() throws Exception {
        var classes = compileFixtures();
        var plugins = root.resolve("plugins");
        writeJar(plugins.resolve("broken.jar"), descriptor("broken", "fixture.Broken"), classes, "fixture.Broken");
        writeJar(plugins.resolve("consumer.jar"), descriptor("consumer", "fixture.Consumer", "provider"),
                classes, "fixture.Consumer");
        writeJar(plugins.resolve("lifecycle.jar"), descriptor("lifecycle", "fixture.Lifecycle"),
                classes, "fixture.Lifecycle");
        writeJar(plugins.resolve("provider.jar"), descriptor("provider", "fixture.Provider"),
                classes, "fixture.Provider");
        var loader = loader(plugins);

        loader.loadAll();

        assertEquals(PluginState.ENABLED, status(loader, "lifecycle").state());
        assertEquals(PluginState.ENABLED, status(loader, "provider").state());
        assertEquals(PluginState.ENABLED, status(loader, "consumer").state());
        var broken = status(loader, "broken");
        assertEquals(PluginState.DISABLED, broken.state());
        assertTrue(broken.message().contains("java.time.Clock"), broken.message());
        assertEquals(List.of(
                "construct:lifecycle", "logger:lifecycle", "load:lifecycle", "enable:lifecycle",
                "provide:provider", "enable:provider",
                "construct:consumer:hello", "enable:consumer"), PluginTestSupport.events());

        loader.disableAll();

        assertEquals(List.of(
                "construct:lifecycle", "logger:lifecycle", "load:lifecycle", "enable:lifecycle",
                "provide:provider", "enable:provider",
                "construct:consumer:hello", "enable:consumer",
                "disable:consumer", "disable:provider", "disable:lifecycle"), PluginTestSupport.events());
        assertEquals(PluginState.DISABLED, status(loader, "lifecycle").state());
    }

    @Test
    void jarWithoutDescriptorIsDisabledWithAClearMessage() throws Exception {
        var classes = compileFixtures();
        var plugins = root.resolve("plugins");
        writeJar(plugins.resolve("bare.jar"), null, classes, "fixture.Lifecycle");
        var loader = loader(plugins);

        loader.loadAll();

        var bare = status(loader, "bare.jar");
        assertEquals(PluginState.DISABLED, bare.state());
        assertEquals("missing llama-plugin.json", bare.message());
        assertEquals(List.of(), PluginTestSupport.events());
    }

    @Test
    void createsThePluginsDirectoryWhenMissing() {
        var plugins = root.resolve("plugins");
        var loader = loader(plugins);

        loader.loadAll();

        assertTrue(Files.isDirectory(plugins));
        assertEquals(List.of(), loader.statuses());
    }

    @Test
    void closingAContextUnpublishesItsServices() {
        var context = new PluginContext("provider", new EventBus(), new CommandRegistry(permissions()));
        context.provide(PluginTestSupport.Greeting.class, () -> "hi");
        assertEquals(1, context.published().size());

        context.close();

        assertTrue(context.published().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> context.provide(PluginTestSupport.Greeting.class, () -> "again"));
    }

    @Test
    void disabledPluginLosesItsHandlersAndCommandsEvenWhenDisableThrows() throws Exception {
        var classes = compileFixtures();
        var plugins = root.resolve("plugins");
        writeJar(plugins.resolve("cleanup.jar"), descriptor("cleanup", "fixture.Cleanup"), classes,
                "fixture.Cleanup", "fixture.CleanupHandler");
        var permissions = permissions();
        var events = new EventBus();
        var commands = new CommandRegistry(permissions);
        var loader = loader(plugins, new StubLlama(), events, commands, permissions);
        var sender = new Operator();

        loader.loadAll();

        assertEquals(PluginState.ENABLED, status(loader, "cleanup").state());
        events.post(new PluginTestSupport.Ping());
        assertTrue(commands.dispatch(sender, "cleanup"));
        assertEquals(List.of("ping", "command"), PluginTestSupport.events());

        loader.disableAll();
        events.post(new PluginTestSupport.Ping());

        assertEquals(PluginState.DISABLED, status(loader, "cleanup").state());
        assertFalse(commands.dispatch(sender, "cleanup"));
        assertEquals(List.of("ping", "command", "disable:cleanup"), PluginTestSupport.events());
    }

    @Test
    void pluginConstructorReceivesTheLlamaGivenToTheLoader() throws Exception {
        var classes = compileFixtures();
        var plugins = root.resolve("plugins");
        writeJar(plugins.resolve("llama-aware.jar"), descriptor("llama-aware", "fixture.LlamaAware"), classes,
                "fixture.LlamaAware");
        var llama = new StubLlama();
        var loader = loader(plugins, llama);

        loader.loadAll();

        assertEquals(PluginState.ENABLED, status(loader, "llama-aware").state());
        assertSame(llama, PluginTestSupport.llama());
        assertEquals(List.of("construct:llama", "enable:llama"), PluginTestSupport.events());
    }

    private PluginLoader loader(Path plugins) {
        return loader(plugins, new StubLlama());
    }

    private PluginLoader loader(Path plugins, ILlama llama) {
        var permissions = permissions();
        return loader(plugins, llama, new EventBus(), new CommandRegistry(permissions), permissions);
    }

    private PluginLoader loader(Path plugins, ILlama llama, IEventBus events, ICommandRegistry commands,
                                PermissionService permissions) {
        var scheduler = new TickScheduler(new TickLoop(() -> 0L, (phase, error) -> {
        }));
        return new PluginLoader(plugins, PluginLoaderTest.class.getClassLoader(), llama, scheduler, events, commands,
                permissions);
    }

    private static PermissionService permissions() {
        var config = new PermissionsConfig();
        config.setUsers(Map.of());
        config.setGroups(Map.of());
        return new PermissionService(config);
    }

    private static final class Operator implements ICommandSender {

        @Override
        public String name() {
            return "operator";
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void sendMessage(Component message) {
        }
    }

    /** Only the identity of the instance matters to the tests, so every call fails. */
    private static final class StubLlama implements ILlama {

        @Override
        public INetworkServer server() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IEventBus events() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IScheduler scheduler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IWorldManager worlds() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IRegistries registries() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ICommandRegistry commands() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IPermissionService permissions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IPlayerRegistry players() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }
    }

    private static PluginStatus status(PluginLoader loader, String id) {
        return loader.statuses().stream()
                .filter(status -> status.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static String descriptor(String id, String main, String... depends) {
        var dependencies = Arrays.stream(depends)
                .map(dependency -> "\"" + dependency + "\"")
                .collect(Collectors.joining(", "));
        return """
                {"id": "%s", "name": "%s", "version": "1.0.0", "main": "%s", "depends": [%s]}
                """.formatted(id, id, main, dependencies);
    }

    private Path compileFixtures() throws Exception {
        var sources = Files.createDirectories(root.resolve("sources").resolve("fixture"));
        var classes = Files.createDirectories(root.resolve("classes"));
        var classpath = String.join(File.pathSeparator,
                location(IPlugin.class), location(Logger.class), location(PluginTestSupport.class));
        var arguments = new ArrayList<String>(List.of("-classpath", classpath, "-d", classes.toString()));
        for (var fixture : FIXTURES.entrySet()) {
            var source = sources.resolve(fixture.getKey() + ".java");
            Files.writeString(source, fixture.getValue());
            arguments.add(source.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the plugin tests need a JDK with a Java compiler");
        var errors = new ByteArrayOutputStream();
        var exitCode = compiler.run(null, errors, errors, arguments.toArray(String[]::new));
        assertEquals(0, exitCode, errors::toString);
        return classes;
    }

    private static String location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static void writeJar(Path jar, String descriptor, Path classes, String... classNames) throws Exception {
        Files.createDirectories(jar.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (descriptor != null) {
                addEntry(output, PluginDescriptor.FILE_NAME, descriptor.getBytes(StandardCharsets.UTF_8));
            }
            for (var className : classNames) {
                var path = className.replace('.', '/') + ".class";
                addEntry(output, path, Files.readAllBytes(classes.resolve(path)));
            }
        }
    }

    private static void addEntry(JarOutputStream output, String name, byte[] content) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }
}
