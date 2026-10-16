package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommand;
import com.llamamc.llamaapi.command.ICommandContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    private LoggerContext loggers;
    private ReplyCapture capture;

    @BeforeEach
    void captureReplies() {
        loggers = (LoggerContext) LogManager.getContext(false);
        capture = new ReplyCapture();
        capture.start();
        loggers.getConfiguration().getRootLogger().addAppender(capture, null, null);
        loggers.updateLoggers();
    }

    @AfterEach
    void stopCapturingReplies() {
        loggers.getConfiguration().getRootLogger().removeAppender(capture.getName());
        loggers.updateLoggers();
        capture.stop();
    }

    @Test
    void dispatchRunsCommandUnderItsNameWithArgumentsInOrder() {
        var registry = new CommandRegistry();
        var greeter = new Greeter();
        registry.register(greeter);

        assertTrue(registry.dispatch("greet Alice  Bob"));
        assertEquals(1, greeter.runs);
        assertEquals("greet", greeter.last.label());
        assertEquals(List.of("Alice", "Bob"), greeter.last.arguments());
        assertThrows(UnsupportedOperationException.class, () -> greeter.last.arguments().add("Carol"));
    }

    @Test
    void aliasDispatchesToSameCommandAndMatchingIgnoresCase() {
        var registry = new CommandRegistry();
        var greeter = new Greeter();
        registry.register(greeter);

        assertTrue(registry.dispatch("HI Alice"));
        assertEquals("hi", greeter.last.label());
        assertTrue(registry.dispatch("Greet"));
        assertEquals("greet", greeter.last.label());
        assertEquals(2, greeter.runs);
    }

    @Test
    void dispatchReturnsFalseForUnknownBlankAndNullLines() {
        var registry = new CommandRegistry();
        var greeter = new Greeter();
        registry.register(greeter);

        assertFalse(registry.dispatch("unknown Alice"));
        assertFalse(registry.dispatch("   "));
        assertFalse(registry.dispatch(""));
        assertFalse(registry.dispatch(null));
        assertEquals(0, greeter.runs);
    }

    @Test
    void duplicateAliasThrowsAndFirstCommandKeepsWorking() {
        var registry = new CommandRegistry();
        var greeter = new Greeter();
        registry.register(greeter);

        var clash = assertThrows(IllegalStateException.class, () -> registry.register(new Clash()));

        assertTrue(clash.getMessage().contains("hi"));
        assertFalse(registry.dispatch("other"));
        assertTrue(registry.dispatch("greet"));
        assertEquals(1, greeter.runs);
    }

    @Test
    void registeringSameCommandTwiceThrows() {
        var registry = new CommandRegistry();
        var greeter = new Greeter();
        registry.register(greeter);

        assertThrows(IllegalStateException.class, () -> registry.register(greeter));
    }

    @Test
    void registerRejectsNullUnannotatedAndBlankLabelCommands() {
        var registry = new CommandRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Unannotated()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Blank()));
    }

    @Test
    void registerRejectsLabelsContainingWhitespace() {
        var registry = new CommandRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register(new SpacedName()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new SpacedAlias()));
    }

    @Test
    void unregisterRemovesEveryLabelOnce() {
        var registry = new CommandRegistry();
        var greeter = new Greeter();
        registry.register(greeter);

        assertTrue(registry.unregister(greeter));
        assertFalse(registry.dispatch("greet"));
        assertFalse(registry.dispatch("hi"));
        assertFalse(registry.unregister(greeter));

        registry.register(greeter);
        assertTrue(registry.dispatch("hi"));
    }

    @Test
    void throwingCommandIsReportedAsFailedReply() {
        var registry = new CommandRegistry();
        registry.register(new Exploder());

        assertTrue(registry.dispatch("explode"));
        assertEquals(List.of("Command failed: boom"), capture.messages);
    }

    @Test
    void concurrentRegisterAndDispatchKeepEveryLabel() throws Exception {
        var registry = new CommandRegistry();
        var commands = List.<ICommand>of(new Worker0(), new Worker1(), new Worker2(), new Worker3(),
                new Worker4(), new Worker5(), new Worker6(), new Worker7());
        var labels = List.of("worker0", "worker1", "worker2", "worker3", "worker4", "worker5", "worker6", "worker7");
        var start = new CountDownLatch(1);
        var failures = new ConcurrentLinkedQueue<Throwable>();

        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            var futures = new ArrayList<Future<?>>(THREADS);

            for (var index = 0; index < THREADS; index++) {
                var command = commands.get(index);
                var label = labels.get(index);
                futures.add(executor.submit(() -> runWorker(registry, command, label, labels, start, failures)));
            }
            start.countDown();

            for (var future : futures) {
                future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
        assertTrue(failures.isEmpty(), () -> "Workers failed: " + failures);
        var missing = labels.stream().filter(label -> !registry.dispatch(label)).toList();
        assertEquals(List.of(), missing);
        assertTrue(commands.stream().allMatch(registry::unregister), "Every worker command should unregister");
    }

    private static void runWorker(CommandRegistry registry, ICommand command, String label, List<String> labels,
                                  CountDownLatch start, Queue<Throwable> failures) {
        try {
            start.await();
            registry.register(command);
            dispatchRepeatedly(registry, label, labels);
        } catch (Exception error) {
            failures.add(error);
        }
    }

    private static void dispatchRepeatedly(CommandRegistry registry, String ownLabel, List<String> labels) {
        for (var round = 0; round < DISPATCH_ROUNDS; round++) {
            if (!registry.dispatch(ownLabel)) {
                throw new IllegalStateException("Own command " + ownLabel + " was not found");
            }

            // Other workers may not have registered yet, so their results are not checked; only exceptions matter.
            for (var label : labels) {
                registry.dispatch(label);
            }
        }
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

    @Command(name = "explode")
    private static final class Exploder implements ICommand {

        @Override
        public void execute(ICommandContext context) {
            throw new IllegalStateException("boom");
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

    private static final class ReplyCapture extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        ReplyCapture() {
            super("CommandReplyCapture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel() == Level.INFO && CommandRegistry.class.getName().equals(event.getLoggerName())) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }
    }
}
