package cn.xianaldai.worldgenrender.network;

import cn.xianaldai.worldgenrender.client.ClientBoxScreenshotRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端请求执行命令的客户端用真实客户端画面保存一次范围渲染截图。 */
public record ClientBoxRenderRequestPacket(ResourceLocation dimension,
                                           BlockPos first,
                                           BlockPos second,
                                           float yaw,
                                           float pitch,
                                           double cameraX,
                                           double cameraY,
                                           double cameraZ,
                                           boolean playerPerspective,
                                           boolean cutaway,
                                           String orientation,
                                           String fileName)
{
    public static void encode(ClientBoxRenderRequestPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeResourceLocation(packet.dimension);
        buffer.writeBlockPos(packet.first);
        buffer.writeBlockPos(packet.second);
        buffer.writeFloat(packet.yaw);
        buffer.writeFloat(packet.pitch);
        buffer.writeDouble(packet.cameraX);
        buffer.writeDouble(packet.cameraY);
        buffer.writeDouble(packet.cameraZ);
        buffer.writeBoolean(packet.playerPerspective);
        buffer.writeBoolean(packet.cutaway);
        buffer.writeUtf(packet.orientation == null ? "" : packet.orientation, 16);
        buffer.writeUtf(packet.fileName == null ? "" : packet.fileName, 32767);
    }

    public static ClientBoxRenderRequestPacket decode(FriendlyByteBuf buffer)
    {
        return new ClientBoxRenderRequestPacket(
                buffer.readResourceLocation(),
                buffer.readBlockPos(),
                buffer.readBlockPos(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readUtf(16),
                buffer.readUtf(32767));
    }

    public static void handle(ClientBoxRenderRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientBoxScreenshotRenderer.handle(packet));
    }
}
