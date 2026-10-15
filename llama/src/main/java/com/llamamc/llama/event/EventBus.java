package com.llamamc.llama.event;

import com.llamamc.llamaapi.event.EventPriority;
import com.llamamc.llamaapi.event.IEvent;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.event.IRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class EventBus implements IEventBus {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);
    private static final Comparator<Registration<?>> DISPATCH_ORDER = Comparator
            .<Registration<?>>comparingInt(registration -> registration.priority.ordinal())
            .thenComparingLong(registration -> registration.sequence);

    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    // Replaced on every change and never mutated, so post can iterate the list it read without locking.
    private volatile List<Registration<?>> registrations = List.of();

    @Override
    public <E extends IEvent> IRegistration on(Class<E> type, Consumer<? super E> handler) {
        return on(type, EventPriority.NORMAL, handler);
    }

    @Override
    public <E extends IEvent> IRegistration on(Class<E> type, EventPriority priority, Consumer<? super E> handler) {
        if (type == null) {
            throw new IllegalArgumentException("Event type must not be null");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Event priority must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Event handler must not be null");
        }
        var registration = new Registration<>(this, type, priority, handler, sequence.incrementAndGet());
        synchronized (lock) {
            var next = new ArrayList<>(registrations);
            next.add(registration);
            next.sort(DISPATCH_ORDER);
            registrations = List.copyOf(next);
        }
        return registration;
    }

    @Override
    public void post(IEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        for (var registration : registrations) {
            registration.dispatch(event);
        }
    }

    private void remove(Registration<?> registration) {
        synchronized (lock) {
            var next = new ArrayList<>(registrations);
            next.remove(registration);
            registrations = List.copyOf(next);
        }
    }

    private static final class Registration<E> implements IRegistration {
        private final EventBus bus;
        private final Class<E> type;
        private final EventPriority priority;
        private final Consumer<? super E> handler;
        private final long sequence;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(EventBus bus, Class<E> type, EventPriority priority, Consumer<? super E> handler,
                             long sequence) {
            this.bus = bus;
            this.type = type;
            this.priority = priority;
            this.handler = handler;
            this.sequence = sequence;
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            bus.remove(this);
        }

        private void dispatch(IEvent event) {
            if (!active.get() || !type.isInstance(event)) {
                return;
            }
            try {
                handler.accept(type.cast(event));
            } catch (RuntimeException exception) {
                LOGGER.error("Event handler failed for {}", event.getClass().getName(), exception);
            }
        }
    }
}
