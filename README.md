# Llama

Llama is a Minecraft server runtime written in Java 25. It is built to show how to write a fast, thread-safe server. The tick loop runs 20 times per second on its own thread, and chunk generation and storage run on separate worker pools so that slow work does not stall the tick.

The project has two Maven modules:

- **`llama-api`** (package `com.llamamc.llamaapi`) is the plugin-facing API: the interfaces a plugin codes against, and the value types they use.
- **`llama`** (package `com.llamamc.llama`) is the runtime. It implements the API and contains the server, network, world and configuration code.

## Status

`docs/API.md` describes the API as it stands today. Section 9 lists what is defined but not implemented yet:

- Player registry and an `IPlayer` implementation. `ILlama` has no player accessor.
- Posting player and chat events. The event types exist, but nothing posts them.
- Plugin loader and dependency injection. Nothing loads a plugin.
- Network interceptors and listeners. The types exist, but the runtime does not use them.
- World writes. `IWorld` and `IChunk` have no block write method.
- Automatic chunk eviction. A chunk stays loaded until a plugin calls `unloadChunk`.
- Direct palettes. A section with too many block states fails to encode.
- Running queued tasks when the scheduler closes. They are failed or cancelled instead.
- Enforcing the rule against calling `join()` on the tick thread.

Sections 4 to 8 of the guide describe what is in place: the event bus, the scheduler, chunk loading and reads, registries, configuration files, and the command registry.

## Build and test

Java 25 is required, as set by `java.version` in the root `pom.xml`. From the repository root:

```
mvn -B test
```

`mvn -B package` builds the server jar. `llama/pom.xml` sets the final name to `llama` and adds the shade plugin.

## Run

`scripts/start.sh` starts the server and starts it again whenever it exits with code 75. Its settings come from environment variables, with these defaults:

- `LLAMA_JAR`: path to the server jar (`llama.jar` next to the script).
- `LLAMA_MEMORY`: heap size for `-Xms` and `-Xmx` (`2G`).
- `JAVA`: the java executable (`java`).
- `JAVA_FLAGS`: extra JVM flags (`-XX:+UseZGC -XX:+UseCompactObjectHeaders`).

The script changes into its own directory first, so the configuration files, `worlds/`, `logs/latest.log` and `crash-reports/` are all created there.

Exit codes, from `ExitCode` and `Main`:

| Code | Meaning |
|---|---|
| 0 | Normal stop: the console `stop` command, Ctrl+C in the console, `ILlama.shutdown()`, or JVM shutdown. |
| 1 | Error, including a failed start. |
| 75 | Restart requested: the console `restart` command, an out-of-memory error, or the watchdog. |

A restart request writes a crash report to `crash-reports/`. If the server asks for a fourth restart within 300 seconds, `start.sh` stops and exits with code 1.

## Configuration

On start, Llama creates any missing configuration file in the working directory, then saves all three back. The files are:

**`llama.properties`** is the server config (`LlamaConfig`).

- `host`: address to listen on (default `127.0.0.1`).
- `port`: port to listen on (default `25565`).
- `motd`: message of the day (default `A Llama server`).
- `max-players`: player limit (default `10`).
- `minecraft-version`: the Minecraft version the runtime targets (default `26.3`).
- `online-mode`: a boolean. It is read and saved, but nothing in the runtime uses it yet.

**`permissions.toml`** holds permissions (`PermissionsConfig`).

- `[groups]` maps a group name to a list of permission names. The default is `admin = ["llamamc.command.example"]`.
- `[users.<uuid>]` lists the `permissions` and `groups` of one user. The default is one user, `00000000-0000-0000-0000-000000000000`, in the `admin` group.
- The `permission` value on `@Command` is not enforced yet (`docs/API.md` section 8).

**`worlds.toml`** defines worlds (`WorldsConfig`). Each `[worlds.<name>]` table has:

- `type`: a `WorldType` name, such as `NORMAL` or `FLAT`.
- `dimension`: a `DimensionType` name: `NETHER`, `OVERWORLD`, `THE_END` or `VOID`.
- `seed`: a string (default `0`).
- `format`: loaded from the file, but the runtime does not read it yet. Chunks are always stored with the Vicuna storage, under `worlds/<name>/`.

The defaults are `main` (`NORMAL`, `OVERWORLD`) and `building` (`FLAT`, `THE_END`). `mainWorld()` is `main` when it is configured, and otherwise the first world (`docs/API.md` section 6).

## Documentation

- `docs/API.md`: the guide to the `llama-api` module.
- `PURELINE_SPECIFICATION_1.4.md`: the code style the project follows.

## License

The project is licensed under the GNU General Public License. See the `LICENSE` file.
