package cn.xianaldai.worldgenrender.util;

import cn.xianaldai.worldgenrender.WorldGenRender;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 用铁粒右键方块为每个玩家记录 WorldGenRender 空间盒选区的两个角点，并用蓝色粒子线框显示选区。 */
@Mod.EventBusSubscriber(modid = WorldGenRender.MODID)
public final class WorldGenSelectionManager
{
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
    private static final DustParticleOptions BLUE_LINE = new DustParticleOptions(new Vector3f(0.05F, 0.35F, 1.0F), 1.0F);

    private WorldGenSelectionManager() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!player.getMainHandItem().is(Items.IRON_NUGGET)) return;

        BlockPos pos = event.getPos().immutable();
        Selection selection = SELECTIONS.get(player.getUUID());
        ResourceKey<Level> dimension = level.dimension();

        if (player.isShiftKeyDown())
        {
            SELECTIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("WorldGenRender 选区已清除。"));
            event.setCanceled(true);
            return;
        }

        if (selection == null || selection.isComplete() || selection.dimension() != dimension)
        {
            SELECTIONS.put(player.getUUID(), new Selection(dimension, pos, null));
            player.sendSystemMessage(Component.literal("WorldGenRender 选区点 1 已设置为 " + format(pos) + "。再次用铁粒右键方块设置点 2；潜行右键可清除选区。"));
        }
        else
        {
            Selection updated = new Selection(dimension, selection.first(), pos);
            SELECTIONS.put(player.getUUID(), updated);
            player.sendSystemMessage(Component.literal("WorldGenRender 选区点 2 已设置为 " + format(pos) + "。当前选区 " + format(updated.first()) + " -> " + format(updated.second()) + "。使用 /worldgenrender render3d box 渲染；PNG 会使用固定正交捕获画布，蓝色边线不会进入图片。"));
        }

        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (player.tickCount % 10 != 0) return;

        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null || !selection.isComplete() || selection.dimension() != level.dimension()) return;
        drawSelectionBox(level, selection);
    }

    public static Selection selection(ServerPlayer player)
    {
        return SELECTIONS.get(player.getUUID());
    }

    public static void clear(ServerPlayer player)
    {
        SELECTIONS.remove(player.getUUID());
    }

    private static void drawSelectionBox(ServerLevel level, Selection selection)
    {
        int minX = Math.min(selection.first().getX(), selection.second().getX());
        int minY = Math.min(selection.first().getY(), selection.second().getY());
        int minZ = Math.min(selection.first().getZ(), selection.second().getZ());
        int maxX = Math.max(selection.first().getX(), selection.second().getX()) + 1;
        int maxY = Math.max(selection.first().getY(), selection.second().getY()) + 1;
        int maxZ = Math.max(selection.first().getZ(), selection.second().getZ()) + 1;

        drawLine(level, minX, minY, minZ, maxX, minY, minZ);
        drawLine(level, minX, minY, maxZ, maxX, minY, maxZ);
        drawLine(level, minX, maxY, minZ, maxX, maxY, minZ);
        drawLine(level, minX, maxY, maxZ, maxX, maxY, maxZ);

        drawLine(level, minX, minY, minZ, minX, minY, maxZ);
        drawLine(level, maxX, minY, minZ, maxX, minY, maxZ);
        drawLine(level, minX, maxY, minZ, minX, maxY, maxZ);
        drawLine(level, maxX, maxY, minZ, maxX, maxY, maxZ);

        drawLine(level, minX, minY, minZ, minX, maxY, minZ);
        drawLine(level, maxX, minY, minZ, maxX, maxY, minZ);
        drawLine(level, minX, minY, maxZ, minX, maxY, maxZ);
        drawLine(level, maxX, minY, maxZ, maxX, maxY, maxZ);
    }

    private static void drawLine(ServerLevel level, double x1, double y1, double z1, double x2, double y2, double z2)
    {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        int steps = Math.max(1, (int)Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 2.0D));
        for (int i = 0; i <= steps; i++)
        {
            double t = i / (double)steps;
            level.sendParticles(BLUE_LINE, x1 + dx * t, y1 + dy * t, z1 + dz * t, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static String format(BlockPos pos)
    {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public record Selection(ResourceKey<Level> dimension, BlockPos first, BlockPos second)
    {
        public boolean isComplete()
        {
            return first != null && second != null;
        }
    }
}
