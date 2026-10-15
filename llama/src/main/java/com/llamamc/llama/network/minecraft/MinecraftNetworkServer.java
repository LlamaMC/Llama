package com.llamamc.llama.network.minecraft;

import com.llamamc.llama.registry.MinecraftRegistries;
import com.llamamc.llamaapi.network.INetworkServer;
import com.llamamc.llama.network.minecraft.protocol.MinecraftProtocolVersion;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import com.llamamc.llamaapi.world.IWorld;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public final class MinecraftNetworkServer implements INetworkServer {
    private static final int WORKER_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ServerStatus status;
    private final PacketRegistry registry;
    private final IWorld world;

    private EventLoopGroup acceptor;
    private EventLoopGroup workers;
    private Channel channel;

    public MinecraftNetworkServer(ServerStatus status) {
        this(status, MinecraftProtocolVersion.V26_3, null,
                new MinecraftRegistries(MinecraftProtocolVersion.V26_3));
    }

    public MinecraftNetworkServer(ServerStatus status, MinecraftProtocolVersion protocolVersion) {
        this(status, protocolVersion, null, new MinecraftRegistries(protocolVersion));
    }

    public MinecraftNetworkServer(ServerStatus status, MinecraftProtocolVersion protocolVersion, IWorld world) {
        this(status, protocolVersion, world, new MinecraftRegistries(protocolVersion));
    }

    public MinecraftNetworkServer(ServerStatus status, MinecraftProtocolVersion protocolVersion, IWorld world,
                                  MinecraftRegistries gameRegistries) {
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        if (protocolVersion == null) {
            throw new IllegalArgumentException("protocolVersion cannot be null");
        }

        this.status = status;
        this.world = world;
        registry = protocolVersion.createRegistry(gameRegistries);
    }

    @Override
    public synchronized void start(InetSocketAddress address) {
        if (address == null) {
            throw new IllegalArgumentException("address cannot be null");
        }
        if (isRunning()) {
            throw new IllegalStateException("Minecraft network server is already running");
        }

        acceptor = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(WORKER_THREADS);

        try {
            channel = new ServerBootstrap()
                    .group(acceptor, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socket) {
                            socket.pipeline()
                                    .addLast(new MinecraftFrameDecoder())
                                    .addLast(new MinecraftFrameEncoder())
                                    .addLast(new MinecraftConnectionHandler(status, registry, world));
                        }
                    })
                    .bind(address)
                    .syncUninterruptibly()
                    .channel();
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        if (acceptor != null) {
            acceptor.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncUninterruptibly();
            acceptor = null;
        }
        if (workers != null) {
            workers.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncUninterruptibly();
            workers = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return channel != null && channel.isOpen();
    }

    @Override
    public synchronized InetSocketAddress address() {
        if (channel == null) {
            throw new IllegalStateException("Minecraft network server is not running");
        }

        return (InetSocketAddress) channel.localAddress();
    }
}
