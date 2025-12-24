package com.llamamc.llama.config;

import com.llamamc.llamaapi.config.Config;
import com.llamamc.llamaapi.config.ConfigType;
import com.llamamc.llamaapi.config.Key;

@Config(name = "llama", type = ConfigType.PROPERTIES)
public class LlamaConfig {

    @Key("host")
    private String host;
    @Key("port")
    private int port;
    @Key("motd")
    private String motd;
    @Key("max-players")
    private int maxPlayers;
    @Key("online-mode")
    private boolean onlineMode;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getMotd() {
        return motd;
    }

    public void setMotd(String motd) {
        this.motd = motd;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public boolean isOnlineMode() {
        return onlineMode;
    }

    public void setOnlineMode(boolean onlineMode) {
        this.onlineMode = onlineMode;
    }
}
