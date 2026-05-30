package cn.xianaldai.worldgenrender.task.worldgen;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.AbstractWorldGenTask;
import cn.xianaldai.worldgenrender.task.ChunkRange;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** 按区块强制请求 FULL chunk，用于触发缺失区块重新生成/预加载。 */
public final class WorldGenPreloadTask extends AbstractWorldGenTask
{
    private int currentX;
    private int currentZ;

    public WorldGenPreloadTask(CommandSourceStack source, ServerLevel level, ChunkRange range)
    {
        super(source, level, range);
        this.currentX = range.minChunkX();
        this.currentZ = range.minChunkZ();
    }

    @Override public String name() { return "preload"; }

    @Override
    public void tick(MinecraftServer server)
    {
        int budget = Config.maxChunksPerTick;
        while (!cancelled && budget-- > 0 && currentZ <= range.maxChunkZ())
        {
            level.getChunk(currentX, currentZ);
            processedChunks++;
            advance();
        }
        if (!cancelled && currentZ > range.maxChunkZ())
        {
            done = true;
            source.sendSuccess(() -> Component.literal("preload 完成：已请求/生成 " + processedChunks + " 个区块。"), true);
        }
    }

    private void advance()
    {
        currentX++;
        if (currentX > range.maxChunkX()) { currentX = range.minChunkX(); currentZ++; }
    }
}