package cn.xianaldai.worldgenrender.util;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.ChunkRange;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import org.apache.commons.lang3.mutable.MutableObject;

/** 世界路径、region 文件和输出文件相关的底层辅助方法。 */
public final class WorldGenOperations
{
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);
    private static final Map<Path, String> PENDING_DIMENSION_DELETES = new LinkedHashMap<>();
    private static final Pattern REGION_FILE_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private WorldGenOperations() {}

    public record DeleteSummary(int deletedFiles, int failedFiles) {}

    /**
     * 当前维度热重生成时的玩家保护器。
     * <p>
     * 目标维度内玩家会被临时切到旁观，并且临时关闭“旁观者生成区块”规则，让 vanilla 的
     * ChunkMap/DistanceManager 把玩家 ticket 移除。这样玩家仍留在原维度观察，但其附近 chunk
     * 不再因为玩家 ticket 被永久钉在内存里，删除磁盘条目后可以正常卸载并在恢复玩家时重新生成。
     */
    public static final class HotPlayerGuard
    {
        private static final int CLIENT_RESYNC_INITIAL_DELAY_TICKS = 5;
        private static final int CLIENT_RESYNC_ATTEMPT_INTERVAL_TICKS = 10;
        private static final int CLIENT_RESYNC_SETTLE_TICKS = 10;

        private final ServerLevel level;
        private final Map<UUID, GameType> originalModes = new LinkedHashMap<>();
        private final Set<UUID> playersNeedingClientResync = new LinkedHashSet<>();
        private final boolean originalSpectatorsGenerateChunks;
        private boolean restored;
        private boolean clientResyncFinished = true;
        private int clientResyncTicks;
        private int clientResyncAttempts;

        private HotPlayerGuard(ServerLevel level)
        {
            this.level = level;
            this.originalSpectatorsGenerateChunks = level.getGameRules().getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).get();
            if (originalSpectatorsGenerateChunks)
            {
                level.getGameRules().getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).set(false, level.getServer());
            }
            refresh();
        }

        /** 把当前仍在目标维度的玩家纳入保护；任务运行期间新进入该维度的玩家也会被处理。 */
        public void refresh()
        {
            if (restored) return;

            for (ServerPlayer player : level.players())
            {
                UUID uuid = player.getUUID();
                if (!originalModes.containsKey(uuid))
                {
                    originalModes.put(uuid, player.gameMode.getGameModeForPlayer());
                    player.sendSystemMessage(Component.literal("WorldGenRender 正在热重生成当前维度：你已被临时切换为旁观模式，任务结束后会尝试恢复。"));
                }
                if (!player.isSpectator())
                {
                    player.setGameMode(GameType.SPECTATOR);
                }
                try
                {
                    player.stopRiding();
                    player.fallDistance = 0.0F;
                }
                catch (Throwable ignored)
                {
                }
                try
                {
                    level.getChunkSource().move(player);
                }
                catch (Throwable ignored)
                {
                }
            }
            runChunkDistanceUpdates(level);
        }

        public int affectedPlayers()
        {
            return originalModes.size();
        }

        public void forgetChunk(ChunkPos pos)
        {
        }

        public void restore()
        {
            restore(null);
        }

        public void restore(GameType targetMode)
        {
            if (restored) return;
            restored = true;

            restoreSpectatorsGenerateChunksRule();
            restorePlayers(targetMode, false);
            clientResyncFinished = true;
            runChunkDistanceUpdates(level);
        }

        public void beginRestore(GameType targetMode)
        {
            if (restored) return;
            restored = true;

            restoreSpectatorsGenerateChunksRule();
            restorePlayers(targetMode, true);
            clientResyncFinished = playersNeedingClientResync.isEmpty();
            clientResyncTicks = 0;
            clientResyncAttempts = 0;
            runChunkDistanceUpdates(level);
        }

        public boolean tickClientResync()
        {
            if (!restored)
            {
                beginRestore(null);
            }
            if (clientResyncFinished) return true;

            clientResyncTicks++;
            runChunkDistanceUpdates(level);

            if (clientResyncTicks >= CLIENT_RESYNC_INITIAL_DELAY_TICKS
                    && clientResyncAttempts < Config.hotRegenerationClientResyncAttempts
                    && (clientResyncTicks - CLIENT_RESYNC_INITIAL_DELAY_TICKS) % CLIENT_RESYNC_ATTEMPT_INTERVAL_TICKS == 0)
            {
                resetClientLevelsAndResendVisibleChunks();
                clientResyncAttempts++;
                runChunkDistanceUpdates(level);
            }

            int finishTick = CLIENT_RESYNC_INITIAL_DELAY_TICKS
                    + Math.max(0, Config.hotRegenerationClientResyncAttempts - 1) * CLIENT_RESYNC_ATTEMPT_INTERVAL_TICKS
                    + CLIENT_RESYNC_SETTLE_TICKS;
            if (clientResyncTicks >= finishTick || playersNeedingClientResync.isEmpty())
            {
                clientResyncFinished = true;
                return true;
            }
            return false;
        }

        public String clientResyncStatus()
        {
            if (!restored) return "not-started";
            if (clientResyncFinished) return "done";
            return "ticks=" + clientResyncTicks
                    + " attempts=" + clientResyncAttempts + "/" + Config.hotRegenerationClientResyncAttempts
                    + " players=" + playersNeedingClientResync.size();
        }

        private void restoreSpectatorsGenerateChunksRule()
        {
            if (originalSpectatorsGenerateChunks)
            {
                level.getGameRules().getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).set(true, level.getServer());
            }
        }

        private void restorePlayers(GameType targetMode, boolean scheduleClientResync)
        {
            playersNeedingClientResync.clear();

            for (Map.Entry<UUID, GameType> entry : originalModes.entrySet())
            {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
                if (player == null) continue;
                if (player.level() != level) continue;

                if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR)
                {
                    GameType mode = targetMode == null ? entry.getValue() : targetMode;
                    player.setGameMode(mode);
                    player.fallDistance = 0.0F;
                    player.sendSystemMessage(Component.literal("WorldGenRender 热重生成已结束：已将你的游戏模式设置为 " + mode.getName() + "。"));
                }
                try
                {
                    level.getChunkSource().move(player);
                    if (scheduleClientResync)
                    {
                        playersNeedingClientResync.add(entry.getKey());
                    }
                }
                catch (Throwable ignored)
                {
                }
            }
        }

        private void resetClientLevelsAndResendVisibleChunks()
        {
            playersNeedingClientResync.removeIf(uuid ->
            {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
                if (player == null || player.level() != level) return true;

                try
                {
                    forcePlayerCenterChunk(level, player);
                    forceReloadVisibleChunks(level, player);
                    level.getChunkSource().move(player);
                }
                catch (Throwable ignored)
                {
                }
                return false;
            });
        }
    }

    public static HotPlayerGuard protectPlayersForHotRegeneration(ServerLevel level)
    {
        return new HotPlayerGuard(level);
    }

    private static void forcePlayerCenterChunk(ServerLevel level, ServerPlayer player)
    {
        try
        {
            ChunkPos pos = player.chunkPosition();
            level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
        }
        catch (Throwable ignored)
        {
        }
    }

    public static List<ChunkPos> collectPlayerVisibleChunkPositions(ServerLevel level, int extraRadius)
    {
        Object chunkMap = level.getChunkSource().chunkMap;
        int viewDistance = readIntFieldQuietly(chunkMap, "viewDistance", 10);
        int radius = viewDistance + Math.max(0, extraRadius);
        Set<ChunkPos> positions = new LinkedHashSet<>();
        for (ServerPlayer player : level.players())
        {
            int centerX = SectionPos.blockToSectionCoord(player.getBlockX());
            int centerZ = SectionPos.blockToSectionCoord(player.getBlockZ());
            for (int chunkX = centerX - radius - 1; chunkX <= centerX + radius + 1; chunkX++)
            {
                for (int chunkZ = centerZ - radius - 1; chunkZ <= centerZ + radius + 1; chunkZ++)
                {
                    if (isChunkInVanillaTrackingRange(chunkX, chunkZ, centerX, centerZ, radius))
                    {
                        positions.add(new ChunkPos(chunkX, chunkZ));
                    }
                }
            }
        }
        return new ArrayList<>(positions);
    }

    public static boolean preloadFullChunkForHotRegeneration(ServerLevel level, ChunkPos pos)
    {
        try
        {
            level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
            runChunkDistanceUpdates(level);
            return isFullChunkReadyForHotRegeneration(level, pos);
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * 调度一个 FULL chunk future，但不在主线程 join。
     * <p>
     * {@code ServerChunkCache.getChunkFuture(...)} 在主线程调用时会 managedBlock 等待完成，
     * 因此这里反射调用私有的 {@code getChunkFutureMainThread(...)}：它只添加 UNKNOWN ticket 并走
     * {@code ChunkHolder.getOrScheduleFuture(...)}，实际世界生成仍由 vanilla/Forge chunk pipeline 与其后台 executor 并行执行。
     */
    public static CompletableFuture<?> scheduleFullChunkForHotRegeneration(ServerLevel level, ChunkPos pos)
    {
        try
        {
            Method method = level.getChunkSource().getClass().getDeclaredMethod("getChunkFutureMainThread", int.class, int.class, ChunkStatus.class, boolean.class);
            method.setAccessible(true);
            Object future = method.invoke(level.getChunkSource(), pos.x, pos.z, ChunkStatus.FULL, true);
            if (future instanceof CompletableFuture<?> completableFuture)
            {
                return completableFuture;
            }
        }
        catch (Throwable ignored)
        {
        }
        return CompletableFuture.completedFuture(null);
    }

    public static boolean isFullChunkReadyForHotRegeneration(ServerLevel level, ChunkPos pos)
    {
        try
        {
            return isTickingChunkReady(level.getChunkSource().chunkMap, pos) || level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * 热重生成阶段的 I/O 顺序屏障。
     * <p>
     * chunk 保存是异步的，不能假设旧保存何时完成。这里连续推进距离管理/unload 队列并同步 terrain、entities、POI
     * 三类 IOWorker，使删除写入、空实体列与 POI null 写入在开始新一轮 worldgen 前尽可能落盘并排在旧 pending write 之后。
     */
    public static void hotRegenerationStorageBarrier(ServerLevel level)
    {
        runChunkDistanceUpdates(level);
        flushChunkData(level);
        runChunkDistanceUpdates(level);
        flushChunkData(level);
        clearServerChunkCache(level);
    }

    private static void forceReloadVisibleChunks(ServerLevel level, ServerPlayer player)
    {
        Object chunkMap = level.getChunkSource().chunkMap;
        int viewDistance = readIntFieldQuietly(chunkMap, "viewDistance", 10);
        int centerX = SectionPos.blockToSectionCoord(player.getBlockX());
        int centerZ = SectionPos.blockToSectionCoord(player.getBlockZ());

        for (int chunkX = centerX - viewDistance - 1; chunkX <= centerX + viewDistance + 1; chunkX++)
        {
            for (int chunkZ = centerZ - viewDistance - 1; chunkZ <= centerZ + viewDistance + 1; chunkZ++)
            {
                if (!isChunkInVanillaTrackingRange(chunkX, chunkZ, centerX, centerZ, viewDistance)) continue;

                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                LevelChunk chunk = getTickingChunk(chunkMap, pos);
                if (chunk == null) chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) continue;

                try
                {
                    player.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
                    player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getChunkSource().getLightEngine(), null, null));
                }
                catch (Throwable ignored)
                {
                }
            }
        }
    }

    private static LevelChunk forceChunkRegenerationForResync(ServerLevel level, ChunkPos pos)
    {
        try
        {
            ChunkAccess chunk = level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
            runChunkDistanceUpdates(level);
            LevelChunk tickingChunk = getTickingChunk(level.getChunkSource().chunkMap, pos);
            clearServerChunkCache(level);
            if (tickingChunk != null) return tickingChunk;
            return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static LevelChunk getTickingChunk(Object chunkMap, ChunkPos pos)
    {
        try
        {
            Object holder = invokeChunkHolderLookup(chunkMap, "getVisibleChunkIfPresent", pos.toLong());
            if (holder == null) return null;
            Method method = holder.getClass().getDeclaredMethod("getTickingChunk");
            method.setAccessible(true);
            Object chunk = method.invoke(holder);
            return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static boolean isTickingChunkReady(Object chunkMap, ChunkPos pos)
    {
        return getTickingChunk(chunkMap, pos) != null;
    }

    private static boolean isChunkInVanillaTrackingRange(int chunkX, int chunkZ, int centerX, int centerZ, int viewDistance)
    {
        int dx = Math.max(0, Math.abs(chunkX - centerX) - 1);
        int dz = Math.max(0, Math.abs(chunkZ - centerZ) - 1);
        long outside = Math.max(0, Math.max(dx, dz) - 1);
        long inside = Math.min(dx, dz);
        long distance = inside * inside + outside * outside;
        int radius = viewDistance * viewDistance;
        return distance < radius;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void invokeChunkTrackingUpdate(Object chunkMap, ServerPlayer player, ChunkPos pos, boolean oldTracked, boolean newTracked)
    {
        try
        {
            Method method = chunkMap.getClass().getDeclaredMethod("updateChunkTracking", ServerPlayer.class, ChunkPos.class, MutableObject.class, boolean.class, boolean.class);
            method.setAccessible(true);
            method.invoke(chunkMap, player, pos, new MutableObject(), oldTracked, newTracked);
        }
        catch (Throwable ignored)
        {
        }
    }

    public static Path resolveOutputPath(MinecraftServer server, ServerLevel level, ChunkRange range, String fileName)
    {
        return resolveOutputPath(server, level, range, fileName, ".png");
    }

    public static Path resolveOutputPath(MinecraftServer server, ServerLevel level, ChunkRange range, String fileName, String extension)
    {
        String normalizedExtension = extension == null || extension.isBlank() ? ".png" : extension;
        if (!normalizedExtension.startsWith(".")) normalizedExtension = "." + normalizedExtension;

        String safeFileName = fileName;
        if (safeFileName == null || safeFileName.isBlank())
        {
            String dimension = level.dimension().location().toString().replace(':', '_').replace('/', '_');
            safeFileName = dimension + "_" + range.minChunkX() + "_" + range.minChunkZ() + "_" + range.maxChunkX() + "_" + range.maxChunkZ() + "_" + LocalDateTime.now().format(FILE_TIME) + normalizedExtension;
        }
        if (!safeFileName.toLowerCase(Locale.ROOT).endsWith(normalizedExtension.toLowerCase(Locale.ROOT))) safeFileName += normalizedExtension;

        Path base = server.getServerDirectory().toPath().resolve(Config.outputDirectory).normalize();
        Path output = base.resolve(safeFileName).normalize();
        return output.startsWith(base) ? output : base.resolve(output.getFileName()).normalize();
    }

    /** 删除 chunk、entities、poi 三类 region 文件中的对应区块条目。 */
    public static int deleteSavedChunk(ServerLevel level, ChunkPos pos)
    {
        Path dimensionPath = getDimensionPath(level);
        int deleted = 0;
        deleted += clearChunkData(level, dimensionPath.resolve("region"), pos);
        deleted += clearFromRegionFolder(dimensionPath.resolve("entities"), pos);
        deleted += clearPoiData(level, dimensionPath.resolve("poi"), pos);
        return deleted;
    }

    public static List<ChunkPos> listHotRegenerationChunkPositions(ServerLevel level)
    {
        Set<ChunkPos> positions = new LinkedHashSet<>(listSavedChunkPositions(level));
        collectRuntimeChunkPositions(level, positions);
        return new ArrayList<>(positions);
    }

    public static List<ChunkPos> listSavedChunkPositions(ServerLevel level)
    {
        Path dimensionPath = getDimensionPath(level);
        Set<ChunkPos> positions = new LinkedHashSet<>();
        collectSavedChunkPositions(dimensionPath.resolve("region"), positions);
        collectSavedChunkPositions(dimensionPath.resolve("entities"), positions);
        collectSavedChunkPositions(dimensionPath.resolve("poi"), positions);
        return new ArrayList<>(positions);
    }

    /** 判断 chunk 是否已经在内存、可见 chunk map 或更新 chunk map 中，避免热删除后又被内存对象回写。 */
    public static boolean isChunkLoadedOrPending(ServerLevel level, ChunkPos pos)
    {
        if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) return true;

        Object chunkMap = level.getChunkSource().chunkMap;
        long chunkKey = pos.toLong();
        return invokeChunkHolderLookup(chunkMap, "getVisibleChunkIfPresent", chunkKey) != null
                || invokeChunkHolderLookup(chunkMap, "getUpdatingChunkIfPresent", chunkKey) != null
                || getLongMapValue(readFieldQuietly(chunkMap, "pendingUnloads"), chunkKey) != null;
    }

    /** 如果该 chunk 被 vanilla forced chunk 持有，先取消强加载 ticket，让它之后有机会卸载。 */
    public static boolean unforceChunkIfNeeded(ServerLevel level, ChunkPos pos)
    {
        try
        {
            if (level.getForcedChunks().contains(pos.toLong()))
            {
                return level.setChunkForced(pos.x, pos.z, false);
            }
        }
        catch (Throwable ignored)
        {
        }
        return false;
    }

    /** 等待当前世界 chunk IOWorker 中已经提交的热删除写入完成。 */
    public static void flushChunkData(ServerLevel level)
    {
        try
        {
            level.getChunkSource().chunkMap.flushWorker();
        }
        catch (Throwable ignored)
        {
        }

        try
        {
            Object entityManager = readField(level, "entityManager");
            Object permanentStorage = readField(entityManager, "permanentStorage");
            Method flush = permanentStorage.getClass().getMethod("flush", boolean.class);
            flush.invoke(permanentStorage, true);
        }
        catch (Throwable ignored)
        {
        }

        try
        {
            synchronizeIOWorker(readField(level.getPoiManager(), "worker"), true);
        }
        catch (Throwable ignored)
        {
        }
    }

    public static void purgeRuntimeCachesForHotRegeneration(ServerLevel level, ChunkPos pos)
    {
        purgeChunkRuntimeCaches(level, pos);
    }

    public static boolean prepareChunkForHotRegeneration(ServerLevel level, ChunkPos pos, HotPlayerGuard playerGuard)
    {
        if (playerGuard != null) playerGuard.forgetChunk(pos);

        boolean loadedOrPending = isChunkLoadedOrPending(level, pos);
        LevelChunk loadedChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (loadedChunk != null)
        {
            suppressSave(loadedChunk);
        }

        Object chunkMap = level.getChunkSource().chunkMap;
        Object holder = invokeChunkHolderLookup(chunkMap, "getVisibleChunkIfPresent", pos.toLong());
        if (holder == null) holder = invokeChunkHolderLookup(chunkMap, "getUpdatingChunkIfPresent", pos.toLong());
        if (holder == null) holder = getLongMapValue(readFieldQuietly(chunkMap, "pendingUnloads"), pos.toLong());
        if (holder != null)
        {
            suppressHolderSave(holder);
            loadedOrPending = true;
        }

        purgeChunkRuntimeCaches(level, pos);
        markChunkReplaceable(level, pos);
        clearServerChunkCache(level);
        return loadedOrPending;
    }

    public static boolean invalidatePreparedChunkForHotRegeneration(ServerLevel level, ChunkPos pos)
    {
        boolean invalidated = false;
        LevelChunk loadedChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (loadedChunk != null)
        {
            detachLoadedChunkForRegeneration(level, loadedChunk);
            invalidated = true;
        }

        Object chunkMap = level.getChunkSource().chunkMap;
        Object holder = invokeChunkHolderLookup(chunkMap, "getVisibleChunkIfPresent", pos.toLong());
        if (holder == null) holder = invokeChunkHolderLookup(chunkMap, "getUpdatingChunkIfPresent", pos.toLong());
        boolean holderFromPendingUnload = false;
        if (holder == null)
        {
            holder = getLongMapValue(readFieldQuietly(chunkMap, "pendingUnloads"), pos.toLong());
            holderFromPendingUnload = holder != null;
        }
        if (holder != null)
        {
            suppressHolderSave(holder);
            detachLastAvailableChunkForRegeneration(level, holder);
            invalidateChunkHolderFutures(chunkMap, holder);
            if (holderFromPendingUnload)
            {
                removeLongFromFieldMap(chunkMap, "pendingUnloads", pos.toLong());
                removeLongFromFieldSet(chunkMap, "toDrop", pos.toLong());
            }
            invalidated = true;
        }

        purgeChunkRuntimeCaches(level, pos);
        markChunkReplaceable(level, pos);
        clearServerChunkCache(level);
        return invalidated;
    }

    public static boolean isChunkStillResident(ServerLevel level, ChunkPos pos)
    {
        return isChunkLoadedOrPending(level, pos);
    }

    public static int discardNonPlayerEntitiesInChunk(ServerLevel level, ChunkPos pos)
    {
        int discarded = 0;
        try
        {
            List<Entity> toDiscard = new ArrayList<>();
            for (Entity entity : level.getAllEntities())
            {
                if (entity instanceof ServerPlayer) continue;
                if (entity.chunkPosition().equals(pos))
                {
                    toDiscard.add(entity);
                }
            }

            for (Entity entity : toDiscard)
            {
                try
                {
                    entity.stopRiding();
                    entity.discard();
                    discarded++;
                }
                catch (Throwable ignored)
                {
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return discarded;
    }

    /** 推进 ticket 计算、unload 队列和 ServerChunkCache 小缓存清理。 */
    public static void runChunkDistanceUpdates(ServerLevel level)
    {
        try
        {
            Method method = level.getChunkSource().getClass().getDeclaredMethod("runDistanceManagerUpdates");
            method.setAccessible(true);
            method.invoke(level.getChunkSource());
        }
        catch (Throwable ignored)
        {
        }

        try
        {
            Method method = level.getChunkSource().chunkMap.getClass().getDeclaredMethod("tick", BooleanSupplier.class);
            method.setAccessible(true);
            method.invoke(level.getChunkSource().chunkMap, (BooleanSupplier) () -> true);
        }
        catch (Throwable ignored)
        {
        }

        clearServerChunkCache(level);
    }

    /** 登记整个维度的 region/entities/poi，等服务器完全停止后再删除，避免在线删除已打开的 region 文件。 */
    public static synchronized boolean scheduleDimensionContentDeletion(ServerLevel level)
    {
        Path dimensionPath = getDimensionPath(level).toAbsolutePath().normalize();
        return PENDING_DIMENSION_DELETES.putIfAbsent(dimensionPath, level.dimension().location().toString()) == null;
    }

    public static synchronized int pendingDimensionDeletionCount()
    {
        return PENDING_DIMENSION_DELETES.size();
    }

    /** 删除所有已登记维度的 region/entities/poi 下 .mca 文件。应只在 ServerStoppedEvent 后调用。 */
    public static synchronized DeleteSummary deletePendingDimensionContents()
    {
        DeleteSummary total = new DeleteSummary(0, 0);
        for (Path dimensionPath : PENDING_DIMENSION_DELETES.keySet())
        {
            DeleteSummary summary = deleteDimensionContent(dimensionPath);
            total = new DeleteSummary(total.deletedFiles() + summary.deletedFiles(), total.failedFiles() + summary.failedFiles());
        }
        PENDING_DIMENSION_DELETES.clear();
        return total;
    }

    private static DeleteSummary deleteDimensionContent(Path dimensionPath)
    {
        DeleteSummary region = deleteMcaFiles(dimensionPath.resolve("region"));
        DeleteSummary entities = deleteMcaFiles(dimensionPath.resolve("entities"));
        DeleteSummary poi = deleteMcaFiles(dimensionPath.resolve("poi"));
        return new DeleteSummary(
                region.deletedFiles() + entities.deletedFiles() + poi.deletedFiles(),
                region.failedFiles() + entities.failedFiles() + poi.failedFiles());
    }

    public static Path getDimensionPath(ServerLevel level)
    {
        MinecraftServer server = level.getServer();
        Path root = server.getWorldPath(LevelResource.ROOT);
        if (level.dimension() == Level.OVERWORLD) return root;
        if (level.dimension() == Level.NETHER) return root.resolve("DIM-1");
        if (level.dimension() == Level.END) return root.resolve("DIM1");
        return root.resolve("dimensions").resolve(level.dimension().location().getNamespace()).resolve(level.dimension().location().getPath());
    }

    private static int clearChunkData(ServerLevel level, Path folder, ChunkPos pos)
    {
        if (!hasSavedChunkEntry(folder, pos)) return 0;

        try
        {
            level.getChunkSource().chunkMap.write(pos, null);
            return 1;
        }
        catch (Throwable ignored)
        {
            return clearFromRegionFolder(folder, pos);
        }
    }

    private static int clearFromRegionFolder(Path folder, ChunkPos pos)
    {
        try
        {
            if (!hasSavedChunkEntry(folder, pos)) return 0;

            Constructor<RegionFileStorage> constructor = RegionFileStorage.class.getDeclaredConstructor(Path.class, boolean.class);
            constructor.setAccessible(true);
            Method write = RegionFileStorage.class.getDeclaredMethod("write", ChunkPos.class, CompoundTag.class);
            write.setAccessible(true);

            try (RegionFileStorage storage = constructor.newInstance(folder, true))
            {
                write.invoke(storage, pos, null);
                storage.flush();
            }
            return 1;
        }
        catch (Throwable ignored)
        {
            return 0;
        }
    }

    private static int clearPoiData(ServerLevel level, Path folder, ChunkPos pos)
    {
        boolean hadSavedEntry = hasSavedChunkEntry(folder, pos);

        try
        {
            // PoiManager 继承 SectionStorage，内部 worker 与 POI runtime 缓存共享 pendingWrites。
            // 通过当前 worker 写入 null，可覆盖/排序同一队列里的旧 POI 写入，避免另开 RegionFileStorage 乱序。
            // 即使磁盘头暂时没有 POI 条目，也仍写入 null：IOWorker.pendingWrites 可能已有稍晚的旧 POI 保存。
            Object worker = readField(level.getPoiManager(), "worker");
            storeNullChunk(worker, pos);
            return hadSavedEntry ? 1 : 0;
        }
        catch (Throwable ignored)
        {
            return hadSavedEntry ? clearFromRegionFolder(folder, pos) : 0;
        }
    }

    private static Object invokeChunkHolderLookup(Object chunkMap, String methodName, long chunkKey)
    {
        try
        {
            Method method = chunkMap.getClass().getDeclaredMethod(methodName, long.class);
            method.setAccessible(true);
            return method.invoke(chunkMap, chunkKey);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static void suppressHolderSave(Object holder)
    {
        try
        {
            Method method = holder.getClass().getDeclaredMethod("getLastAvailable");
            method.setAccessible(true);
            Object chunk = method.invoke(holder);
            if (chunk instanceof ChunkAccess chunkAccess)
            {
                suppressSave(chunkAccess);
            }
        }
        catch (Throwable ignored)
        {
        }

        try
        {
            Method method = holder.getClass().getDeclaredMethod("getChunkToSave");
            method.setAccessible(true);
            Object future = method.invoke(holder);
            if (future instanceof java.util.concurrent.CompletableFuture<?> completableFuture)
            {
                Object chunk = completableFuture.getNow(null);
                if (chunk instanceof ChunkAccess chunkAccess)
                {
                    suppressSave(chunkAccess);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void suppressSave(ChunkAccess chunk)
    {
        try
        {
            chunk.setUnsaved(false);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void detachLoadedChunkForRegeneration(ServerLevel level, LevelChunk chunk)
    {
        try
        {
            suppressSave(chunk);
            chunk.setLoaded(false);
            MinecraftForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
        }
        catch (Throwable ignored)
        {
        }

        try
        {
            level.unload(chunk);
        }
        catch (Throwable ignored)
        {
        }

        Object chunkMap = level.getChunkSource().chunkMap;
        removeLongFromFieldSet(chunkMap, "entitiesInLevel", chunk.getPos().toLong());
        removeLongFromFieldMap(chunkMap, "chunkSaveCooldowns", chunk.getPos().toLong());
    }

    /**
     * 对仅存在于 ChunkHolder/pendingUnloads 中的最后一个 ChunkAccess 做卸载解绑。
     * <p>
     * pendingUnloads 的 holder 不一定还能被 getChunkNow 取到，但 getLastAvailable 里可能仍挂着旧 LevelChunk；
     * 若不先解绑，后续 updateChunkScheduling 重新取回 holder 时可能继续复用旧 chunk/future。
     */
    private static void detachLastAvailableChunkForRegeneration(ServerLevel level, Object holder)
    {
        try
        {
            Method method = holder.getClass().getDeclaredMethod("getLastAvailable");
            method.setAccessible(true);
            Object chunk = method.invoke(holder);
            if (chunk instanceof LevelChunk levelChunk)
            {
                detachLoadedChunkForRegeneration(level, levelChunk);
            }
            else if (chunk instanceof ChunkAccess chunkAccess)
            {
                suppressSave(chunkAccess);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void purgeChunkRuntimeCaches(ServerLevel level, ChunkPos pos)
    {
        clearScheduledTicksAndBlockEvents(level, pos);
        purgeLevelBlockEntityRuntimeCache(level, pos);
        purgeChunkStorageRuntimeCache(level, pos);
        purgePoiRuntimeCache(level, pos);
        purgeEntityRuntimeCache(level, pos);
        purgeStructureCheckCache(level, pos);
    }

    /** 清除目标 chunk 内旧方块/流体计划刻和方块事件，避免旧地形卸载后仍触发旧逻辑。 */
    private static void clearScheduledTicksAndBlockEvents(ServerLevel level, ChunkPos pos)
    {
        try
        {
            BoundingBox box = new BoundingBox(
                    pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                    pos.getMaxBlockX(), level.getMaxBuildHeight() - 1, pos.getMaxBlockZ());
            level.getBlockTicks().clearArea(box);
            level.getFluidTicks().clearArea(box);
            level.clearBlockEvents(box);
            purgeBlockEventsToReschedule(level, box);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** 清掉 clearBlockEvents 不会处理的重排队列，防止旧方块事件下一 tick 又被塞回。 */
    @SuppressWarnings("unchecked")
    private static void purgeBlockEventsToReschedule(ServerLevel level, BoundingBox box)
    {
        try
        {
            Object events = readField(level, "blockEventsToReschedule");
            if (events instanceof Collection<?> collection)
            {
                ((Collection<Object>) collection).removeIf(event -> event instanceof BlockEventData data && box.isInside(data.pos()));
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    /** 清理 Level 全局方块实体 tick/fresh 列表中目标 chunk 的旧对象，避免旧 BE ticker 延后一两 tick 继续执行。 */
    @SuppressWarnings("unchecked")
    private static void purgeLevelBlockEntityRuntimeCache(ServerLevel level, ChunkPos pos)
    {
        try
        {
            removeFromCollectionField(level, "blockEntityTickers", value -> value instanceof TickingBlockEntity ticker && isBlockPosInChunk(ticker.getPos(), pos));
            removeFromCollectionField(level, "pendingBlockEntityTickers", value -> value instanceof TickingBlockEntity ticker && isBlockPosInChunk(ticker.getPos(), pos));
            removeFromCollectionField(level, "freshBlockEntities", value -> value instanceof BlockEntity blockEntity && isBlockPosInChunk(blockEntity.getBlockPos(), pos));
            removeFromCollectionField(level, "pendingFreshBlockEntities", value -> value instanceof BlockEntity blockEntity && isBlockPosInChunk(blockEntity.getBlockPos(), pos));
        }
        catch (Throwable ignored)
        {
        }
    }

    /** 清理 ChunkStorage/IOWorker 的 blender old-data 缓存；热删除后旧 region bitset 不能继续影响新生成。 */
    private static void purgeChunkStorageRuntimeCache(ServerLevel level, ChunkPos pos)
    {
        try
        {
            Object worker = readField(level.getChunkSource().chunkMap, "worker");
            Object regionCache = readField(worker, "regionCacheForBlender");
            synchronized (regionCache)
            {
                invokeLongRemove(regionCache, ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ()));
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * 清除 PoiManager/SectionStorage 中目标 chunk 的已加载 POI section 和 dirty 标记。
     * 不调用 flush(pos)，因为此时目标就是阻止旧 POI 被重新写回 poi region。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void purgePoiRuntimeCache(ServerLevel level, ChunkPos pos)
    {
        try
        {
            Object poiManager = level.getPoiManager();
            long chunkKey = pos.toLong();
            removeLongFromFieldSet(poiManager, "loadedChunks", chunkKey);

            Object storage = readField(poiManager, "storage");
            Object dirty = readField(poiManager, "dirty");
            for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++)
            {
                long sectionKey = SectionPos.asLong(pos.x, sectionY, pos.z);
                invokeLongRemove(storage, sectionKey);
                invokeLongRemove(dirty, sectionKey);
            }
            purgePoiDistanceTrackerCache(poiManager);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** POI 距离追踪器会缓存“离村庄中心距离”，删除 POI 后需要清掉，否则村庄/刷怪等逻辑可能读到旧距离。 */
    private static void purgePoiDistanceTrackerCache(Object poiManager)
    {
        try
        {
            Object distanceTracker = readField(poiManager, "distanceTracker");
            Object levels = readField(distanceTracker, "levels");
            if (levels instanceof Map<?, ?> map)
            {
                map.clear();
            }
            else
            {
                Method clear = levels.getClass().getMethod("clear");
                clear.invoke(levels);
            }

            Method runAllUpdates = distanceTracker.getClass().getMethod("runAllUpdates");
            runAllUpdates.invoke(distanceTracker);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * 清除目标 chunk 的实体持久化缓存。
     * <p>
     * 玩家实体不能直接从 EntitySectionStorage 中移除；这里先丢弃非玩家实体，再仅移除已经空掉的 section。
     * 若玩家正站在目标 chunk，该 section 会保留玩家本体，但旧非玩家实体 UUID、待加载实体包、卸载队列等
     * 都会被清掉，避免 entities region 被删除后又从内存状态回写旧实体。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void purgeEntityRuntimeCache(ServerLevel level, ChunkPos pos)
    {
        try
        {
            Object entityManager = readField(level, "entityManager");
            Object sectionStorage = readField(entityManager, "sectionStorage");
            long chunkKey = pos.toLong();

            Set<UUID> removedEntityUuids = new LinkedHashSet<>();
            boolean hasPlayerSectionLeft = false;

            Method sectionPositionsMethod = sectionStorage.getClass().getDeclaredMethod("getExistingSectionPositionsInChunk", long.class);
            sectionPositionsMethod.setAccessible(true);
            Object sectionPositions = sectionPositionsMethod.invoke(sectionStorage, chunkKey);
            long[] sectionKeys = sectionPositions instanceof LongStream stream ? stream.toArray() : new long[0];

            Method getSectionMethod = sectionStorage.getClass().getDeclaredMethod("getSection", long.class);
            getSectionMethod.setAccessible(true);
            Method removeSectionMethod = sectionStorage.getClass().getDeclaredMethod("remove", long.class);
            removeSectionMethod.setAccessible(true);

            for (long sectionKey : sectionKeys)
            {
                Object section = getSectionMethod.invoke(sectionStorage, sectionKey);
                if (section == null) continue;

                List<Entity> oldEntities = collectSectionEntities(section);
                for (Entity entity : oldEntities)
                {
                    if (entity instanceof ServerPlayer)
                    {
                        hasPlayerSectionLeft = true;
                        continue;
                    }

                    removedEntityUuids.add(entity.getUUID());
                    try
                    {
                        entity.stopRiding();
                        entity.discard();
                    }
                    catch (Throwable ignored)
                    {
                    }
                }

                if (isEntitySectionEmptyOrPlayerOnly(section))
                {
                    // 没有玩家时直接删除空 section；有玩家时保留 section，避免把在线玩家从实体管理器中摘掉。
                    if (!containsServerPlayer(section))
                    {
                        removeSectionMethod.invoke(sectionStorage, sectionKey);
                    }
                }
            }

            removeKnownEntityUuids(entityManager, removedEntityUuids);
            removePendingEntityLoadsForChunk(entityManager, chunkKey);
            markEntityStorageChunkEmpty(entityManager, pos);
            removeLongFromFieldSet(entityManager, "chunksToUnload", chunkKey);

            if (!hasPlayerSectionLeft)
            {
                removeLongFromFieldMap(entityManager, "chunkVisibility", chunkKey);
                removeLongFromFieldMap(entityManager, "chunkLoadStatuses", chunkKey);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void markEntityStorageChunkEmpty(Object entityManager, ChunkPos pos)
    {
        try
        {
            Object permanentStorage = readField(entityManager, "permanentStorage");
            Method storeEntities = permanentStorage.getClass().getMethod("storeEntities", ChunkEntities.class);
            storeEntities.invoke(permanentStorage, new ChunkEntities(pos, List.of()));
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void storeNullChunk(Object worker, ChunkPos pos) throws ReflectiveOperationException
    {
        Method store = worker.getClass().getMethod("store", ChunkPos.class, CompoundTag.class);
        store.invoke(worker, pos, null);
    }

    private static void synchronizeIOWorker(Object worker, boolean flush) throws ReflectiveOperationException
    {
        Method synchronize = worker.getClass().getMethod("synchronize", boolean.class);
        Object future = synchronize.invoke(worker, flush);
        if (future instanceof CompletableFuture<?> completableFuture)
        {
            completableFuture.join();
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromCollectionField(Object target, String fieldName, java.util.function.Predicate<Object> predicate)
    {
        try
        {
            Object value = readField(target, fieldName);
            if (value instanceof Collection<?> collection)
            {
                ((Collection<Object>) collection).removeIf(predicate);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static boolean isBlockPosInChunk(BlockPos blockPos, ChunkPos chunkPos)
    {
        return blockPos != null && (blockPos.getX() >> 4) == chunkPos.x && (blockPos.getZ() >> 4) == chunkPos.z;
    }

    private static List<Entity> collectSectionEntities(Object section)
    {
        List<Entity> result = new ArrayList<>();
        try
        {
            Method method = section.getClass().getDeclaredMethod("getEntities");
            method.setAccessible(true);
            Object entities = method.invoke(section);
            if (entities instanceof Stream<?> stream)
            {
                stream.forEach(entity ->
                {
                    if (entity instanceof Entity minecraftEntity)
                    {
                        result.add(minecraftEntity);
                    }
                });
            }
        }
        catch (Throwable ignored)
        {
        }
        return result;
    }

    private static boolean containsServerPlayer(Object section)
    {
        for (Entity entity : collectSectionEntities(section))
        {
            if (entity instanceof ServerPlayer) return true;
        }
        return false;
    }

    private static boolean isEntitySectionEmptyOrPlayerOnly(Object section)
    {
        for (Entity entity : collectSectionEntities(section))
        {
            if (!(entity instanceof ServerPlayer)) return false;
        }
        return true;
    }

    /** 清理 StructureCheck 对旧 chunk 结构起点/引用结果的缓存，避免删除 terrain 后 locate/结构检查仍命中旧数据。 */
    @SuppressWarnings("rawtypes")
    private static void purgeStructureCheckCache(ServerLevel level, ChunkPos pos)
    {
        try
        {
            Object structureCheck = readField(level, "structureCheck");
            long chunkKey = pos.toLong();
            invokeLongRemove(readField(structureCheck, "loadedChunks"), chunkKey);

            Object featureChecks = readField(structureCheck, "featureChecks");
            if (featureChecks instanceof Map map)
            {
                for (Object perStructureCache : map.values())
                {
                    invokeLongRemove(perStructureCache, chunkKey);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeKnownEntityUuids(Object entityManager, Set<UUID> uuids)
    {
        if (uuids.isEmpty()) return;
        try
        {
            Object knownUuids = readField(entityManager, "knownUuids");
            if (knownUuids instanceof Set<?> set)
            {
                ((Set<UUID>) set).removeAll(uuids);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    @SuppressWarnings("unchecked")
    private static void removePendingEntityLoadsForChunk(Object entityManager, long chunkKey)
    {
        try
        {
            Object loadingInbox = readField(entityManager, "loadingInbox");
            if (loadingInbox instanceof Collection<?> collection)
            {
                ((Collection<Object>) collection).removeIf(pendingChunkEntities -> isChunkEntitiesForChunk(pendingChunkEntities, chunkKey));
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static boolean isChunkEntitiesForChunk(Object chunkEntities, long chunkKey)
    {
        try
        {
            Method getPos = chunkEntities.getClass().getDeclaredMethod("getPos");
            getPos.setAccessible(true);
            Object pos = getPos.invoke(chunkEntities);
            return pos instanceof ChunkPos chunkPos && chunkPos.toLong() == chunkKey;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void invalidateChunkHolderFutures(Object chunkMap, Object holder)
    {
        try
        {
            Object unloadedChunkFuture = readStaticField(holder.getClass(), "UNLOADED_CHUNK_FUTURE");
            Object futures = readField(holder, "futures");
            if (unloadedChunkFuture instanceof CompletableFuture<?> future && futures instanceof AtomicReferenceArray array)
            {
                for (int i = 0; i < array.length(); i++)
                {
                    array.set(i, future);
                }
            }
        }
        catch (Throwable ignored)
        {
        }

        Object unloadedLevelChunkFuture = null;
        try
        {
            unloadedLevelChunkFuture = readStaticField(holder.getClass(), "UNLOADED_LEVEL_CHUNK_FUTURE");
        }
        catch (Throwable ignored)
        {
        }

        if (unloadedLevelChunkFuture instanceof CompletableFuture<?> future)
        {
            writeField(holder, "fullChunkFuture", future);
            writeField(holder, "tickingChunkFuture", future);
            writeField(holder, "entityTickingChunkFuture", future);
        }

        writeField(holder, "chunkToSave", CompletableFuture.completedFuture(null));
        writeField(holder, "currentlyLoading", null);
        writeField(holder, "wasAccessibleSinceLastSave", false);
        try
        {
            Object pending = readField(holder, "pendingFullStateConfirmation");
            if (pending instanceof CompletableFuture<?> completableFuture)
            {
                completableFuture.cancel(false);
            }
            writeField(holder, "pendingFullStateConfirmation", CompletableFuture.completedFuture(null));
        }
        catch (Throwable ignored)
        {
        }

        writeField(holder, "oldTicketLevel", getChunkMaxLevel() + 1);
    }

    private static int getChunkMaxLevel()
    {
        try
        {
            Class<?> chunkLevel = Class.forName("net.minecraft.server.level.ChunkLevel");
            Field field = chunkLevel.getDeclaredField("MAX_LEVEL");
            field.setAccessible(true);
            return field.getInt(null);
        }
        catch (Throwable ignored)
        {
            return 33;
        }
    }

    private static Object readStaticField(Class<?> type, String name) throws ReflectiveOperationException
    {
        Field field = findField(type, name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException
    {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object readFieldQuietly(Object target, String name)
    {
        if (target == null) return null;
        try
        {
            return readField(target, name);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static int readIntFieldQuietly(Object target, String name, int fallback)
    {
        try
        {
            Object value = readField(target, name);
            return value instanceof Number number ? number.intValue() : fallback;
        }
        catch (Throwable ignored)
        {
            return fallback;
        }
    }

    private static void writeField(Object target, String name, Object value)
    {
        try
        {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException
    {
        Class<?> current = type;
        while (current != null)
        {
            try
            {
                return current.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void removeLongFromFieldSet(Object target, String fieldName, long value)
    {
        try
        {
            Object set = readField(target, fieldName);
            Method method = set.getClass().getMethod("remove", long.class);
            method.invoke(set, value);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void removeLongFromFieldMap(Object target, String fieldName, long value)
    {
        try
        {
            Object map = readField(target, fieldName);
            Method method = map.getClass().getMethod("remove", long.class);
            method.invoke(map, value);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void invokeLongRemove(Object target, long value)
    {
        if (target == null) return;
        try
        {
            Method method = target.getClass().getMethod("remove", long.class);
            method.invoke(target, value);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static Object getLongMapValue(Object target, long value)
    {
        if (target == null) return null;
        try
        {
            Method method = target.getClass().getMethod("get", long.class);
            return method.invoke(target, value);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static void markChunkReplaceable(ServerLevel level, ChunkPos pos)
    {
        try
        {
            Method method = level.getChunkSource().chunkMap.getClass().getDeclaredMethod("markPositionReplaceable", ChunkPos.class);
            method.setAccessible(true);
            method.invoke(level.getChunkSource().chunkMap, pos);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void clearServerChunkCache(ServerLevel level)
    {
        try
        {
            Method method = level.getChunkSource().getClass().getDeclaredMethod("clearCache");
            method.setAccessible(true);
            method.invoke(level.getChunkSource());
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void collectRuntimeChunkPositions(ServerLevel level, Set<ChunkPos> positions)
    {
        Object chunkMap = level.getChunkSource().chunkMap;
        collectChunkMapKeys(readFieldQuietly(chunkMap, "visibleChunkMap"), positions);
        collectChunkMapKeys(readFieldQuietly(chunkMap, "updatingChunkMap"), positions);
        collectChunkMapKeys(readFieldQuietly(chunkMap, "pendingUnloads"), positions);
    }

    private static void collectChunkMapKeys(Object map, Set<ChunkPos> positions)
    {
        if (map == null) return;
        try
        {
            Method keySetMethod = map.getClass().getMethod("keySet");
            Object keySet = keySetMethod.invoke(map);
            Method toLongArrayMethod = keySet.getClass().getMethod("toLongArray");
            Object array = toLongArrayMethod.invoke(keySet);
            if (array instanceof long[] keys)
            {
                for (long key : keys)
                {
                    positions.add(new ChunkPos(key));
                }
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void collectSavedChunkPositions(Path folder, Set<ChunkPos> positions)
    {
        if (!Files.isDirectory(folder)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "r.*.*.mca"))
        {
            for (Path regionFile : stream)
            {
                Matcher matcher = REGION_FILE_NAME.matcher(regionFile.getFileName().toString());
                if (!matcher.matches()) continue;

                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                collectSavedChunkPositions(regionFile, regionX, regionZ, positions);
            }
        }
        catch (IOException | NumberFormatException ignored)
        {
        }
    }

    private static void collectSavedChunkPositions(Path regionFile, int regionX, int regionZ, Set<ChunkPos> positions)
    {
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ))
        {
            ByteBuffer offsets = ByteBuffer.allocate(4096);
            int read = channel.read(offsets, 0L);
            if (read <= 0) return;

            offsets.flip();
            for (int index = 0; index < 1024 && offsets.remaining() >= Integer.BYTES; index++)
            {
                int offset = offsets.getInt();
                if (offset == 0) continue;

                int localX = index & 31;
                int localZ = index >> 5;
                positions.add(new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ));
            }
        }
        catch (IOException ignored)
        {
        }
    }

    private static boolean hasSavedChunkEntry(Path folder, ChunkPos pos)
    {
        if (!Files.isDirectory(folder)) return false;

        Path regionFile = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        if (!Files.isRegularFile(regionFile)) return false;

        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ))
        {
            ByteBuffer offset = ByteBuffer.allocate(Integer.BYTES);
            long headerOffset = ((long) pos.getRegionLocalX() + (long) pos.getRegionLocalZ() * 32L) * Integer.BYTES;
            int read = channel.read(offset, headerOffset);
            if (read < Integer.BYTES) return false;
            offset.flip();
            return offset.getInt() != 0;
        }
        catch (IOException ignored)
        {
            return false;
        }
    }

    private static DeleteSummary deleteMcaFiles(Path folder)
    {
        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        if (!Files.exists(folder)) return new DeleteSummary(0, 0);

        try
        {
            Files.walkFileTree(folder, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                {
                    if (file.getFileName().toString().endsWith(".mca"))
                    {
                        try
                        {
                            Files.deleteIfExists(file);
                            deleted.incrementAndGet();
                        }
                        catch (IOException ignored)
                        {
                            failed.incrementAndGet();
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException ignored)
        {
            failed.incrementAndGet();
        }
        return new DeleteSummary(deleted.get(), failed.get());
    }
}