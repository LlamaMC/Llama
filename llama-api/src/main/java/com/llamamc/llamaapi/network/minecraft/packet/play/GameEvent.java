package com.llamamc.llamaapi.network.minecraft.packet.play;

public enum GameEvent {
    NO_RESPAWN_BLOCK_AVAILABLE(0),
    START_RAINING(1),
    STOP_RAINING(2),
    CHANGE_GAME_MODE(3),
    WIN_GAME(4),
    DEMO_EVENT(5),
    PLAY_ARROW_HIT_SOUND(6),
    RAIN_LEVEL_CHANGE(7),
    THUNDER_LEVEL_CHANGE(8),
    PUFFER_FISH_STING(9),
    GUARDIAN_ELDER_EFFECT(10),
    IMMEDIATE_RESPAWN(11),
    LIMITED_CRAFTING(12),
    LEVEL_CHUNKS_LOAD_START(13);

    private final int protocolId;

    GameEvent(int protocolId) {
        this.protocolId = protocolId;
    }

    public int protocolId() {
        return protocolId;
    }

    public static GameEvent byProtocolId(int protocolId) {
        for (var event : values()) {
            if (event.protocolId == protocolId) {
                return event;
            }
        }
        throw new IllegalArgumentException("Unknown Minecraft game event: " + protocolId);
    }
}
