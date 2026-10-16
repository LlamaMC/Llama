# Llama API

This guide covers the `llama-api` module, the interfaces a plugin codes against. It describes the code as it stands today. Anything that is defined but does not work yet is listed in section 9.

## 1. What Llama is

Llama is a Minecraft server runtime written in Java 25, built to show how to write a fast, thread-safe server. Its tick loop runs 20 times per second on the tick thread, and chunk generation and storage run on separate worker pools so that slow work does not stall the tick. Plugins code against the interfaces in `llama-api` (package `com.llamamc.llamaapi`), and the classes in `llama` implement them.

## 2. Quick start

The runtime gives the plugin an `ILlama`. This guide does not describe how that happens, because there is no plugin loader yet.

```java
import com.llamamc.llamaapi.ILlama;
import com.llamamc.llamaapi.event.impl.player.PlayerJoinEvent;

public final class WelcomePlugin {

    public WelcomePlugin(ILlama llama) {
        llama.events().on(PlayerJoinEvent.class, event ->
                llama.scheduler().runOnTick(() -> System.out.println("Welcome, " + event.player())));

        llama.worlds().mainWorld().chunk(0, 0).whenComplete((chunk, error) -> {
            if (error != null) {
                System.err.println("Chunk 0,0 failed: " + error.getMessage());
                return;
            }
            llama.scheduler().runOnTick(() ->
                    System.out.println("Highest block at 0,0: " + chunk.highestBlockY(0, 0)));
        });
    }
}
```

Two points about this example. The runtime does not post `PlayerJoinEvent` yet (section 9), so that handler never runs. The chunk callback runs on the thread that finishes the load, which is a world worker thread for a new load, so it uses `runOnTick` to get back to the tick thread.

## 3. Layers

**High-level facade.** Most plugins use `events()` (`IEventBus`), `scheduler()` (`IScheduler`), `worlds()` (`IWorldManager`) and `registries()` (`IRegistries`), together with the value types `Component` and `NamespacedKey`. Several members are thin wrappers over others. In the runtime, `runOnTick` is `callOnTick` with the `Runnable` wrapped as a `Callable`. The `String` overloads of `IRegistry.find` and `require` parse the key and call the `NamespacedKey` overloads. `IWorld.peekBlock` is a default method over `loadedChunk`, and `Component.replace(String, Component)` calls the `String` overload.

**Low-level access.** Reads on `IChunk` (`block`, `highestBlockY`) return values directly, with no future and no scheduling. `BlockState` is an immutable record of an identifier and its properties. `ILlama.server()` is the only handle to the network, and `INetworkServer` exposes just `start`, `close`, `isRunning` and `address`. Packet interceptors and listeners exist as types in `llama-api`, but the runtime does not use them yet (section 9).

The accessors on `ILlama` other than `events()` are set during `Llama.start()`, so they return `null` until start has run.

## 4. Events

Subscribe with `on`, which returns an `IRegistration` that you can close later. The samples in this guide assume an `ILlama llama` in scope.

```java
import com.llamamc.llamaapi.event.EventPriority;
import com.llamamc.llamaapi.event.IRegistration;
import com.llamamc.llamaapi.event.impl.chat.ChatEvent;

IRegistration filter = llama.events().on(ChatEvent.class, EventPriority.HIGH, event -> {
    if (event.message().text().contains("spam")) {
        event.cancel(true);
    }
});

filter.close(); // later: removes the handler
```

- `on(type, handler)` uses `EventPriority.NORMAL`. `on(type, priority, handler)` sets the priority.
- Handlers run in ascending priority order: `LOWEST`, `LOW`, `NORMAL`, `HIGH`, `HIGHEST`, `MONITOR`. Handlers with the same priority run in registration order. The enum sets only the order. Nothing stops a `MONITOR` handler from changing the event.
- A handler registered for a class also receives its subclasses, because the bus matches with `isInstance`.
- An event is any type that implements `IEvent`, an empty marker interface. Any code can `post` an event. `ChatEvent` shows the pattern: it is an `ICancelableEvent` that exposes `player()`, `message()` and `setMessage(...)`.
- `post(event)` is synchronous. It runs every matching handler on the calling thread and returns once they have all run. A slow handler delays the poster.
- `ICancelableEvent` has `cancel(boolean)` and `canceled()`. Cancelling only sets a flag. `post` does not skip handlers after an event is canceled, so a handler that must respect cancellation checks `canceled()` itself.
- A `RuntimeException` thrown by a handler is logged with the event class name, and dispatch goes on to the next handler. An `Error` is not caught. It propagates to the poster, and the handlers after it do not run.
- `IRegistration.close()` removes the handler and can be called more than once. After `close()` returns, the handler is not started again, but a `post` already running on another thread may still call it once. `IRegistration` extends `AutoCloseable`, so try-with-resources works.
- `on` and `post` throw `IllegalArgumentException` for a null argument.

A registration that should last only for one block can be scoped with try-with-resources:

```java
import com.llamamc.llamaapi.event.impl.chat.ChatEvent;

try (var scoped = llama.events().on(ChatEvent.class, event -> System.out.println(event.message().text()))) {
    // the handler is active while this block runs
}
```

## 5. Scheduler

```java
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.scheduler.ITaskHandle;

var scheduler = llama.scheduler();
scheduler.runOnTick(() -> System.out.println("on the tick thread, never inline"));
LlamaFuture<String> label = scheduler.callOnTick(() -> "ready");
ITaskHandle timer = scheduler.runLaterTicks(40, () -> System.out.println("about two seconds later"));
LlamaFuture<Integer> answer = scheduler.supplyAsync(() -> 6 * 7);
```

- **Nothing runs inline.** `runOnTick` and `callOnTick` queue the task on the tick loop. It runs at the start of a later tick, even when the caller is the tick thread.
- **Timing.** The tick loop runs one tick every 50 ms while it keeps up. `runLaterTicks` counts ticks, not milliseconds, so 40 ticks is about two seconds only while the server keeps pace.
- **`runLaterTicks`** runs the task after `ticks` more ticks have completed. Zero or negative values behave like `runOnTick`. The returned `ITaskHandle` can cancel the task from any thread, and a second `cancel()` does nothing. The task has no result, so an exception from it is logged, not returned.
- **Errors go into the future** for `runOnTick`, `callOnTick` and `supplyAsync`. `join()` throws `LlamaPromise.LlamaAsyncException`, with the cause attached, for a failed future. It throws `LlamaPromise.LlamaCancelledException` for a cancelled one. `await()` throws the same exceptions and also `InterruptedException`.
- **Do not `join()` from the tick thread.** The task behind a future from `runOnTick` or `callOnTick` can only run on the tick thread. Joining that future there blocks the thread the task needs, so the wait never ends. Nothing enforces this rule (section 9). Use `whenComplete` instead.
- **`supplyAsync`** runs its supplier on a virtual thread. Use it for blocking work, then hand the result back with `runOnTick`.
- **Callbacks.** `whenComplete` runs its callback on the thread that completes the future, or on the caller if the future is already done. `thenApply` and `thenCompose` run on the completing thread. `thenApplyAsync` and `thenComposeAsync` take an `Executor` when you need a particular thread. A callback that throws is handed to that thread's uncaught-exception handler, and the other callbacks still run.
- **`isTickThread()`** is true only on the current tick thread.

A common pattern is to compute off the tick thread and apply the result on it:

```java
var scheduler = llama.scheduler();
scheduler.supplyAsync(() -> 6 * 7).whenComplete((answer, error) -> {
    if (error == null) {
        scheduler.runOnTick(() -> System.out.println("answer " + answer));
    }
});
```

After shutdown, Llama stops the tick loop first and then closes the scheduler. From then on:

- `runOnTick` and `callOnTick` return a future that has already failed with `IllegalStateException` ("Tick loop is not running").
- `runLaterTicks` returns a handle that is already cancelled.
- Futures still waiting for a tick fail with the same exception. Queued tasks are not run.
- `supplyAsync` still runs its supplier. It does not check whether the scheduler is closed.

## 6. Worlds and chunks

```java
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.IWorld;
import java.util.Optional;

IWorld world = llama.worlds().mainWorld();
world.loadedChunk(0, 0).ifPresent(chunk ->
        System.out.println(chunk.block(0, chunk.minY(), 0).identifier()));
Optional<BlockState> block = world.peekBlock(8, 64, 8);
```

- `mainWorld()` is the world named `main` if one is configured, and otherwise the first configured world. `world(name)` returns an `Optional`. `worlds()` returns a copy of the list.
- **`chunk(x, z)`** returns a future and never blocks. Calls for the same chunk share one load, and a chunk that is already loaded gives back a completed future. The future fails with `IllegalStateException` if the world is closed or if 4096 loads are already pending. A failed load is forgotten, so the next call starts a new one. Once a chunk is loaded, every caller receives the same `IChunk` object.
- **`loadedChunk(x, z)`** returns the chunk only if it is already loaded. It never blocks and never starts a load. It does not check whether the world is closed, so chunks loaded before `close()` can still be returned.
- **`chunkNow(x, z)`** blocks until the chunk is loaded. It throws `IllegalStateException` on the tick thread and on a world worker thread, because a worker waiting on another queued load can starve the pool. Use `chunk()` there instead. A virtual thread, such as one started by `supplyAsync`, may call it.
- **`peekBlock(x, y, z)`** takes world coordinates. It returns `Optional.empty()` if the chunk is not loaded or if `y` is outside `[minY, minY + height)`. It never starts a load.
- After `close()`, `chunk()` fails with `IllegalStateException`.
- **`IChunk`** has `WIDTH = 16`. `position()` returns a `ChunkPosition`, and `minY()` and `height()` give the vertical range. `block(localX, blockY, localZ)` takes chunk-local X and Z and an absolute Y in `[minY, minY + height)`. `highestBlockY(localX, localZ)` takes chunk-local X and Z. Out-of-range arguments throw `IndexOutOfBoundsException`. Chunk reads are safe from any thread and do not change the chunk.
- **`BlockState`** is a record of a `NamespacedKey` and a map of properties, copied on construction. `new BlockState("minecraft:stone")` and `BlockState.AIR` are the usual starting points. `property(name)` returns `null` for a missing property. `with(name, value)` returns a new state.
- **`ChunkPosition`** is a record of chunk X and Z with region helpers (`regionX`, `regionZ`, `localX`, `localZ`). A region is 32 chunks wide.

From a thread that is neither the tick thread nor a world worker, `chunkNow` is the simplest option:

```java
llama.scheduler().supplyAsync(() -> llama.worlds().mainWorld().chunkNow(0, 0))
        .whenComplete((chunk, error) -> System.out.println("loaded: " + (error == null)));
```

## 7. Threading contract

| API | Caller thread | Blocks | Notes |
|---|---|---|---|
| `IEventBus.on`, `IRegistration.close` | any | no | Registration takes a short lock. Dispatch reads a copy-on-write list. |
| `IEventBus.post` | any | while handlers run | Handlers run on the posting thread. A slow handler delays the poster. |
| `IScheduler.runOnTick`, `callOnTick` | any, including the tick thread | no | The task runs on the tick thread at the start of a later tick. |
| `IScheduler.runLaterTicks` | any | no | `ITaskHandle.cancel()` is safe from any thread. |
| `IScheduler.supplyAsync` | any | no | The supplier runs on a virtual thread. |
| `LlamaFuture.join()`, `await()` | not the tick thread | yes | Deadlocks on tick-thread work. Not enforced. |
| `LlamaFuture.whenComplete`, `thenApply`, `thenCompose` | any | no | Run on the completing thread, or on the caller if already done. |
| `IWorld.loadedChunk` | any | no | Reads committed state only, and never starts a load. |
| `IWorld.chunk` | any | no | Starts a load if one is needed. |
| `IWorld.chunkNow` | not the tick thread, not a world worker | yes | Throws `IllegalStateException` on both. The tick check depends on the runtime's predicate. |
| `IWorld.peekBlock` | any | no | Never starts a load. |
| `IChunk` reads | any | no | Do not change the chunk. |
| `IWorldManager` | any | no | Fixed at startup. `worlds()` returns a copy. |
| `IRegistry` lookups | any | no | The runtime's registries are immutable. |
| `ILlama.shutdown()` | any | no | Only requests a stop. It returns before the server has stopped. |

What is not enforced:

- Nothing stops a plugin from calling `join()` on the tick thread.
- Nothing checks which thread calls the scheduler or `post`.
- The tick-thread check in `chunkNow` works only when the world is given a tick-thread predicate. Llama gives one (see section 10).
- The runtime posts no events yet.

Rules of thumb:

- Keep work that changes server state on the tick thread, and reach it with `runOnTick` or `runLaterTicks`.
- Load and read chunks with `chunk()` and `whenComplete`, which never block. Hand the result back to the tick thread with `runOnTick`.
- Use `loadedChunk` or `peekBlock` when a missing chunk is an acceptable answer, since neither starts a load.

## 8. Components, registries and NamespacedKey

```java
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.registry.NamespacedKey;
import com.llamamc.llamaapi.registry.minecraft.Blocks;
import java.util.Optional;

Component line = Component.of("Hello <name>, you have <count> items")
        .replace("<name>", "Steve")
        .replace("<count>", "3")
        .append(Component.of("!"));

Optional<NamespacedKey> key = NamespacedKey.tryParse("stone");
var stone = llama.registries().blocks().find(Blocks.STONE);
```

**Component** is an immutable record with one `text` field. `of`, `append` and `replace` return new components.

- `replace(literal, value)` replaces every occurrence of `literal` as plain text. It is not a regex, so `$` and `\` in the value are inserted as written. The literal must not be empty, and the value must not be null.
- `of` and `append` reject a null argument with `IllegalArgumentException`.

**NamespacedKey** is a record of a namespace and a value. Both parts are lowercased.

- Namespaces may use `a-z`, `0-9`, `_`, `.` and `-`. Values may also use `/`. An invalid part throws `IllegalArgumentException`.
- `parse` throws `IllegalArgumentException` for a blank or invalid key. A key without a colon gets the `minecraft` namespace.
- `tryParse` returns `Optional.empty()` for the same inputs. Use it for user input.
- `minecraft(value)` is shorthand for a key in the `minecraft` namespace.

**Registries.** `llama.registries()` returns an `IRegistries` with `blocks()` and `items()`. Each is an `IRegistry`, and its entries implement `IRegistryEntry`, which has `key()`.

- `find(NamespacedKey)` returns an `Optional`. `require(NamespacedKey)` throws `NoSuchElementException` for an unknown key.
- `find(String)` never throws. Invalid text gives `Optional.empty()`. `require(String)` throws `IllegalArgumentException` for invalid text and `NoSuchElementException` for an unknown key.
- `contains` and `size` are default methods.

```java
import com.llamamc.llamaapi.block.IBlockType;
import com.llamamc.llamaapi.registry.IRegistry;
import com.llamamc.llamaapi.registry.NamespacedKey;
import com.llamamc.llamaapi.registry.minecraft.Blocks;

IRegistry<IBlockType> blocks = llama.registries().blocks();
IBlockType stone = blocks.require(Blocks.STONE);
boolean known = blocks.contains(NamespacedKey.parse("minecraft:stone"));
int count = blocks.size();
```

### Config

A config class is annotated with `@Config`, and `ConfigFactory` loads and saves it. A field can carry a `@Key` with a dotted path. Without one, the property name is the key.

```java
import com.llamamc.llamaapi.config.Config;
import com.llamamc.llamaapi.config.ConfigFactory;
import com.llamamc.llamaapi.config.ConfigType;
import com.llamamc.llamaapi.config.Key;

@Config(name = "homes", type = ConfigType.TOML)
public final class HomesConfig {

    @Key("limits.max")
    private int maxHomes = 3;

    private String greeting = "Hello";

    public int getMaxHomes() { return maxHomes; }
    public void setMaxHomes(int maxHomes) { this.maxHomes = maxHomes; }

    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }
}

var config = ConfigFactory.load(HomesConfig.class);
config.setGreeting("Hi");
ConfigFactory.save(config);
```

- `load` creates the file, and its directory, if they are missing. The file name is `name` plus the extension of the `ConfigType`: `.json`, `.yml`, `.toml` or `.properties`. `path` sets the directory and defaults to the working directory. The class needs a no-argument constructor.
- Loading calls each public one-argument `set` method with the value at its key. A missing key keeps the field's default. Supported value types are `String`, `int`, `boolean`, `List`, `Set` and `Map`. A value of any other type fails the load when its key is present.
- `save` writes every public zero-argument `get` method that has a field of the same name. `is` getters are ignored, so a boolean field needs a `get` method too.
- A failure in `load` or `save` throws a `RuntimeException`.

## 9. Not yet implemented

- **Player registry and `IPlayer` implementation.** `IPlayer` has no implementing class, and `ILlama` has no player accessor.
- **Posting player and chat events.** `PlayerJoinEvent`, `PlayerLeaveEvent`, `PlayerConfigureEvent` and `ChatEvent` are defined, but nothing in the runtime posts them, so their handlers never run.
- **Command dispatcher.** `ICommand`, `ICommandContext` and `Argument` exist, and the example package implements them. Nothing registers or runs commands, and `ILlama` has no command accessor.
- **Plugin loader and dependency injection.** `IPlugin`, `IPluginContext` and `@Inject` exist. Nothing loads a plugin, and nothing injects a field outside the example package.
- **Network interceptors and listeners.** `InterceptorRegistry`, `InboundInterceptor` and `INetworkListener` exist in `llama-api`. The runtime does not use them, and `INetworkServer` does not expose them.
- **World writes.** `IWorld` and `IChunk` have no `setBlock` or other write method.
- **Chunk eviction.** Loaded chunks stay in memory. Nothing unloads them.
- **Direct palettes.** A section with more block states than the indirect palette allows fails to encode, with the message "direct palettes are not implemented".
- **Repeating tasks.** `IScheduler` has only one-shot methods. A task that must repeat can reschedule itself with `runLaterTicks`.
- **Draining queued tasks at scheduler close.** Queued tasks are failed or cancelled when the scheduler closes. They are not run.
- **Enforcing the join rule.** `LlamaFuture.join()` has no tick-thread check.

## 10. Known limitations

- **Close during a post.** `IRegistration.close()` called while a `post` runs on another thread may still let the handler run once.
- **Event cancellation is advisory.** `post` never skips handlers, so a handler must check `canceled()` itself (section 4).
- **Shutdown timeouts.** `ILlama.shutdown()` only requests a stop. The runtime gives the tick thread 10 seconds to stop, or 1 second if the current tick has been running for more than 5 seconds. If the tick thread does not stop in time, shutdown continues and logs an error. If the whole shutdown has not finished 60 seconds after the request, the runtime halts the JVM, and any work still running is cut off.
- **Chunk request racing close.** `chunk()` checks whether the world is closed before it starts a load. A request that passes that check just as `close()` runs can, in a narrow window, start a load on a worker pool that is still open.
- **Hung ticks.** If a tick runs past the watchdog's takeover threshold, the watchdog abandons that tick thread and continues on a fresh one, provided the server can still recover. Otherwise it asks for a restart. An abandoned thread keeps running until it returns, and then it is stopped the next time it enters server code. A plugin handler that blocks the tick can therefore trigger either outcome.
- **Tick guard on `chunkNow`.** The tick-thread check depends on the predicate the world is built with. `LlamaWorld` and `WorldManager` have public constructors that pass `() -> false`, so worlds built that way have no tick-thread check. The runtime passes the real predicate.
