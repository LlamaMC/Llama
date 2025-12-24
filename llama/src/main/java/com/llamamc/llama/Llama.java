package com.llamamc.llama;

import com.llamamc.llama.config.LlamaConfig;
import com.llamamc.llama.config.PermissionsConfig;
import com.llamamc.llama.config.WorldsConfig;
import com.llamamc.llama.console.Console;
import com.llamamc.llama.event.EventBus;
import com.llamamc.llamaapi.ILlama;
import com.llamamc.llamaapi.ILlamaRuntime;
import com.llamamc.llamaapi.config.ConfigFactory;
import com.llamamc.llamaapi.console.IConsole;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.network.INetworkServer;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.WorldType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Llama implements ILlama {
    private final LlamaConfig llamaConfig;
    private final PermissionsConfig permissionsConfig;
    private final WorldsConfig worldsConfig;
    /*private final ILlamaRuntime runtime;
    private final IConsole console;*/
    private final IEventBus eventBus;

    public Llama() {
        this.llamaConfig = ConfigFactory.load(LlamaConfig.class);
        if (llamaConfig.getHost() == null) llamaConfig.setHost("127.0.0.1");
        if (llamaConfig.getPort() == 0) llamaConfig.setPort(25565);
        if (llamaConfig.getMotd() == null) llamaConfig.setMotd("A Llama server");
        if (llamaConfig.getMaxPlayers() == 0) llamaConfig.setMaxPlayers(10);
        if (!llamaConfig.isOnlineMode()) llamaConfig.setOnlineMode(false);
        this.permissionsConfig = ConfigFactory.load(PermissionsConfig.class);
        if (permissionsConfig.getUsers() == null) {
            Map<String, Map<String, List<String>>> users = new HashMap<>();
            Map<String, List<String>> user = new HashMap<>();
            user.put("permissions", List.of("llamamc.command.example"));
            user.put("groups", List.of("admin"));
            String zeroUUID = "00000000-0000-0000-0000-000000000000";
            users.put(zeroUUID, user);
            permissionsConfig.setUsers(users);
        }
        if (permissionsConfig.getGroups() == null) {
            Map<String, List<String>> groups = new HashMap<>();
            groups.put("admin", List.of(
                    "llamamc.command.example"
            ));
            permissionsConfig.setGroups(groups);
        }
        this.worldsConfig = ConfigFactory.load(WorldsConfig.class);
        if (this.worldsConfig.getWorlds() == null) {
            Map<String, WorldsConfig.ConfigWorld> worlds = new HashMap<>();
            worlds.put("main", new WorldsConfig.ConfigWorld("_llama", WorldType.NORMAL, DimensionType.OVERWORLD));
            worlds.put("building", new WorldsConfig.ConfigWorld("_minecraft", WorldType.FLAT, DimensionType.THE_END));
            this.worldsConfig.setWorlds(worlds);
        }
        ConfigFactory.save(llamaConfig);
        ConfigFactory.save(permissionsConfig);
        ConfigFactory.save(worldsConfig);
        /*this.runtime = new LlamaRuntime();
        this.runtime.start();
        this.console = new Console();
        this.console.start();*/
        this.eventBus = new EventBus();
    }

    @Override
    public INetworkServer server() {
        return null;
    }

    @Override
    public IEventBus eventBus() {
        return eventBus;
    }

    @Override
    public void shutdown() {
        /*console.exit();
        ((Thread) console).interrupt();
        runtime.shutdown();*/
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

    static void main() {
        ILlama llama = new Llama();
        Runtime.getRuntime().addShutdownHook(new Thread(llama::shutdown));
    }
}
