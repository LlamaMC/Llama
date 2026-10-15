package com.llamamc.llama.event;

import com.llamamc.llamaapi.event.EventPriority;
import com.llamamc.llamaapi.event.ICancelableEvent;
import com.llamamc.llamaapi.event.IEvent;
import com.llamamc.llamaapi.event.IRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class EventBusTest {
    private static final int WORKERS = 8;
    private static final int HANDLERS_PER_WORKER = 1_000;
    private static final int OPEN_HANDLERS_PER_WORKER = 10;
    private static final int EVENT_COUNT = 10_000;

    @Test
    void dispatchesByPriorityThenRegistrationOrder() {
        var bus = new EventBus();
        var calls = new ArrayList<String>();
        bus.on(PingEvent.class, EventPriority.MONITOR, event -> calls.add("MONITOR"));
        bus.on(PingEvent.class, event -> calls.add("NORMAL-1"));
        bus.on(PingEvent.class, EventPriority.HIGH, event -> calls.add("HIGH"));
        bus.on(PingEvent.class, EventPriority.LOWEST, event -> calls.add("LOWEST"));
        bus.on(PingEvent.class, EventPriority.NORMAL, event -> calls.add("NORMAL-2"));

        bus.post(new PingEvent());

        assertEquals(List.of("LOWEST", "NORMAL-1", "NORMAL-2", "HIGH", "MONITOR"), calls);
    }

    @Test
    void closeIsIdempotentAndStopsDelivery() {
        var bus = new EventBus();
        var calls = new AtomicInteger();
        var registration = bus.on(PingEvent.class, event -> calls.incrementAndGet());

        bus.post(new PingEvent());
        registration.close();
        registration.close();
        bus.post(new PingEvent());

        assertEquals(1, calls.get());
    }

    @Test
    void handlerExceptionDoesNotStopLaterHandlers() {
        var bus = new EventBus();
        var reached = new AtomicInteger();
        bus.on(PingEvent.class, EventPriority.LOW, event -> {
            throw new IllegalStateException("handler failure under test");
        });
        bus.on(PingEvent.class, EventPriority.HIGH, event -> reached.incrementAndGet());

        bus.post(new PingEvent());

        assertEquals(1, reached.get());
    }

    @Test
    void deliversOnlyEventsOfTheRegisteredType() {
        var bus = new EventBus();
        var pings = new AtomicInteger();
        var everything = new AtomicInteger();
        bus.on(PingEvent.class, event -> pings.incrementAndGet());
        bus.on(IEvent.class, event -> everything.incrementAndGet());

        bus.post(new PongEvent());
        bus.post(new PingEvent());

        assertEquals(1, pings.get());
        assertEquals(2, everything.get());
    }

    @Test
    void monitorSeesCancelRequestedByHighestHandler() {
        var bus = new EventBus();
        var monitorSawCancel = new AtomicBoolean();
        bus.on(CancelableEvent.class, EventPriority.HIGHEST, event -> event.cancel(true));
        bus.on(CancelableEvent.class, EventPriority.MONITOR, event -> monitorSawCancel.set(event.canceled()));
        var event = new CancelableEvent();

        bus.post(event);

        assertTrue(event.canceled());
        assertTrue(monitorSawCancel.get());
    }

    @Test
    void survivesConcurrentRegistrationClosingAndPosting() throws Exception {
        var bus = new EventBus();
        var start = new CyclicBarrier(WORKERS + 1);
        try (var executor = Executors.newFixedThreadPool(WORKERS)) {
            var futures = new ArrayList<Future<?>>();
            for (var i = 0; i < WORKERS; i++) {
                futures.add(executor.submit(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    registerAndCloseHandlers(bus);
                    return null;
                }));
            }
            start.await(20, TimeUnit.SECONDS);
            for (var i = 0; i < EVENT_COUNT; i++) {
                bus.post(new PingEvent());
            }
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        var probeCalls = new AtomicInteger();
        bus.on(PingEvent.class, event -> probeCalls.incrementAndGet());
        bus.post(new PingEvent());

        assertEquals(1, probeCalls.get());
    }

    // Keeps a sliding window of open handlers so registration and close interleave within each worker.
    private static void registerAndCloseHandlers(EventBus bus) {
        var open = new ArrayDeque<IRegistration>();
        for (var index = 0; index < HANDLERS_PER_WORKER; index++) {
            var priority = EventPriority.values()[index % EventPriority.values().length];
            open.addLast(bus.on(PingEvent.class, priority, event -> { }));
            if (open.size() > OPEN_HANDLERS_PER_WORKER) {
                open.removeFirst().close();
            }
        }
        for (var registration : open) {
            registration.close();
        }
    }

    private static final class PingEvent implements IEvent {}

    private static final class PongEvent implements IEvent {}

    private static final class CancelableEvent implements ICancelableEvent {
        private boolean canceled;

        @Override
        public void cancel(boolean cancel) {
            this.canceled = cancel;
        }

        @Override
        public boolean canceled() {
            return canceled;
        }
    }
}
