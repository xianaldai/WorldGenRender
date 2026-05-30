# WorldGenRender 热重生成 API 文档

本文档面向希望复用 WorldGenRender 在线热重生成能力的其它 Forge 服务端模组开发者。

核心 API 位于 [`WorldGenRenderApi.java`](src/main/java/cn/xianaldai/worldgenrender/api/WorldGenRenderApi.java)。它把 WorldGenRender 内部对 Minecraft/Forge chunk holder、runtime cache、IOWorker、player ticket、客户端重同步等细节封装成稳定入口，避免其它模组直接依赖反射字段或复制不安全逻辑。

## 设计目标

WorldGenRender API 的目标不是替代 vanilla/Forge 世界生成流水线，而是给其它模组提供一层“兼容性收拢”工具：

- 仍使用原版/Forge FULL chunk generation future，保证真实反映其它模组的维度生成。
- 暴露热重生成各阶段，让其它模组可以插入自己的缓存清理、ticket 处理、诊断、过滤逻辑。
- 封装异步保存/IOWorker 屏障，降低旧 chunk 异步保存覆盖新生成结果的风险。
- 封装玩家保护、玩家 ticket 释放、恢复后客户端 chunk 重同步。
- 提供安全结果对象，不要求调用方捕获 WorldGenRender 内部反射异常。

## 最快接入：直接提交整维度热重生成任务

如果你的模组只想触发 WorldGenRender 默认完整流程，可以直接调用：

```java
WorldGenRenderApi.OperationResult<Void> result = WorldGenRenderApi.submitHotRegenerateDimension(source, level);
if (!result.success()) {
    LOGGER.warn("WorldGenRender submit failed: {}", result.message(), result.error());
}
```

适合场景：

- 你的模组不需要修改流程。
- 只希望复用 `/worldgenrender clearDimension <dimension> confirm` 的默认行为。
- 可接受 WorldGenRender 负责玩家保护、磁盘条目清空、runtime holder 失效、预热和客户端重同步。

注意：

- `source` 是命令源，通常来自你的命令或管理逻辑。
- `level` 是目标 [`ServerLevel`](src/main/java/cn/xianaldai/worldgenrender/api/WorldGenRenderApi.java)。

## 推荐接入：使用上下文 + 选项 + 回调

如果你的模组世界生成逻辑比较特殊，建议使用 [`WorldGenRenderApi.HotRegenerationOptions`](src/main/java/cn/xianaldai/worldgenrender/api/WorldGenRenderApi.java) 和 [`WorldGenRenderApi.HotRegenerationCallbacks`](src/main/java/cn/xianaldai/worldgenrender/api/WorldGenRenderApi.java)。

```java
WorldGenRenderApi.HotRegenerationOptions options = new WorldGenRenderApi.HotRegenerationOptions()
        .maxConcurrentChunkFutures(3)
        .preloadChunksPerTick(16)
        .visibleRadiusExtra(1)
        .clientResyncAttempts(2)
        .flushStorageBarriers(true)
        .callbacks(new WorldGenRenderApi.HotRegenerationCallbacks() {
            @Override
            public boolean shouldProcessChunk(WorldGenRenderApi.HotRegenerationContext context, ChunkPos pos) {
                // 可跳过你不想热重生成的 chunk。
                return true;
            }

            @Override
            public void beforeStage(WorldGenRenderApi.HotRegenerationContext context,
                                    WorldGenRenderApi.HotRegenerationStage stage,
                                    ChunkPos pos) {
                // 可在这里清理你自己模组的缓存、结构索引、运行时引用等。
            }

            @Override
            public void afterStorageBarrier(WorldGenRenderApi.HotRegenerationContext context) {
                // terrain/entities/POI IOWorker 同步后，可检查你的自定义存储是否也需要 flush。
            }
        });

WorldGenRenderApi.OperationResult<WorldGenRenderApi.HotRegenerationContext> created =
        WorldGenRenderApi.createHotRegenerationContext(level, options);

if (!created.success()) {
    LOGGER.warn("Cannot create hot regeneration context", created.error());
    return;
}

WorldGenRenderApi.HotRegenerationContext context = created.value();
```

## 运行时参数热调整

### 读取当前参数

```java
WorldGenRenderApi.HotRegenerationSettings settings = WorldGenRenderApi.getHotRegenerationSettings();
```

包含：

- `preloadChunksPerTick`：每 tick 最多新调度多少个玩家可见 chunk future。
- `maxConcurrentChunkFutures`：同时挂起的 FULL chunk future 上限，默认建议 `3`。
- `visibleRadiusExtra`：玩家视距外额外预热半径。
- `clientResyncAttempts`：恢复后客户端 chunk 重发尝试次数。
- `maxChunksPerTick`：删除、失效、缓存清理阶段每 tick 处理 chunk 数。

### 临时应用参数并恢复

```java
WorldGenRenderApi.HotRegenerationSettings old = WorldGenRenderApi.applyHotRegenerationSettings(options);
try {
    // 执行你的热重生成逻辑。
} finally {
    WorldGenRenderApi.restoreHotRegenerationSettings(old);
}
```

这不会写入 Forge 配置文件，只影响本次运行时。

## 分阶段 API

以下 API 允许其它模组自行编排完整流程。

### 1. 扫描目标 chunk

```java
List<ChunkPos> targets = WorldGenRenderApi.scanHotRegenerationTargetChunks(level);
List<ChunkPos> diskOnly = WorldGenRenderApi.scanSavedChunkEntries(level);
```

区别：

- `scanSavedChunkEntries` 只扫描磁盘 `region/entities/poi` 条目。
- `scanHotRegenerationTargetChunks` 扫描“磁盘条目 + 当前 runtime holder/pending unload”，用于解决第二次热重生成时新区块尚未落盘但仍驻留内存的问题。

### 2. 查询 chunk 是否驻留或 pending

```java
WorldGenRenderApi.OperationResult<Boolean> resident = WorldGenRenderApi.isChunkResidentOrPending(level, pos);
if (resident.success() && resident.value()) {
    // 该 chunk 仍在内存、holder 或 pending unload 中。
}
```

### 3. 准备 chunk

```java
WorldGenRenderApi.OperationResult<WorldGenRenderApi.ChunkStageResult> prepared =
        WorldGenRenderApi.prepareChunkForHotRegeneration(context, pos);
```

准备阶段会做：

- 释放 forced chunk ticket。
- 丢弃旧非玩家实体。
- 抑制旧 chunk 保存。
- 清理部分 runtime cache。
- 标记该位置可替换。

如果你的模组有额外 runtime cache，建议在 `beforeStage` 或 `afterStage` 中处理。

### 4. 删除保存条目

```java
WorldGenRenderApi.OperationResult<Integer> deleted = WorldGenRenderApi.deleteSavedChunkEntries(context, pos);
```

删除目标包括：

- terrain `region`。
- entity `entities`。
- POI `poi`。

返回值是删除/清空的条目数量。

### 5. 运行存储屏障

```java
WorldGenRenderApi.OperationResult<Void> barrier = WorldGenRenderApi.runHotRegenerationStorageBarrier(context);
```

这个阶段非常重要，尤其是异步保存不确定时。它会同步：

- terrain chunk IOWorker。
- entity storage IOWorker。
- POI IOWorker。
- distance manager / unload queue。

建议在以下位置调用：

- 删除写入完成后、失效 holder 前。
- 失效 holder 和 drain 阶段后、新 generation 前。
- 可见区块预热完成后、恢复玩家前。

如果你的模组有自定义异步存储，建议在 `beforeStorageBarrier` 或 `afterStorageBarrier` 中 flush 自己的 worker。

### 6. 失效运行时 holder/future/cache

```java
WorldGenRenderApi.OperationResult<Boolean> invalidated = WorldGenRenderApi.invalidateRuntimeChunk(context, pos);
```

这个阶段用于让旧 [`ChunkHolder`](src/main/java/cn/xianaldai/worldgenrender/api/WorldGenRenderApi.java)、FULL future、ticking future、last available chunk 等不再复用旧数据。

### 7. 兜底清理 runtime cache

```java
WorldGenRenderApi.OperationResult<Void> purged = WorldGenRenderApi.purgeRuntimeCaches(level, pos);
```

可在 drain 阶段重复调用，降低较晚完成的异步读取把旧实体/POI/结构缓存重新塞回内存的风险。

### 8. 推进 chunk 调度

```java
WorldGenRenderApi.OperationResult<Void> updates = WorldGenRenderApi.runChunkSchedulingUpdates(level);
```

用于推进 vanilla/Forge distance manager、unload queue、chunk map promote 等逻辑。

### 9. 调度 FULL chunk future

```java
WorldGenRenderApi.OperationResult<CompletableFuture<?>> scheduled =
        WorldGenRenderApi.scheduleHotRegenerationFullChunk(context, pos);

if (scheduled.success()) {
    CompletableFuture<?> future = scheduled.value();
}
```

这个 API 使用 vanilla/Forge FULL chunk future，不在主线程同步阻塞。适合有限并发预热。

检查是否 ready：

```java
WorldGenRenderApi.OperationResult<Boolean> ready = WorldGenRenderApi.isFullChunkReady(level, pos);
```

兼容调试时也可以同步预热：

```java
WorldGenRenderApi.OperationResult<Boolean> ready = WorldGenRenderApi.preloadFullChunkSynchronously(level, pos);
```

不建议大量 chunk 使用同步预热。

### 10. 保护与恢复玩家

保护玩家：

```java
WorldGenRenderApi.OperationResult<WorldGenOperations.HotPlayerGuard> guard =
        WorldGenRenderApi.protectPlayers(level);
```

如果你使用 `createHotRegenerationContext` 且 `options.protectPlayers(true)`，上下文会自动包含玩家保护器。

恢复玩家：

```java
WorldGenRenderApi.OperationResult<Void> restore = WorldGenRenderApi.beginRestorePlayers(context);
```

推进客户端重同步：

```java
while (true) {
    WorldGenRenderApi.OperationResult<Boolean> done = WorldGenRenderApi.tickClientResync(context.playerGuard());
    if (!done.success() || done.value()) break;
}
```

实际服务器 tick 中应分 tick 调用，不要在一个 tick 里死循环。

## 建议的手动完整流程

伪代码：

```java
WorldGenRenderApi.HotRegenerationOptions options = new WorldGenRenderApi.HotRegenerationOptions()
        .maxConcurrentChunkFutures(3)
        .flushStorageBarriers(true)
        .callbacks(myCallbacks);

WorldGenRenderApi.OperationResult<WorldGenRenderApi.HotRegenerationContext> created =
        WorldGenRenderApi.createHotRegenerationContext(level, options);
if (!created.success()) return;

WorldGenRenderApi.HotRegenerationContext context = created.value();
List<ChunkPos> resident = new ArrayList<>();

for (ChunkPos pos : context.targetChunks()) {
    WorldGenRenderApi.OperationResult<WorldGenRenderApi.ChunkStageResult> prepared =
            WorldGenRenderApi.prepareChunkForHotRegeneration(context, pos);
    if (prepared.success() && prepared.value().residentOrPending()) {
        resident.add(pos);
    }
    WorldGenRenderApi.deleteSavedChunkEntries(context, pos);
}

WorldGenRenderApi.runHotRegenerationStorageBarrier(context);

for (ChunkPos pos : resident) {
    WorldGenRenderApi.invalidateRuntimeChunk(context, pos);
}

WorldGenRenderApi.runHotRegenerationStorageBarrier(context);

// 分 tick、有限并发调度 context.visibleChunks() 的 FULL future。
for (ChunkPos pos : context.visibleChunks()) {
    WorldGenRenderApi.scheduleHotRegenerationFullChunk(context, pos);
}

WorldGenRenderApi.runHotRegenerationStorageBarrier(context);
WorldGenRenderApi.beginRestorePlayers(context);
```

真实实现中应按 tick 分批执行，避免一次性处理大量 chunk 卡死服务器。

## 诊断 API

```java
WorldGenRenderApi.OperationResult<WorldGenRenderApi.HotRegenerationDiagnostics> diag =
        WorldGenRenderApi.diagnoseHotRegeneration(level);

if (diag.success()) {
    LOGGER.info("dimension={}, targets={}, disk={}, visible={}, loadedVisible={}",
            diag.value().dimension(),
            diag.value().savedOrRuntimeChunks(),
            diag.value().diskSavedChunks(),
            diag.value().playerVisibleChunks(),
            diag.value().loadedOrPendingVisibleChunks());
}
```

解析维度存储路径：

```java
WorldGenRenderApi.OperationResult<Path> path = WorldGenRenderApi.getDimensionStoragePath(level);
```

## OperationResult 用法

所有安全封装 API 都返回 `OperationResult<T>`：

```java
if (!result.success()) {
    LOGGER.warn("WorldGenRender API failed at stage={}, chunk={}, message={}",
            result.stage(), result.chunk(), result.message(), result.error());
    return;
}
T value = result.value();
```

字段说明：

- `success`：是否成功。
- `value`：成功结果。
- `stage`：失败或成功发生在哪个热重生成阶段。
- `chunk`：相关 chunk，可能为 `null`。
- `message`：简短说明。
- `error`：异常，成功时为 `null`。

## 回调建议

如果你的模组有以下情况，建议实现 `HotRegenerationCallbacks`：

- 有自定义结构缓存、定位缓存、噪声缓存、biome cache。
- 有自己的异步 region/数据库/文件保存 worker。
- 维度生成依赖动态规则，需要在生成前后锁定配置。
- 有自己的 chunk ticket 或强加载系统。
- 有自定义实体管理器或 POI 类似系统。
- 希望跳过某些 chunk 或只重生成某些区域。

示例：

```java
HotRegenerationCallbacks callbacks = new HotRegenerationCallbacks() {
    @Override
    public void beforeStage(HotRegenerationContext context, HotRegenerationStage stage, ChunkPos pos) {
        if (stage == HotRegenerationStage.PREPARE_CHUNK) {
            MyModCache.invalidateChunk(context.level().dimension(), pos);
        }
    }

    @Override
    public void beforeStorageBarrier(HotRegenerationContext context) {
        MyAsyncStorage.flushAndWait(context.level().dimension());
    }

    @Override
    public boolean shouldProcessChunk(HotRegenerationContext context, ChunkPos pos) {
        return !MyProtectionApi.isProtected(context.level(), pos);
    }
};
```

## 并发预热建议

默认：

- `maxConcurrentChunkFutures = 3`
- `preloadChunksPerTick = 16`

建议：

- 自定义维度生成很重：并发保持 `2..3`。
- CPU 多且生成稳定：可试 `4..6`。
- 不建议直接设太高，因为 worldgen 会争抢主线程回调、light engine、IOWorker、其它模组资源。
- 不要从你自己的线程直接调用 Minecraft world state 修改 API；应使用 WorldGenRender 暴露的 future 调度或在主线程分 tick 执行。

## 注意事项

- API 不会阉割噪声算法或替代维度生成器。
- FULL chunk 生成仍走 vanilla/Forge pipeline，因此其它模组的真实维度生成逻辑会正常参与。
- 热重生成仍是高风险操作，尤其是大型维度、复杂实体系统、自定义存储系统。
- 如果其它模组自己持有旧 chunk、block entity、structure 或 entity 引用，必须通过回调自行清理。
- 异步保存无法完全由单一 Mod 绝对保证顺序；WorldGenRender 提供 terrain/entities/POI 屏障，外部自定义存储应在回调中同步自己的 worker。

## 构建验证

当前 API 已通过 [`gradlew.bat`](gradlew.bat) `build` 验证。