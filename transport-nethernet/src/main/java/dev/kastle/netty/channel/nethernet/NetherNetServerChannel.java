package dev.kastle.netty.channel.nethernet;

import dev.kastle.netty.channel.nethernet.config.NetherNetChannelConfig;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxSignaling;
import dev.kastle.webrtc.CreateSessionDescriptionObserver;
import dev.kastle.webrtc.PeerConnectionFactory;
import dev.kastle.webrtc.PeerConnectionObserver;
import dev.kastle.webrtc.RTCAnswerOptions;
import dev.kastle.webrtc.RTCBundlePolicy;
import dev.kastle.webrtc.RTCConfiguration;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCIceCandidate;
import dev.kastle.webrtc.RTCIceServer;
import dev.kastle.webrtc.RTCPeerConnection;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSdpType;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.SetSessionDescriptionObserver;
import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.EventLoop;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

public class NetherNetServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final NetherNetChannelConfig config = new NetherNetChannelConfig(this);
    private final PeerConnectionFactory factory;
    private final NetherNetServerSignaling signaling;
    
    private InetSocketAddress localAddress;

    public NetherNetServerChannel(NetherNetServerSignaling signaling) {
        this(new PeerConnectionFactory(), signaling);
    }

    public NetherNetServerChannel(PeerConnectionFactory factory, NetherNetServerSignaling signaling) {
        this.factory = factory;
        this.signaling = signaling;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (!(localAddress instanceof InetSocketAddress)) throw new IllegalArgumentException("Unsupported address type");
        this.localAddress = (InetSocketAddress) localAddress;
        
        this.signaling.setNewConnectionHandler((connectionId, remoteNetworkId, offerSdp) -> {
            acceptConnection(connectionId, offerSdp, remoteNetworkId);
        });

        this.signaling.bind(localAddress);
    }

    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId) {
        RTCConfiguration rtcConfig = new RTCConfiguration();
        rtcConfig.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;

        // Inject ICE servers if the signaling implementation supports it
        if (this.signaling instanceof NetherNetXboxSignaling xboxSignaling) {
            List<IceServerInfo> iceServers = xboxSignaling.getIceServers();
            for (IceServerInfo info : iceServers) {
                RTCIceServer iceServer = new RTCIceServer();
                iceServer.urls = info.urls;
                iceServer.username = info.username;
                iceServer.password = info.password;
                rtcConfig.iceServers.add(iceServer);
            }
        }

        ServerPeerConnectionObserver observer = new ServerPeerConnectionObserver(connectionId, remoteNetworkId);
        RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, observer);

        NetherNetChildChannel child = new NetherNetChildChannel(this, pc, new InetSocketAddress(0), localAddress);
        observer.setChildChannel(child);
        
        // Register Signal Handler
        signaling.setSignalHandler(connectionId, (signal) -> {
            String[] parts = signal.split(" ", 3);
            if (parts.length < 3) return;
            String type = parts[0];
            String data = parts[2];

            switch (type) {
                case NetherNetConstants.SIGNAL_CANDIDATE_ADD -> 
                    pc.addIceCandidate(new RTCIceCandidate("0", 0, data));
                case NetherNetConstants.SIGNAL_CONNECT_ERROR -> 
                    child.close();
            }
        });

        // Handle Offer
        pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, offerSdp), new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription description) {
                        pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                signaling.sendSignal(
                                    remoteNetworkId, 
                                    NetherNetConstants.buildSignalConnectResponse(connectionId, description.sdp)
                                );
                                pipeline().fireChannelRead(child);
                            }
                            @Override public void onFailure(String error) { log.error("SetLocalDesc failed: {}", error); }
                        });
                    }
                    @Override public void onFailure(String error) { log.error("CreateAnswer failed: {}", error); }
                });
            }
            @Override public void onFailure(String error) { log.error("SetRemoteDesc failed: {}", error); }
        });
    }

    /**
     * Observer to handle Data Channel creation from the client.
     */
    private class ServerPeerConnectionObserver implements PeerConnectionObserver {
        private final long connectionId;
        private final String remoteNetworkId;
        private NetherNetChildChannel child;
        
        private RTCDataChannel reliable;
        private RTCDataChannel unreliable;

        public ServerPeerConnectionObserver(long connectionId, String remoteNetworkId) {
            this.connectionId = connectionId;
            this.remoteNetworkId = remoteNetworkId;
        }

        public void setChildChannel(NetherNetChildChannel child) {
            this.child = child;
            checkDataChannels();
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            signaling.sendSignal(
                remoteNetworkId, 
                NetherNetConstants.buildSignalCandidateAdd(connectionId, candidate.sdp)
            );
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            log.debug("Connection {} state changed: {}", Long.toUnsignedString(this.connectionId), state);
        }

        @Override
        public void onDataChannel(RTCDataChannel dataChannel) {
            String label = dataChannel.getLabel();
            log.debug("Received Data Channel: {}", label);
            
            if (NetherNetConstants.RELIABLE_CHANNEL_LABEL.equals(label)) {
                this.reliable = dataChannel;
            } else if (NetherNetConstants.UNRELIABLE_CHANNEL_LABEL.equals(label)) {
                this.unreliable = dataChannel;
            }
            
            checkDataChannels();
        }
        
        private void checkDataChannels() {
            if (child != null && reliable != null && unreliable != null) {
                log.debug("Data Channels established for {}", Long.toUnsignedString(this.connectionId));
                child.setDataChannels(reliable, unreliable);
                
                if (child.pipeline() != null) {
                    child.pipeline().fireChannelActive();
                }
            }
        }
    }

    @Override
    protected void doClose() throws Exception {
        signaling.close();
        factory.dispose();
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Server channel doesn't read data directly
    }

    @Override
    protected SocketAddress localAddress0() {
        return this.localAddress;
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true; 
    }

    @Override
    public ChannelConfig config() { return config; }
    
    @Override 
    public boolean isOpen() { 
        return true;
    }
    
    @Override 
    public boolean isActive() { 
        return isOpen(); 
    }
    
    @Override 
    public ChannelMetadata metadata() { 
        return METADATA; 
    }
}