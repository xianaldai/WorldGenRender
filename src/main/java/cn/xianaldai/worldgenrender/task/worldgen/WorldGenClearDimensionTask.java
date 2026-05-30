package cn.xianaldai.worldgenrender.task.worldgen;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.WorldGenManagedTask;
import cn.xianaldai.worldgenrender.util.WorldGenOperations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 在线热清空整个维度已经保存的 chunk/entities/poi 条目。
 */
public final class WorldGenClearDimensionTask implements WorldGenManagedTask
{
    private static final int UNLOAD_DRAIN_TICKS = 40;

    private final CommandSourceStack source;
    private final ServerLevel level;
    private final List<ChunkPos> savedChunks;
    private final List<ChunkPos> residentChunksToInvalidate = new ArrayList<>();
    private final List<ChunkPos> visibleChunksToPreload = new ArrayList<>();
    private final Map<ChunkPos, CompletableFuture<?>> visiblePreloadFutures = new LinkedHashMap<>();
    private final WorldGenOperations.HotPlayerGuard playerGuard;
    private int index;
    private int invalidationIndex;
    private int preloadIndex;
    private int deletedEntries;
    private int clearedChunks;
    private int preparedResidentChunks;
    private int invalidatedResidentChunks;
    private int discardedEntities;
    private int unforcedChunks;
    private int preloadedVisibleChunks;
    private int failedVisiblePreloadChunks;
    private int drainTicks;
    private int drainPurgeIndex;
    private boolean deletionFlushed;
    private boolean finalFlushed;
    private boolean preloadBarrierFlushed;
    private boolean restoreStarted;
    private boolean done;
    private boolean cancelled;

    public WorldGenClearDimensionTask(CommandSourceStack source, ServerLevel level)
    {
        this.source = source;
        this.level = level;
        this.savedChunks = WorldGenOperations.listHotRegenerationChunkPositions(level);
        this.playerGuard = WorldGenOperations.protectPlayersForHotRegeneration(level);
        source.sendSuccess(() -> Component.literal("已提交 clearDimension 热操作任务，维度 " + level.dimension().location()
                + "，扫描到 " + savedChunks.size() + " 个已保存或运行时驻留区块。目标维度玩家 " + playerGuard.affectedPlayers()
                + " 人已进入热重生成保护：临时旁观、释放玩家 ticket，已加载/玩家附近区块不会跳过。"), true);
    }

    @Override public String name() { return "clearDimension"; }
    @Override public boolean isDone() { return done; }

    @Override
    public void cancel()
    {
        cancelled = true;
        done = true;
        visiblePreloadFutures.clear();
        playerGuard.restore();
        source.sendSuccess(() -> Component.literal("任务 clearDimension 已取消。"), true);
    }

    @Override
    public Component status()
    {
        return Component.literal("clearDimension dimension=" + level.dimension().location()
                + " " + index + "/" + savedChunks.size()
                + " invalidating=" + invalidationIndex + "/" + residentChunksToInvalidate.size()
                + " visiblePreload=" + preloadIndex + "/" + visibleChunksToPreload.size()
                + " pendingPreload=" + visiblePreloadFutures.size() + "/" + Config.hotRegenerationMaxConcurrentChunkFutures
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

    @Override
    public void tick(MinecraftServer server)
    {
        if (cancelled)
        {
            done = true;
            visiblePreloadFutures.clear();
            playerGuard.restore();
            return;
        }

        playerGuard.refresh();

        int budget = Config.maxChunksPerTick;
        while (!cancelled && budget-- > 0 && index < savedChunks.size())
        {
            ChunkPos pos = savedChunks.get(index++);
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
        }

        WorldGenOperations.runChunkDistanceUpdates(level);

        if (!cancelled && index >= savedChunks.size())
        {
            if (!deletionFlushed)
            {
                WorldGenOperations.hotRegenerationStorageBarrier(level);
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
            if (!finalFlushed)
            {
                WorldGenOperations.hotRegenerationStorageBarrier(level);
                finalFlushed = true;
                visibleChunksToPreload.addAll(WorldGenOperations.collectPlayerVisibleChunkPositions(level, Config.hotRegenerationVisibleRadiusExtra));
                return;
            }
            if (preloadIndex < visibleChunksToPreload.size() || !visiblePreloadFutures.isEmpty())
            {
                preloadVisibleChunksBeforeRestore();
                WorldGenOperations.runChunkDistanceUpdates(level);
                return;
            }
            if (!preloadBarrierFlushed)
            {
                WorldGenOperations.hotRegenerationStorageBarrier(level);
                preloadBarrierFlushed = true;
                return;
            }
            if (!restoreStarted)
            {
                playerGuard.beginRestore(GameType.CREATIVE);
                restoreStarted = true;
                return;
            }
            if (!playerGuard.tickClientResync())
            {
                return;
            }

            done = true;
            source.sendSuccess(() -> Component.literal("clearDimension 热重生成完成：维度 " + level.dimension().location()
                    + "，扫描 " + savedChunks.size() + " 个已保存或运行时驻留区块，处理/清空 " + clearedChunks
                    + " 个磁盘区块，删除/清空 " + deletedEntries + " 个 region/entities/poi 条目，取消强加载 " + unforcedChunks
                    + " 个，丢弃旧非玩家实体 " + discardedEntities
                    + " 个，对已加载或玩家附近区块执行热重生成准备 " + preparedResidentChunks
                    + " 个，在全部删除完成并 flush 后强制失效旧内存 holder/future " + invalidatedResidentChunks
                    + " 个，drain 阶段再次兜底清理运行时缓存 " + drainPurgeIndex
                    + " 个，恢复前通过 vanilla/Forge future 并发调度真实预热玩家可见区块 " + preloadedVisibleChunks + "/" + visibleChunksToPreload.size()
                    + " 个，失败/未 ready " + failedVisiblePreloadChunks + " 个，并发上限 " + Config.hotRegenerationMaxConcurrentChunkFutures
                    + "；受保护玩家已切换为创造模式，并完成延迟客户端 chunk 重同步。"), true);
        }
    }

    private void preloadVisibleChunksBeforeRestore()
    {
        collectCompletedVisiblePreloadFutures();

        int scheduleBudget = Config.hotRegenerationPreloadChunksPerTick;
        int concurrency = Math.max(1, Config.hotRegenerationMaxConcurrentChunkFutures);
        while (!cancelled
                && scheduleBudget-- > 0
                && preloadIndex < visibleChunksToPreload.size()
                && visiblePreloadFutures.size() < concurrency)
        {
            ChunkPos pos = visibleChunksToPreload.get(preloadIndex++);
            if (WorldGenOperations.isFullChunkReadyForHotRegeneration(level, pos))
            {
                preloadedVisibleChunks++;
                continue;
            }
            CompletableFuture<?> future = WorldGenOperations.scheduleFullChunkForHotRegeneration(level, pos);
            visiblePreloadFutures.put(pos, future);
        }

        collectCompletedVisiblePreloadFutures();
    }

    private void collectCompletedVisiblePreloadFutures()
    {
        Iterator<Map.Entry<ChunkPos, CompletableFuture<?>>> iterator = visiblePreloadFutures.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<ChunkPos, CompletableFuture<?>> entry = iterator.next();
            CompletableFuture<?> future = entry.getValue();
            if (!future.isDone()) continue;

            iterator.remove();
            ChunkPos pos = entry.getKey();
            if (future.isCompletedExceptionally())
            {
                failedVisiblePreloadChunks++;
            }
            else if (WorldGenOperations.isFullChunkReadyForHotRegeneration(level, pos))
            {
                preloadedVisibleChunks++;
            }
            else
            {
                failedVisiblePreloadChunks++;
            }
        }
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
}
