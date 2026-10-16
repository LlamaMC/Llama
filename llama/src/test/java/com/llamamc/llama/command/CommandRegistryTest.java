package com.llamamc.llama.command;

import com.llamamc.llama.config.PermissionsConfig;
import com.llamamc.llama.permission.PermissionService;
import com.llamamc.llamaapi.command.Argument;
import com.llamamc.llamaapi.command.ArgumentType;
import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommand;
import com.llamamc.llamaapi.command.ICommandContext;
import com.llamamc.llamaapi.command.ICommandSender;
import com.llamamc.llamaapi.component.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class CommandRegistryTest {
    private static final int THREADS = 8;
    private static final int DISPATCH_ROUNDS = 1000;
    private static final long WAIT_SECONDS = 20;
    private static final String DENIED = "You do not have permission to use this command.";

    @Test
    void dispatchRunsCommandUnderItsNameWithArgumentsInOrder() {
        var registry = registry();
        var greeter = new Greeter();
        registry.register(greeter, greeter);

        assertTrue(registry.dispatch(TestSender.operator(), "greet Alice  Bob"));
        assertEquals(1, greeter.runs);
        assertEquals("greet", greeter.last.label());
        assertEquals(List.of("Alice", "Bob"), greeter.last.arguments());
        assertThrows(UnsupportedOperationException.class, () -> greeter.last.arguments().add("Carol"));
    }

    @Test
    void contextExposesTheSenderThatDispatchedTheLine() {
        var registry = registry();
        var greeter = new Greeter();
        registry.register(greeter, greeter);
        var sender = TestSender.operator();

        assertTrue(registry.dispatch(sender, "greet"));
        assertTrue(greeter.last.sender() == sender);
    }

    @Test
    void aliasDispatchesToSameCommandAndMatchingIgnoresCase() {
        var registry = registry();
        var greeter = new Greeter();
        registry.register(greeter, greeter);
        var sender = TestSender.operator();

        assertTrue(registry.dispatch(sender, "HI Alice"));
        assertEquals("hi", greeter.last.label());
        assertTrue(registry.dispatch(sender, "Greet"));
        assertEquals("greet", greeter.last.label());
        assertEquals(2, greeter.runs);
    }

    @Test
    void dispatchReturnsFalseForUnknownBlankAndNullLines() {
        var registry = registry();
        var greeter = new Greeter();
        registry.register(greeter, greeter);
        var sender = TestSender.operator();

        assertFalse(registry.dispatch(sender, "unknown Alice"));
        assertFalse(registry.dispatch(sender, "   "));
        assertFalse(registry.dispatch(sender, ""));
        assertFalse(registry.dispatch(sender, null));
        assertEquals(0, greeter.runs);
    }

    @Test
    void dispatchRejectsNullSender() {
        var registry = registry();

        assertThrows(IllegalArgumentException.class, () -> registry.dispatch(null, "greet"));
    }

    @Test
    void duplicateAliasThrowsAndFirstCommandKeepsWorking() {
        var registry = registry();
        var greeter = new Greeter();
        registry.register(greeter, greeter);
        var clash = new Clash();
        var sender = TestSender.operator();

        var error = assertThrows(IllegalStateException.class, () -> registry.register(clash, clash));

        assertTrue(error.getMessage().contains("hi"));
        assertFalse(registry.dispatch(sender, "other"));
        assertTrue(registry.dispatch(sender, "greet"));
        assertEquals(1, greeter.runs);
    }

    @Test
    void registeringSameCommandTwiceThrows() {
        var registry = registry();
        var greeter = new Greeter();
        registry.register(greeter, greeter);

        assertThrows(IllegalStateException.class, () -> registry.register(greeter, greeter));
    }

    @Test
    void registerRejectsNullOwnerNullHandlerUnannotatedAndBlankLabelCommands() {
        var registry = registry();
        var greeter = new Greeter();

        assertThrows(IllegalArgumentException.class, () -> registry.register(new Object(), null));
        assertThrows(IllegalArgumentException.class, () -> registry.register(null, greeter));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Object(), new Object()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Object(), new Unannotated()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Object(), new Blank()));
    }

    @Test
    void registerRejectsLabelsContainingWhitespace() {
        var registry = registry();
        var spacedName = new SpacedName();
        var spacedAlias = new SpacedAlias();

        assertThrows(IllegalArgumentException.class, () -> registry.register(spacedName, spacedName));
        assertThrows(IllegalArgumentException.class, () -> registry.register(spacedAlias, spacedAlias));
    }

    @Test
    void registerRejectsLabelsContainingIdeographicSpace() {
        var registry = registry();
        var spaced = new IdeographicSpaced();

        assertThrows(IllegalArgumentException.class, () -> registry.register(spaced, spaced));
    }

    @Test
    void unregisterRemovesEveryLabelOnce() {
        var registry = registry();
        var greeter = new Greeter();
        var owner = new Object();
        var sender = TestSender.operator();
        registry.register(owner, greeter);

        assertTrue(registry.unregister(owner));
        assertFalse(registry.dispatch(sender, "greet"));
        assertFalse(registry.dispatch(sender, "hi"));
        assertFalse(registry.unregister(owner));

        registry.register(owner, greeter);
        assertTrue(registry.dispatch(sender, "hi"));
    }

    @Test
    void unregisterRemovesOnlyTheCommandsOfItsOwner() {
        var registry = registry();
        var greeter = new Greeter();
        var repeater = new Repeater();
        var owner = new Object();
        registry.register(owner, greeter);
        registry.register(new Object(), repeater);

        assertTrue(registry.unregister(owner));
        assertFalse(registry.dispatch(TestSender.operator(), "greet"));
        assertTrue(registry.dispatch(TestSender.operator(), "repeat 1 x"));
        assertEquals(List.of("x x1"), repeater.calls);
    }

    @Test
    void throwingCommandIsReportedAsFailedReply() {
        var registry = registry();
        var exploder = new Exploder();
        registry.register(exploder, exploder);
        var sender = TestSender.operator();

        assertTrue(registry.dispatch(sender, "explode"));
        assertEquals(List.of("Command failed: boom"), sender.messages);
    }

    @Test
    void senderWithoutNodeIsDeniedAndCommandDoesNotRun() {
        var permissions = permissions();
        var registry = new CommandRegistry(permissions);
        var greeter = new Greeter();
        registry.register(greeter, greeter);
        var guest = TestSender.guest();

        assertTrue(registry.dispatch(guest, "greet Alice"));

        assertEquals(0, greeter.runs);
        assertEquals(List.of(DENIED), guest.messages);
        assertTrue(permissions.nodes().contains("llama.command.greet"));
    }

    @Test
    void permissionAnnotationNamesTheNodeAndAliasesShareIt() {
        var permissions = permissions();
        var registry = new CommandRegistry(permissions);
        var secret = new Secret();
        registry.register(secret, secret);
        var guest = TestSender.guest();

        assertTrue(registry.dispatch(guest, "s"));
        assertEquals(List.of(DENIED), guest.messages);
        assertEquals(0, secret.runs);
        assertTrue(permissions.nodes().contains("test.secret"));
        assertFalse(permissions.nodes().contains("llama.command.secret"));
        assertTrue(registry.dispatch(TestSender.operator(), "secret"));
        assertEquals(1, secret.runs);
    }

    @Test
    void methodCommandParsesArgumentsByPositionAndRuns() {
        var registry = registry();
        var repeater = new Repeater();
        registry.register(repeater, repeater);
        var sender = TestSender.operator();

        assertTrue(registry.dispatch(sender, "repeat 3 hello"));
        assertTrue(registry.dispatch(sender, "rep 2 hi"));

        assertEquals(List.of("hello x3", "hi x2"), repeater.calls);
        assertEquals(List.of(), sender.messages);
    }

    @Test
    void methodCommandRepliesWithUsageWhenRequiredArgumentIsMissing() {
        var registry = registry();
        var repeater = new Repeater();
        registry.register(repeater, repeater);
        var sender = TestSender.operator();

        assertTrue(registry.dispatch(sender, "repeat 3"));

        assertEquals(List.of("Usage: repeat <count> <text>"), sender.messages);
        assertEquals(List.of(), repeater.calls);
    }

    @Test
    void methodCommandRepliesWithErrorWhenArgumentDoesNotParse() {
        var registry = registry();
        var repeater = new Repeater();
        registry.register(repeater, repeater);
        var sender = TestSender.operator();

        assertTrue(registry.dispatch(sender, "repeat many hi"));

        assertEquals(List.of("Invalid count 'many': expected an integer"), sender.messages);
        assertEquals(List.of(), repeater.calls);
    }

    @Test
    void methodWithoutArgumentAnnotationIsRejectedNamingTheMethod() {
        var registry = registry();
        var handler = new MissingArgument();

        var error = assertThrows(IllegalArgumentException.class, () -> registry.register(handler, handler));

        assertTrue(error.getMessage().contains("unannotated"), error.getMessage());
        assertFalse(registry.dispatch(TestSender.operator(), "bad"));
    }

    @Test
    void concurrentRegisterAndDispatchKeepEveryLabel() throws Exception {
        var registry = registry();
        var commands = List.<ICommand>of(new Worker0(), new Worker1(), new Worker2(), new Worker3(),
                new Worker4(), new Worker5(), new Worker6(), new Worker7());
        var labels = List.of("worker0", "worker1", "worker2", "worker3", "worker4", "worker5", "worker6", "worker7");
        var sender = TestSender.operator();
        var start = new CountDownLatch(1);
        var failures = new ConcurrentLinkedQueue<Throwable>();

        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            var futures = new ArrayList<Future<?>>(THREADS);

            for (var index = 0; index < THREADS; index++) {
                var command = commands.get(index);
                var label = labels.get(index);
                futures.add(executor.submit(() -> runWorker(registry, sender, command, label, labels, start, failures)));
            }
            start.countDown();

            for (var future : futures) {
                future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
        assertTrue(failures.isEmpty(), () -> "Workers failed: " + failures);
        var missing = labels.stream().filter(label -> !registry.dispatch(sender, label)).toList();
        assertEquals(List.of(), missing);
        assertTrue(commands.stream().allMatch(registry::unregister), "Every worker command should unregister");
    }

    private static void runWorker(CommandRegistry registry, ICommandSender sender, ICommand command, String label,
                                  List<String> labels, CountDownLatch start, Queue<Throwable> failures) {
        try {
            start.await();
            registry.register(command, command);
            dispatchRepeatedly(registry, sender, label, labels);
        } catch (Exception error) {
            failures.add(error);
        }
    }

    private static void dispatchRepeatedly(CommandRegistry registry, ICommandSender sender, String ownLabel,
                                           List<String> labels) {
        for (var round = 0; round < DISPATCH_ROUNDS; round++) {
            if (!registry.dispatch(sender, ownLabel)) {
                throw new IllegalStateException("Own command " + ownLabel + " was not found");
            }

            // Other workers may not have registered yet, so their results are not checked; only exceptions matter.
            for (var label : labels) {
                registry.dispatch(sender, label);
            }
        }
    }

    private static CommandRegistry registry() {
        return new CommandRegistry(permissions());
    }

    private static PermissionService permissions() {
        return new PermissionService(new PermissionsConfig());
    }

    @Command(name = "greet", aliases = {"hi"})
    private static final class Greeter implements ICommand {
        private int runs;
        private ICommandContext last;

        @Override
        public void execute(ICommandContext context) {
            runs++;
            last = context;
        }
    }

    @Command(name = "other", aliases = {"HI"})
    private static final class Clash implements ICommand {

        @Override
        public void execute(ICommandContext context) {
        }
    }

    private static final class Unannotated implements ICommand {

        @Override
        public void execute(ICommandContext context) {
        }
    }

    @Command(name = " ")
    private static final class Blank implements ICommand {

        @Override
        public void execute(ICommandContext context) {
        }
    }

    @Command(name = "my cmd")
    private static final class SpacedName implements ICommand {

        @Override
        public void execute(ICommandContext context) {
        }
    }

    @Command(name = "spaced", aliases = {"my hi"})
    private static final class SpacedAlias implements ICommand {

        @Override
        public void execute(ICommandContext context) {
        }
    }

    @Command(name = "my　cmd")
    private static final class IdeographicSpaced implements ICommand {

        @Override
        public void execute(ICommandContext context) {
        }
    }

    @Command(name = "explode")
    private static final class Exploder implements ICommand {

        @Override
        public void execute(ICommandContext context) {
            throw new IllegalStateException("boom");
        }
    }

    @Command(name = "secret", aliases = {"s"}, permission = "test.secret")
    private static final class Secret implements ICommand {
        private int runs;

        @Override
        public void execute(ICommandContext context) {
            runs++;
        }
    }

    private static final class Repeater {
        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Command(name = "repeat", aliases = {"rep"})
        public void repeat(ICommandContext context,
                           @Argument(name = "text", type = ArgumentType.STRING, position = 1) String text,
                           @Argument(name = "count", type = ArgumentType.INTEGER, position = 0) int count) {
            calls.add(text + " x" + count);
        }
    }

    private static final class MissingArgument {

        @Command(name = "bad")
        public void unannotated(ICommandContext context, String text) {
        }
    }

    private abstract static class Worker implements ICommand {

        @Override
        public void execute(ICommandContext context) {
        }
    }

    @Command(name = "worker0")
    private static final class Worker0 extends Worker {
    }

    @Command(name = "worker1")
    private static final class Worker1 extends Worker {
    }

    @Command(name = "worker2")
    private static final class Worker2 extends Worker {
    }

    @Command(name = "worker3")
    private static final class Worker3 extends Worker {
    }

    @Command(name = "worker4")
    private static final class Worker4 extends Worker {
    }

    @Command(name = "worker5")
    private static final class Worker5 extends Worker {
    }

    @Command(name = "worker6")
    private static final class Worker6 extends Worker {
    }

    @Command(name = "worker7")
    private static final class Worker7 extends Worker {
    }

    private static final class TestSender implements ICommandSender {
        private final boolean operator;
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private TestSender(boolean operator) {
            this.operator = operator;
        }

        static TestSender operator() {
            return new TestSender(true);
        }

        static TestSender guest() {
            return new TestSender(false);
        }

        @Override
        public String name() {
            return operator ? "operator" : "guest";
        }

        @Override
        public boolean hasPermission(String permission) {
            return operator;
        }

        @Override
        public void sendMessage(Component message) {
            messages.add(message.text());
        }
    }
}
