package cn.xianaldai.worldgenrender.task.worldgen;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.AbstractWorldGenTask;
import cn.xianaldai.worldgenrender.task.ChunkRange;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 将指定区块范围内的方块逐步替换为空气。 */
public final class WorldGenClearTask extends AbstractWorldGenTask
{
    private final boolean saveAfter;
    private int chunkX;
    private int chunkZ;
    private int localX;
    private int localZ;
    private int y;
    private int changedBlocks;

    public WorldGenClearTask(CommandSourceStack source, ServerLevel level, ChunkRange range, boolean saveAfter)
    {
        super(source, level, range);
        this.saveAfter = saveAfter;
        this.chunkX = range.minChunkX();
        this.chunkZ = range.minChunkZ();
        this.y = level.getMinBuildHeight();
    }

    @Override public String name() { return "clear"; }

    @Override
    public void tick(MinecraftServer server)
    {
        int budget = Config.maxBlockChangesPerTick;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();

        while (!cancelled && budget-- > 0 && chunkZ <= range.maxChunkZ())
        {
            int blockX = (chunkX << 4) + localX;
            int blockZ = (chunkZ << 4) + localZ;
            pos.set(blockX, y, blockZ);
            BlockState oldState = level.getBlockState(pos);
            if (!oldState.isAir())
            {
                level.setBlock(pos, air, 2 | 16);
                changedBlocks++;
            }
            advanceBlock();
        }

        if (!cancelled && chunkZ > range.maxChunkZ())
        {
            if (saveAfter) server.saveEverything(false, true, false);
            done = true;
            source.sendSuccess(() -> Component.literal("clear 完成：访问 " + range.chunkCount() + " 个区块，清空 " + changedBlocks + " 个非空气方块。" + (saveAfter ? " 已触发保存。" : "")), true);
        }
    }

    @Override
    public Component status()
    {
        return Component.literal(name() + " chunk=" + chunkX + "," + chunkZ + " changed=" + changedBlocks);
    }

    private void advanceBlock()
    {
        y++;
        if (y >= level.getMaxBuildHeight())
        {
            y = level.getMinBuildHeight();
            localX++;
            if (localX >= 16)
            {
                localX = 0;
                localZ++;
                if (localZ >= 16)
                {
                    localZ = 0;
                    processedChunks++;
                    chunkX++;
                    if (chunkX > range.maxChunkX()) { chunkX = range.minChunkX(); chunkZ++; }
                }
            }
        }
    }
}