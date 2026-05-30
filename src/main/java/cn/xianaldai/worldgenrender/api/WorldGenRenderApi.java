package cn.xianaldai.worldgenrender.api;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.WorldGenRenderTaskManager;
import cn.xianaldai.worldgenrender.task.worldgen.WorldGenClearDimensionTask;
import cn.xianaldai.worldgenrender.util.WorldGenOperations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 给其它模组/脚本调试与复用 WorldGenRender 热重生成流程的API。
 */
public final class WorldGenRenderApi
{
    private WorldGenRenderApi() {}

    /** 当前热重生成运行时参数快照。 */
    public record HotRegenerationSettings(
            int preloadChunksPerTick,
            int maxConcurrentChunkFutures,
            int visibleRadiusExtra,
            int clientResyncAttempts,
            int maxChunksPerTick) {}

    /** 热重生成阶段枚举，供回调/日志/诊断统一使用。 */
    public enum HotRegenerationStage
    {
        SCAN,
        PROTECT_PLAYERS,
        PREPARE_CHUNK,
        DELETE_STORAGE,
        INVALIDATE_RUNTIME,
        STORAGE_BARRIER,
        PRELOAD_FULL_CHUNK,
        RESTORE_PLAYERS,
        CLIENT_RESYNC,
        CUSTOM_COMPATIBILITY,
        DIAGNOSTIC
    }

    /** 安全封装结果：外部模组可按 success/error 判断，而不必捕获内部反射异常。 */
    public record OperationResult<T>(
            boolean success,
            T value,
            HotRegenerationStage stage,
            ChunkPos chunk,
            String message,
            Throwable error)
    {
        public static <T> OperationResult<T> ok(HotRegenerationStage stage, ChunkPos chunk, T value, String message)
        {
            return new OperationResult<>(true, value, stage, chunk, message, null);
        }

        public static <T> OperationResult<T> fail(HotRegenerationStage stage, ChunkPos chunk, String message, Throwable error)
        {
            return new OperationResult<>(false, null, stage, chunk, message, error);
        }
    }

    /** 单个 chunk 热清理/重生成阶段结果。 */
    public record ChunkStageResult(
            ChunkPos pos,
            boolean residentOrPending,
            boolean unforced,
            int discardedEntities,
            int deletedEntries,
            boolean invalidated,
            boolean fullChunkReady) {}

    /** 维度热重生成诊断快照。 */
    public record HotRegenerationDiagnostics(
            String dimension,
            int savedOrRuntimeChunks,
            int diskSavedChunks,
            int playerVisibleChunks,
            int loadedOrPendingVisibleChunks,
            HotRegenerationSettings settings) {}

    /** 可插拔兼容选项，供其它模组插入自己特殊的世界生成/缓存逻辑。 */
    public static final class HotRegenerationOptions
    {
        private int preloadChunksPerTick = Config.hotRegenerationPreloadChunksPerTick;
        private int maxConcurrentChunkFutures = Config.hotRegenerationMaxConcurrentChunkFutures;
        private int visibleRadiusExtra = Config.hotRegenerationVisibleRadiusExtra;
        private int clientResyncAttempts = Config.hotRegenerationClientResyncAttempts;
        private int maxChunksPerTick = Config.maxChunksPerTick;
        private boolean protectPlayers = true;
        private boolean discardNonPlayerEntities = true;
        private boolean deleteTerrainStorage = true;
        private boolean invalidateRuntimeHolders = true;
        private boolean flushStorageBarriers = true;
        private boolean preloadVisibleChunks = true;
        private GameType restoreGameType = GameType.CREATIVE;
        private Predicate<Entity> entityDiscardPredicate = entity -> !(entity instanceof net.minecraft.server.level.ServerPlayer);
        private HotRegenerationCallbacks callbacks = HotRegenerationCallbacks.noop();

        public int preloadChunksPerTick() { return preloadChunksPerTick; }
        public int maxConcurrentChunkFutures() { return maxConcurrentChunkFutures; }
        public int visibleRadiusExtra() { return visibleRadiusExtra; }
        public int clientResyncAttempts() { return clientResyncAttempts; }
        public int maxChunksPerTick() { return maxChunksPerTick; }
        public boolean protectPlayers() { return protectPlayers; }
        public boolean discardNonPlayerEntities() { return discardNonPlayerEntities; }
        public boolean deleteTerrainStorage() { return deleteTerrainStorage; }
        public boolean invalidateRuntimeHolders() { return invalidateRuntimeHolders; }
        public boolean flushStorageBarriers() { return flushStorageBarriers; }
        public boolean preloadVisibleChunks() { return preloadVisibleChunks; }
        public GameType restoreGameType() { return restoreGameType; }
        public Predicate<Entity> entityDiscardPredicate() { return entityDiscardPredicate; }
        public HotRegenerationCallbacks callbacks() { return callbacks; }

        public HotRegenerationOptions preloadChunksPerTick(int value) { this.preloadChunksPerTick = clamp(value, 1, 256); return this; }
        public HotRegenerationOptions maxConcurrentChunkFutures(int value) { this.maxConcurrentChunkFutures = clamp(value, 1, 32); return this; }
        public HotRegenerationOptions visibleRadiusExtra(int value) { this.visibleRadiusExtra = clamp(value, 0, 32); return this; }
        public HotRegenerationOptions clientResyncAttempts(int value) { this.clientResyncAttempts = clamp(value, 1, 16); return this; }
        public HotRegenerationOptions maxChunksPerTick(int value) { this.maxChunksPerTick = clamp(value, 1, 4096); return this; }
        public HotRegenerationOptions protectPlayers(boolean value) { this.protectPlayers = value; return this; }
        public HotRegenerationOptions discardNonPlayerEntities(boolean value) { this.discardNonPlayerEntities = value; return this; }
        public HotRegenerationOptions deleteTerrainStorage(boolean value) { this.deleteTerrainStorage = value; return this; }
        public HotRegenerationOptions invalidateRuntimeHolders(boolean value) { this.invalidateRuntimeHolders = value; return this; }
        public HotRegenerationOptions flushStorageBarriers(boolean value) { this.flushStorageBarriers = value; return this; }
        public HotRegenerationOptions preloadVisibleChunks(boolean value) { this.preloadVisibleChunks = value; return this; }
        public HotRegenerationOptions restoreGameType(GameType value) { this.restoreGameType = value; return this; }
        public HotRegenerationOptions entityDiscardPredicate(Predicate<Entity> value) { this.entityDiscardPredicate = Objects.requireNonNull(value); return this; }
        public HotRegenerationOptions callbacks(HotRegenerationCallbacks value) { this.callbacks = Objects.requireNonNull(value); return this; }
    }

    /** 热重生成上下文，外部模组可持有它手动编排阶段。 */
    public record HotRegenerationContext(
            ServerLevel level,
            HotRegenerationOptions options,
            WorldGenOperations.HotPlayerGuard playerGuard,
            List<ChunkPos> targetChunks,
            List<ChunkPos> visibleChunks) {}

    /** 兼容回调：其它模组可以在每个阶段清自己的缓存、补 ticket、注入诊断或拒绝某些 chunk。 */
    public interface HotRegenerationCallbacks
    {
        default boolean shouldProcessChunk(HotRegenerationContext context, ChunkPos pos) { return true; }
        default void beforeStage(HotRegenerationContext context, HotRegenerationStage stage, ChunkPos pos) {}
        default void afterStage(HotRegenerationContext context, HotRegenerationStage stage, ChunkPos pos, OperationResult<?> result) {}
        default void beforeStorageBarrier(HotRegenerationContext context) {}
        default void afterStorageBarrier(HotRegenerationContext context) {}
        default void beforeVisiblePreload(HotRegenerationContext context, ChunkPos pos) {}
        default void afterVisiblePreload(HotRegenerationContext context, ChunkPos pos, CompletableFuture<?> future) {}
        default void beforePlayerRestore(HotRegenerationContext context) {}
        default void afterPlayerRestore(HotRegenerationContext context) {}
        default void collectDiagnostics(HotRegenerationContext context, Consumer<Component> output) {}

        static HotRegenerationCallbacks noop()
        {
            return new HotRegenerationCallbacks() {};
        }
    }

    /** 获取当前热重生成调试参数。 */
    public static HotRegenerationSettings getHotRegenerationSettings()
    {
        return new HotRegenerationSettings(
                Config.hotRegenerationPreloadChunksPerTick,
                Config.hotRegenerationMaxConcurrentChunkFutures,
                Config.hotRegenerationVisibleRadiusExtra,
                Config.hotRegenerationClientResyncAttempts,
                Config.maxChunksPerTick);
    }

    /** 热重载常用热重生成调试参数，不写入配置文件。 */
    public static void setHotRegenerationSettings(
            int preloadChunksPerTick,
            int maxConcurrentChunkFutures,
            int visibleRadiusExtra,
            int clientResyncAttempts,
            int maxChunksPerTick)
    {
        Config.hotRegenerationPreloadChunksPerTick = clamp(preloadChunksPerTick, 1, 256);
        Config.hotRegenerationMaxConcurrentChunkFutures = clamp(maxConcurrentChunkFutures, 1, 32);
        Config.hotRegenerationVisibleRadiusExtra = clamp(visibleRadiusExtra, 0, 8);
        Config.hotRegenerationClientResyncAttempts = clamp(clientResyncAttempts, 1, 8);
        Config.maxChunksPerTick = clamp(maxChunksPerTick, 1, 256);
    }

    /** 按选项临时应用运行时参数，返回旧参数，方便其它模组 try/finally 恢复。 */
    public static HotRegenerationSettings applyHotRegenerationSettings(HotRegenerationOptions options)
    {
        HotRegenerationSettings previous = getHotRegenerationSettings();
        Config.hotRegenerationPreloadChunksPerTick = clamp(options.preloadChunksPerTick(), 1, 256);
        Config.hotRegenerationMaxConcurrentChunkFutures = clamp(options.maxConcurrentChunkFutures(), 1, 32);
        Config.hotRegenerationVisibleRadiusExtra = clamp(options.visibleRadiusExtra(), 0, 32);
        Config.hotRegenerationClientResyncAttempts = clamp(options.clientResyncAttempts(), 1, 16);
        Config.maxChunksPerTick = clamp(options.maxChunksPerTick(), 1, 4096);
        return previous;
    }

    /** 恢复运行时参数快照。 */
    public static void restoreHotRegenerationSettings(HotRegenerationSettings settings)
    {
        Config.hotRegenerationPreloadChunksPerTick = settings.preloadChunksPerTick();
        Config.hotRegenerationMaxConcurrentChunkFutures = settings.maxConcurrentChunkFutures();
        Config.hotRegenerationVisibleRadiusExtra = settings.visibleRadiusExtra();
        Config.hotRegenerationClientResyncAttempts = settings.clientResyncAttempts();
        Config.maxChunksPerTick = settings.maxChunksPerTick();
    }

    /** 提交整维度在线热重生成任务，供其它服务端模组复用默认任务实现。 */
    public static OperationResult<Void> submitHotRegenerateDimension(CommandSourceStack source, ServerLevel level)
    {
        try
        {
            WorldGenRenderTaskManager.submit(new WorldGenClearDimensionTask(source, level));
            return OperationResult.ok(HotRegenerationStage.SCAN, null, null, "submitted");
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(HotRegenerationStage.SCAN, null, "submit failed", throwable);
        }
    }

    /** 创建上下文：扫描目标 chunk、收集可见预热 chunk，并按需保护玩家。 */
    public static OperationResult<HotRegenerationContext> createHotRegenerationContext(ServerLevel level, HotRegenerationOptions options)
    {
        try
        {
            HotRegenerationOptions safeOptions = options == null ? new HotRegenerationOptions() : options;
            WorldGenOperations.HotPlayerGuard guard = safeOptions.protectPlayers() ? WorldGenOperations.protectPlayersForHotRegeneration(level) : null;
            List<ChunkPos> targetChunks = WorldGenOperations.listHotRegenerationChunkPositions(level);
            List<ChunkPos> visibleChunks = WorldGenOperations.collectPlayerVisibleChunkPositions(level, safeOptions.visibleRadiusExtra());
            HotRegenerationContext context = new HotRegenerationContext(level, safeOptions, guard, targetChunks, visibleChunks);
            return OperationResult.ok(HotRegenerationStage.SCAN, null, context, "context created");
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(HotRegenerationStage.SCAN, null, "context creation failed", throwable);
        }
    }

    /** 收集当前目标维度玩家恢复前会预热的可见 chunk 坐标。 */
    public static List<ChunkPos> collectHotRegenerationVisibleChunks(ServerLevel level)
    {
        return WorldGenOperations.collectPlayerVisibleChunkPositions(level, Config.hotRegenerationVisibleRadiusExtra);
    }

    /** 扫描整维度热重生成目标 chunk：磁盘条目 + 当前运行时 holder/pending unload。 */
    public static List<ChunkPos> scanHotRegenerationTargetChunks(ServerLevel level)
    {
        return WorldGenOperations.listHotRegenerationChunkPositions(level);
    }

    /** 仅扫描磁盘保存条目。 */
    public static List<ChunkPos> scanSavedChunkEntries(ServerLevel level)
    {
        return WorldGenOperations.listSavedChunkPositions(level);
    }

    /** 查询 chunk 是否仍在内存/holder/pending unload 等运行时结构中。 */
    public static OperationResult<Boolean> isChunkResidentOrPending(ServerLevel level, ChunkPos pos)
    {
        return safe(HotRegenerationStage.DIAGNOSTIC, pos, () -> WorldGenOperations.isChunkLoadedOrPending(level, pos));
    }

    /** 执行单个 chunk 的准备阶段：释放 forced ticket、丢弃旧实体、抑制旧保存、标记可替换。 */
    public static OperationResult<ChunkStageResult> prepareChunkForHotRegeneration(HotRegenerationContext context, ChunkPos pos)
    {
        try
        {
            if (!context.options().callbacks().shouldProcessChunk(context, pos))
            {
                return OperationResult.ok(HotRegenerationStage.PREPARE_CHUNK, pos, new ChunkStageResult(pos, false, false, 0, 0, false, false), "skipped by callback");
            }
            context.options().callbacks().beforeStage(context, HotRegenerationStage.PREPARE_CHUNK, pos);
            boolean unforced = WorldGenOperations.unforceChunkIfNeeded(context.level(), pos);
            int discarded = context.options().discardNonPlayerEntities() ? WorldGenOperations.discardNonPlayerEntitiesInChunk(context.level(), pos) : 0;
            boolean resident = WorldGenOperations.prepareChunkForHotRegeneration(context.level(), pos, context.playerGuard());
            ChunkStageResult value = new ChunkStageResult(pos, resident, unforced, discarded, 0, false, false);
            OperationResult<ChunkStageResult> result = OperationResult.ok(HotRegenerationStage.PREPARE_CHUNK, pos, value, "prepared");
            context.options().callbacks().afterStage(context, HotRegenerationStage.PREPARE_CHUNK, pos, result);
            return result;
        }
        catch (Throwable throwable)
        {
            OperationResult<ChunkStageResult> result = OperationResult.fail(HotRegenerationStage.PREPARE_CHUNK, pos, "prepare failed", throwable);
            context.options().callbacks().afterStage(context, HotRegenerationStage.PREPARE_CHUNK, pos, result);
            return result;
        }
    }

    /** 清空单个 chunk 的 terrain/entities/POI 存储条目。 */
    public static OperationResult<Integer> deleteSavedChunkEntries(ServerLevel level, ChunkPos pos)
    {
        return safe(HotRegenerationStage.DELETE_STORAGE, pos, () -> WorldGenOperations.deleteSavedChunk(level, pos));
    }

    /** 删除阶段上下文版本，带回调。 */
    public static OperationResult<Integer> deleteSavedChunkEntries(HotRegenerationContext context, ChunkPos pos)
    {
        try
        {
            context.options().callbacks().beforeStage(context, HotRegenerationStage.DELETE_STORAGE, pos);
            int deleted = context.options().deleteTerrainStorage() ? WorldGenOperations.deleteSavedChunk(context.level(), pos) : 0;
            OperationResult<Integer> result = OperationResult.ok(HotRegenerationStage.DELETE_STORAGE, pos, deleted, "deleted entries=" + deleted);
            context.options().callbacks().afterStage(context, HotRegenerationStage.DELETE_STORAGE, pos, result);
            return result;
        }
        catch (Throwable throwable)
        {
            OperationResult<Integer> result = OperationResult.fail(HotRegenerationStage.DELETE_STORAGE, pos, "delete failed", throwable);
            context.options().callbacks().afterStage(context, HotRegenerationStage.DELETE_STORAGE, pos, result);
            return result;
        }
    }

    /** 强制失效单个 chunk 的旧运行时 holder/future/cache。 */
    public static OperationResult<Boolean> invalidateRuntimeChunk(ServerLevel level, ChunkPos pos)
    {
        return safe(HotRegenerationStage.INVALIDATE_RUNTIME, pos, () -> WorldGenOperations.invalidatePreparedChunkForHotRegeneration(level, pos));
    }

    /** 失效阶段上下文版本，带回调。 */
    public static OperationResult<Boolean> invalidateRuntimeChunk(HotRegenerationContext context, ChunkPos pos)
    {
        try
        {
            context.options().callbacks().beforeStage(context, HotRegenerationStage.INVALIDATE_RUNTIME, pos);
            boolean invalidated = context.options().invalidateRuntimeHolders() && WorldGenOperations.invalidatePreparedChunkForHotRegeneration(context.level(), pos);
            OperationResult<Boolean> result = OperationResult.ok(HotRegenerationStage.INVALIDATE_RUNTIME, pos, invalidated, "invalidated=" + invalidated);
            context.options().callbacks().afterStage(context, HotRegenerationStage.INVALIDATE_RUNTIME, pos, result);
            return result;
        }
        catch (Throwable throwable)
        {
            OperationResult<Boolean> result = OperationResult.fail(HotRegenerationStage.INVALIDATE_RUNTIME, pos, "invalidate failed", throwable);
            context.options().callbacks().afterStage(context, HotRegenerationStage.INVALIDATE_RUNTIME, pos, result);
            return result;
        }
    }

    /** 清理单个 chunk 的运行时缓存兜底入口。 */
    public static OperationResult<Void> purgeRuntimeCaches(ServerLevel level, ChunkPos pos)
    {
        return safeVoid(HotRegenerationStage.CUSTOM_COMPATIBILITY, pos, () -> WorldGenOperations.purgeRuntimeCachesForHotRegeneration(level, pos));
    }

    /** 推进 distance/unload 队列。 */
    public static OperationResult<Void> runChunkSchedulingUpdates(ServerLevel level)
    {
        return safeVoid(HotRegenerationStage.CUSTOM_COMPATIBILITY, null, () -> WorldGenOperations.runChunkDistanceUpdates(level));
    }

    /** 执行热重生成存储屏障，等待 terrain/entities/POI IOWorker 已提交写入。 */
    public static OperationResult<Void> runHotRegenerationStorageBarrier(ServerLevel level)
    {
        return safeVoid(HotRegenerationStage.STORAGE_BARRIER, null, () -> WorldGenOperations.hotRegenerationStorageBarrier(level));
    }

    /** 上下文版本存储屏障，带回调。 */
    public static OperationResult<Void> runHotRegenerationStorageBarrier(HotRegenerationContext context)
    {
        try
        {
            context.options().callbacks().beforeStorageBarrier(context);
            if (context.options().flushStorageBarriers())
            {
                WorldGenOperations.hotRegenerationStorageBarrier(context.level());
            }
            OperationResult<Void> result = OperationResult.ok(HotRegenerationStage.STORAGE_BARRIER, null, null, "barrier completed");
            context.options().callbacks().afterStorageBarrier(context);
            return result;
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(HotRegenerationStage.STORAGE_BARRIER, null, "barrier failed", throwable);
        }
    }

    /** 调度单个 FULL chunk 的 vanilla/Forge future，用于其它模组做实验性预热。 */
    public static OperationResult<CompletableFuture<?>> scheduleHotRegenerationFullChunk(ServerLevel level, ChunkPos pos)
    {
        return safe(HotRegenerationStage.PRELOAD_FULL_CHUNK, pos, () -> WorldGenOperations.scheduleFullChunkForHotRegeneration(level, pos));
    }

    /** 上下文版本 FULL chunk 调度，带回调。 */
    public static OperationResult<CompletableFuture<?>> scheduleHotRegenerationFullChunk(HotRegenerationContext context, ChunkPos pos)
    {
        try
        {
            context.options().callbacks().beforeVisiblePreload(context, pos);
            CompletableFuture<?> future = context.options().preloadVisibleChunks()
                    ? WorldGenOperations.scheduleFullChunkForHotRegeneration(context.level(), pos)
                    : CompletableFuture.completedFuture(null);
            OperationResult<CompletableFuture<?>> result = OperationResult.ok(HotRegenerationStage.PRELOAD_FULL_CHUNK, pos, future, "scheduled");
            context.options().callbacks().afterVisiblePreload(context, pos, future);
            return result;
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(HotRegenerationStage.PRELOAD_FULL_CHUNK, pos, "schedule failed", throwable);
        }
    }

    /** 同步预热单个 FULL chunk，兼容旧式调试场景。 */
    public static OperationResult<Boolean> preloadFullChunkSynchronously(ServerLevel level, ChunkPos pos)
    {
        return safe(HotRegenerationStage.PRELOAD_FULL_CHUNK, pos, () -> WorldGenOperations.preloadFullChunkForHotRegeneration(level, pos));
    }

    /** 查询 FULL/ticking chunk 是否 ready，可用于外部模组的 future 完成后确认。 */
    public static OperationResult<Boolean> isFullChunkReady(ServerLevel level, ChunkPos pos)
    {
        return safe(HotRegenerationStage.PRELOAD_FULL_CHUNK, pos, () -> WorldGenOperations.isFullChunkReadyForHotRegeneration(level, pos));
    }

    /** 保护目标维度玩家，释放玩家 ticket。 */
    public static OperationResult<WorldGenOperations.HotPlayerGuard> protectPlayers(ServerLevel level)
    {
        return safe(HotRegenerationStage.PROTECT_PLAYERS, null, () -> WorldGenOperations.protectPlayersForHotRegeneration(level));
    }

    /** 按上下文恢复玩家并开始客户端重同步。 */
    public static OperationResult<Void> beginRestorePlayers(HotRegenerationContext context)
    {
        try
        {
            context.options().callbacks().beforePlayerRestore(context);
            if (context.playerGuard() != null)
            {
                context.playerGuard().beginRestore(context.options().restoreGameType());
            }
            context.options().callbacks().afterPlayerRestore(context);
            return OperationResult.ok(HotRegenerationStage.RESTORE_PLAYERS, null, null, "restore started");
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(HotRegenerationStage.RESTORE_PLAYERS, null, "restore failed", throwable);
        }
    }

    /** 推进玩家客户端 chunk 重同步。返回 true 表示完成。 */
    public static OperationResult<Boolean> tickClientResync(WorldGenOperations.HotPlayerGuard guard)
    {
        return safe(HotRegenerationStage.CLIENT_RESYNC, null, guard::tickClientResync);
    }

    /** 构建维度热重生成诊断快照。 */
    public static OperationResult<HotRegenerationDiagnostics> diagnoseHotRegeneration(ServerLevel level)
    {
        try
        {
            List<ChunkPos> hotTargets = WorldGenOperations.listHotRegenerationChunkPositions(level);
            List<ChunkPos> disk = WorldGenOperations.listSavedChunkPositions(level);
            List<ChunkPos> visible = WorldGenOperations.collectPlayerVisibleChunkPositions(level, Config.hotRegenerationVisibleRadiusExtra);
            int loadedVisible = 0;
            for (ChunkPos pos : visible)
            {
                if (WorldGenOperations.isChunkLoadedOrPending(level, pos)) loadedVisible++;
            }
            HotRegenerationDiagnostics diagnostics = new HotRegenerationDiagnostics(
                    level.dimension().location().toString(),
                    hotTargets.size(),
                    disk.size(),
                    visible.size(),
                    loadedVisible,
                    getHotRegenerationSettings());
            return OperationResult.ok(HotRegenerationStage.DIAGNOSTIC, null, diagnostics, "diagnostics collected");
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(HotRegenerationStage.DIAGNOSTIC, null, "diagnostics failed", throwable);
        }
    }

    /** 解析维度路径，便于其它模组诊断自定义维度存储布局。 */
    public static OperationResult<Path> getDimensionStoragePath(ServerLevel level)
    {
        return safe(HotRegenerationStage.DIAGNOSTIC, null, () -> WorldGenOperations.getDimensionPath(level));
    }

    /** 把回调诊断输出为 Component 列表，便于命令直接打印。 */
    public static List<Component> collectCallbackDiagnostics(HotRegenerationContext context)
    {
        List<Component> output = new ArrayList<>();
        context.options().callbacks().collectDiagnostics(context, output::add);
        return output;
    }

    private static <T> OperationResult<T> safe(HotRegenerationStage stage, ChunkPos pos, ThrowingSupplier<T> supplier)
    {
        try
        {
            return OperationResult.ok(stage, pos, supplier.get(), "ok");
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(stage, pos, "failed", throwable);
        }
    }

    private static OperationResult<Void> safeVoid(HotRegenerationStage stage, ChunkPos pos, ThrowingRunnable runnable)
    {
        try
        {
            runnable.run();
            return OperationResult.ok(stage, pos, null, "ok");
        }
        catch (Throwable throwable)
        {
            return OperationResult.fail(stage, pos, "failed", throwable);
        }
    }

    private interface ThrowingSupplier<T>
    {
        T get() throws Throwable;
    }

    private interface ThrowingRunnable
    {
        void run() throws Throwable;
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
