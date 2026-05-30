package cn.xianaldai.worldgenrender.network;

import cn.xianaldai.worldgenrender.WorldGenRender;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** 注册 WorldGenRender 的网络通道与数据包。 */
public final class WorldGenRenderNetwork
{
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(WorldGenRender.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextPacketId;

    private WorldGenRenderNetwork() {}

    public static void register()
    {
        CHANNEL.messageBuilder(ClientBoxRenderRequestPacket.class, nextPacketId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClientBoxRenderRequestPacket::encode)
                .decoder(ClientBoxRenderRequestPacket::decode)
                .consumerMainThread(ClientBoxRenderRequestPacket::handle)
                .add();
    }
}
