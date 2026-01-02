package dev.kastle.netty.channel.nethernet.signaling;

import java.net.SocketAddress;
import java.util.List;

public interface NetherNetServerSignaling extends NetherNetSignaling {
    /**
     * Binds the signaling medium to listen for incoming connections (Server mode).
     */
    void bind(SocketAddress localAddress);

    /**
     * Handler for new connections.
     * @param handler Functional interface receiving (ConnectionID, RemoteNetworkID, Payload)
     */
    void setNewConnectionHandler(NewConnectionHandler handler);

    /**
     * Sets the advertisement data for the discovery mechanism (e.g. LAN Pong).
     */
    void setAdvertisementData(PongData pongData);

    @FunctionalInterface
    interface NewConnectionHandler {
        void onConnect(long connectionId, String remoteNetworkId, String payload);
    }

    /**
     * Returns the ICE servers (STUN/TURN) obtained from the signaling handshake.
     * Returns empty list if none available or not applicable.
     */
    default List<IceServerInfo> getIceServers() {
        return java.util.Collections.emptyList();
    }

    /**
     * Data structure for Pong advertisement data.
     * 
     * @param serverName      The name of the server.
     * @param levelName       The name of the level/world.
     * @param gameType        The game type (e.g. Survival, Creative).
     * @param playerCount     The current number of players.
     * @param maxPlayerCount  The maximum number of players allowed.
     * @param isEditorWorld   Whether the world is an editor world.
     * @param isHardcore      Whether the world is in hardcore mode.
     * @param transportLayer  The transport layer identifier (e.g. NetherNet).
     * @param connectionType  The connection type identifier (e.g. LAN, Online).
     */
    public record PongData(String serverName, String levelName, int gameType, int playerCount, int maxPlayerCount, 
            boolean isEditorWorld, boolean isHardcore, int transportLayer, int connectionType) {
        public static class Builder {
            private String serverName = "Server";
            private String levelName = "World";
            private int gameType = 0; // Default to Survival
            private int playerCount = 0;
            private int maxPlayerCount = 10;
            private boolean isEditorWorld = false;
            private boolean isHardcore = false;
            private int transportLayer = 2; // Default to NetherNet
            private int connectionType = 4; // Default to LAN

            public Builder setServerName(String serverName) {
                this.serverName = serverName;
                return this;
            }

            public Builder setLevelName(String levelName) {
                this.levelName = levelName;
                return this;
            }

            public Builder setGameType(int gameType) {
                this.gameType = gameType;
                return this;
            }

            public Builder setPlayerCount(int playerCount) {
                this.playerCount = playerCount;
                return this;
            }

            public Builder setMaxPlayerCount(int maxPlayerCount) {
                this.maxPlayerCount = maxPlayerCount;
                return this;
            }

            public Builder setIsEditorWorld(boolean isEditorWorld) {
                this.isEditorWorld = isEditorWorld;
                return this;
            }

            public Builder setIsHardcore(boolean isHardcore) {
                this.isHardcore = isHardcore;
                return this;
            }

            public Builder setTransportLayer(int transportLayer) {
                this.transportLayer = transportLayer;
                return this;
            }

            public Builder setConnectionType(int connectionType) {
                this.connectionType = connectionType;
                return this;
            }

            public PongData build() {
                return new PongData(serverName, levelName, gameType, playerCount, maxPlayerCount, 
                    isEditorWorld, isHardcore, transportLayer, connectionType);
            }
        }
    }
}
