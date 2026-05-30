package cn.xianaldai.worldgenrender.task.worldgen;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.AbstractWorldGenTask;
import cn.xianaldai.worldgenrender.task.ChunkRange;
import cn.xianaldai.worldgenrender.util.WorldGenOperations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/** 删除磁盘中的 chunk/entities/poi 区块条目，使未加载区块下次加载时重新走世界生成。 */
public final class WorldGenResetSavedTask extends AbstractWorldGenTask
{
    private static final int UNLOAD_DRAIN_TICKS = 40;

    private final WorldGenOperations.HotPlayerGuard playerGuard;
    private final List<ChunkPos> residentChunksToInvalidate = new ArrayList<>();
    private int currentX;
    private int currentZ;
    private int invalidationIndex;
    private int deletedEntries;
    private int clearedChunks;
    private int preparedResidentChunks;
    private int invalidatedResidentChunks;
    private int discardedEntities;
    private int unforcedChunks;
    private int drainTicks;
    private int drainPurgeIndex;
    private boolean deletionFlushed;
    private boolean finalFlushed;
    private boolean restoreStarted;

    public WorldGenResetSavedTask(CommandSourceStack source, ServerLevel level, ChunkRange range)
    {
        super(source, level, range);
        this.playerGuard = WorldGenOperations.protectPlayersForHotRegeneration(level);
        this.currentX = range.minChunkX();
        this.currentZ = range.minChunkZ();
        source.sendSuccess(() -> Component.literal("提示：resetSaved 正在按热重生成处理：目标维度玩家 " + playerGuard.affectedPlayers()
                + " 人已临时旁观并释放玩家 ticket；已加载、玩家附近或正在 IO 的区块不会跳过，会被标记为热重生成。"), true);
    }

    @Override public String name() { return "resetSaved"; }

    @Override
    public void cancel()
    {
        super.cancel();
        playerGuard.restore();
    }

    @Override
    public void tick(MinecraftServer server)
    {
        if (cancelled)
        {
            playerGuard.restore();
            return;
        }

        playerGuard.refresh();

        int budget = Config.maxChunksPerTick;
        while (!cancelled && budget-- > 0 && currentZ <= range.maxChunkZ())
        {
            ChunkPos pos = new ChunkPos(currentX, currentZ);
            if (WorldGenOperations.unforceChunkIfNeeded(level, pos))
            {
                unforcedChunks++;
            }

            discardedEntities += WorldGenOperations.discardNonPlayerEntitiesInChunk(level, pos);
            boolean resident = WorldGenOperations.prepareChunkForHotRegeneration(level, pos, playerGuard);
            if (resident)
            {
                preparedResidentChunks++;
                residentChunksToInvalidate.add(pos);
            }

            int deleted = WorldGenOperations.deleteSavedChunk(level, pos);
            if (deleted > 0)
            {
                clearedChunks++;
                deletedEntries += deleted;
            }
            processedChunks++;
            advance();
        }

        WorldGenOperations.runChunkDistanceUpdates(level);

        if (!cancelled && currentZ > range.maxChunkZ())
        {
            if (!deletionFlushed)
            {
                // 必须等所有目标 chunk 的磁盘条目删除完成后，才统一失效内存 holder，避免生成依赖读到旧邻区。
                WorldGenOperations.flushChunkData(level);
                deletionFlushed = true;
            }

            int invalidationBudget = Config.maxChunksPerTick;
            while (!cancelled && invalidationBudget-- > 0 && invalidationIndex < residentChunksToInvalidate.size())
            {
                ChunkPos pos = residentChunksToInvalidate.get(invalidationIndex++);
                if (WorldGenOperations.invalidatePreparedChunkForHotRegeneration(level, pos))
                {
                    invalidatedResidentChunks++;
                }
            }

            WorldGenOperations.runChunkDistanceUpdates(level);
            if (!cancelled && invalidationIndex < residentChunksToInvalidate.size())
            {
                return;
            }

            if (drainTicks < UNLOAD_DRAIN_TICKS || drainPurgeIndex < residentChunksToInvalidate.size())
            {
                purgeResidentRuntimeCachesDuringDrain();
                drainTicks++;
                WorldGenOperations.runChunkDistanceUpdates(level);
                return;
            }
            if (!restoreStarted)
            {
                if (!finalFlushed)
                {
                    WorldGenOperations.flushChunkData(level);
                    finalFlushed = true;
                }
                playerGuard.beginRestore(GameType.CREATIVE);
                restoreStarted = true;
                return;
            }
            if (!playerGuard.tickClientResync())
            {
                return;
            }

            done = true;
            source.sendSuccess(() -> Component.literal("resetSaved 热重生成完成：处理 " + processedChunks
                    + " 个区块，清空/标记重生成 " + clearedChunks + " 个区块，删除/清空 " + deletedEntries
                    + " 个 region/entities/poi 条目，取消强加载 " + unforcedChunks
                    + " 个，丢弃旧非玩家实体 " + discardedEntities
                    + " 个，对已加载或玩家附近区块执行热重生成准备 " + preparedResidentChunks
                    + " 个，在全部删除完成并 flush 后强制失效旧内存 holder/future " + invalidatedResidentChunks
                    + " 个，drain 阶段再次兜底清理运行时缓存 " + drainPurgeIndex
                    + " 个；受保护玩家已切换为创造模式，并完成延迟客户端 chunk 重同步。"), true);
        }
    }

    @Override
    public Component status()
    {
        return Component.literal(name() + " " + processedChunks + "/" + range.chunkCount()
                + " invalidating=" + invalidationIndex + "/" + residentChunksToInvalidate.size()
                + " clearedChunks=" + clearedChunks
                + " deletedEntries=" + deletedEntries
                + " preparedResident=" + preparedResidentChunks
                + " invalidatedResident=" + invalidatedResidentChunks
                + " discardedEntities=" + discardedEntities
                + " unforced=" + unforcedChunks
                + " drainTicks=" + drainTicks + "/" + UNLOAD_DRAIN_TICKS
                + " drainPurge=" + drainPurgeIndex + "/" + residentChunksToInvalidate.size()
                + " clientResync=" + playerGuard.clientResyncStatus());
    }

    /** drain 阶段再扫一遍驻留 chunk 缓存，兜底处理较晚完成的异步实体/POI 读取。 */
    private void purgeResidentRuntimeCachesDuringDrain()
    {
        int purgeBudget = Config.maxChunksPerTick;
        while (!cancelled && purgeBudget-- > 0 && drainPurgeIndex < residentChunksToInvalidate.size())
        {
            WorldGenOperations.purgeRuntimeCachesForHotRegeneration(level, residentChunksToInvalidate.get(drainPurgeIndex++));
        }
    }

    private void advance()
    {
        currentX++;
        if (currentX > range.maxChunkX()) { currentX = range.minChunkX(); currentZ++; }
    }
}