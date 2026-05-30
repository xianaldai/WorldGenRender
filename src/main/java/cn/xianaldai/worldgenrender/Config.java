package cn.xianaldai.worldgenrender;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * WorldGenRender 通用配置类。
 * <p>
 * 控制命令权限、任务分片预算与 PNG 输出目录。
 *
 * @author xianaldai
 */
@Mod.EventBusSubscriber(modid = WorldGenRender.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue COMMAND_PERMISSION_LEVEL = BUILDER
            .comment("执行 /worldgenrender 命令所需的权限等级。原版服务器管理员通常为 2 级或更高。")
            .defineInRange("commandPermissionLevel", 2, 0, 4);

    private static final ForgeConfigSpec.IntValue MAX_CHUNKS_PER_TICK = BUILDER
            .comment("渲染、预加载、重置已保存区块等任务每个服务器 tick 最多处理的区块数量。数值越高完成越快，但越可能造成卡顿。")
            .defineInRange("maxChunksPerTick", 8, 1, 256);

    private static final ForgeConfigSpec.IntValue RENDER_PIXELS_PER_BLOCK = BUILDER
            .comment("渲染 PNG 时每个 Minecraft 方块对应的像素边长。例如 16 表示 1 个方块渲染为 16x16 像素。范围很大，调高会迅速增加图片尺寸和内存占用。")
            .defineInRange("renderPixelsPerBlock", 16, 1, 4096);

    private static final ForgeConfigSpec.EnumValue<Render3dResolution> RENDER_3D_RESOLUTION = BUILDER
            .comment("3D 正交渲染的单位清晰度预设，不限制最终图片总尺寸。P720 到 P4320 分别表示每个方块/单位投影使用更高像素密度；渲染范围越大，最终 PNG 尺寸仍会按范围扩展。")
            .defineEnum("render3dResolution", Render3dResolution.P1080);

    private static final ForgeConfigSpec.EnumValue<Render3dOrientation> RENDER_3D_ORIENTATION = BUILDER
            .comment("3D 正交捕获默认取向。NE/NW/SW/SE 对应从四个水平斜向观察选区；游戏内命令可临时覆盖。")
            .defineEnum("render3dOrientation", Render3dOrientation.NE);

    private static final ForgeConfigSpec.IntValue RENDER_3D_PIXELS_PER_BLOCK = BUILDER
            .comment("3D 正交渲染的自定义基础像素宽度。render3dResolution 会给出 720P-8K 档位的默认单位密度；这里作为向后兼容和下限参考。")
            .defineInRange("render3dPixelsPerBlock", 32, 2, 256);

    private static final ForgeConfigSpec.IntValue RENDER_3D_VERTICAL_SCALE = BUILDER
            .comment("3D 等距地形 PNG 的高度缩放，每上升 1 个方块在图片中额外上移多少像素。")
            .defineInRange("render3dVerticalScale", 2, 1, 32);

    private static final ForgeConfigSpec.BooleanValue OBJ_EXPORT_INCLUDE_INTERIOR_FACES = BUILDER
            .comment("导出 OBJ 时是否写出完全被相邻方块遮挡的内部面。关闭时仍会遍历并包含地下/非表面方块，但只输出外露面，文件体积小很多；开启会为每个非空气方块输出六面，文件可能极大。")
            .define("objExportIncludeInteriorFaces", false);

    private static final ForgeConfigSpec.BooleanValue RENDER_USE_BLOCK_TEXTURES = BUILDER
            .comment("渲染 PNG 时是否优先尝试读取方块材质，而不是只使用地图纯色。服务端无法使用完整客户端模型渲染器，因此这是基于 assets/textures/block 的顶面材质近似；找不到材质时会回退地图色。")
            .define("renderUseBlockTextures", true);

    private static final ForgeConfigSpec.BooleanValue RENDER_TEXTURE_FALLBACK_TO_MAP_COLOR = BUILDER
            .comment("材质像素透明、材质缺失或服务端无法读取材质时，是否回退为方块地图颜色。建议保持开启。")
            .define("renderTextureFallbackToMapColor", true);

    private static final ForgeConfigSpec.IntValue HOT_REGENERATION_PRELOAD_CHUNKS_PER_TICK = BUILDER
            .comment("在线热重生成维度时，恢复玩家前每个 tick 最多新调度多少个玩家可见区块 FULL 生成 future。仍走原版/Forge 真实区块生成流水线。")
            .defineInRange("hotRegenerationPreloadChunksPerTick", 16, 1, 256);

    private static final ForgeConfigSpec.IntValue HOT_REGENERATION_MAX_CONCURRENT_CHUNK_FUTURES = BUILDER
            .comment("在线热重生成维度时，同时挂起的 FULL 区块生成 future 最大数量。默认 3，可利用多核心并行生成，同时避免压垮服务器、光照线程和其它模组。")
            .defineInRange("hotRegenerationMaxConcurrentChunkFutures", 3, 1, 32);

    private static final ForgeConfigSpec.IntValue HOT_REGENERATION_VISIBLE_RADIUS_EXTRA = BUILDER
            .comment("在线热重生成维度时，恢复玩家前在原版视距外额外预生成的区块半径。提高可减少玩家恢复后的空白等待，但会增加预热耗时。")
            .defineInRange("hotRegenerationVisibleRadiusExtra", 1, 0, 8);

    private static final ForgeConfigSpec.IntValue HOT_REGENERATION_CLIENT_RESYNC_ATTEMPTS = BUILDER
            .comment("在线热重生成维度后，延迟向客户端重发玩家可见区块的最大尝试次数。只会直接发送已经 ready 的完整区块包。")
            .defineInRange("hotRegenerationClientResyncAttempts", 2, 1, 8);

    private static final ForgeConfigSpec.IntValue MAX_BLOCK_CHANGES_PER_TICK = BUILDER
            .comment("清理任务每个服务器 tick 最多访问的方块位置数量。数值越高完成越快，但越可能造成服务器卡顿。")
            .defineInRange("maxBlockChangesPerTick", 20000, 1024, 500000);

    private static final ForgeConfigSpec.IntValue MAX_RENDER_CHUNKS = BUILDER
            .comment("单次渲染任务允许处理的区块数量硬上限，用于防止误操作提交过大的渲染范围。")
            .defineInRange("maxRenderChunks", 4096, 1, 262144);

    private static final ForgeConfigSpec.IntValue MAX_WORLDGEN_CHUNKS = BUILDER
            .comment("单次 clear、preload、resetSaved 等世界生成/清理任务允许处理的区块数量硬上限。")
            .defineInRange("maxWorldgenChunks", 1024, 1, 262144);

    private static final ForgeConfigSpec.ConfigValue<String> OUTPUT_DIRECTORY = BUILDER
            .comment("渲染 PNG 文件输出目录。路径相对于服务器运行目录。")
            .define("outputDirectory", "worldgenrender");

    /** Forge 通用配置规格，由主类注册给加载上下文。 */
    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int commandPermissionLevel = 2;
    public static int maxChunksPerTick = 8;
    public static int renderPixelsPerBlock = 16;
    public static Render3dResolution render3dResolution = Render3dResolution.P1080;
    public static Render3dOrientation render3dOrientation = Render3dOrientation.NE;
    public static int render3dPixelsPerBlock = 32;
    public static int render3dVerticalScale = 16;
    public static boolean objExportIncludeInteriorFaces = false;
    public static boolean renderUseBlockTextures = true;
    public static boolean renderTextureFallbackToMapColor = true;
    public static int hotRegenerationPreloadChunksPerTick = 16;
    public static int hotRegenerationMaxConcurrentChunkFutures = 3;
    public static int hotRegenerationVisibleRadiusExtra = 1;
    public static int hotRegenerationClientResyncAttempts = 2;
    public static int maxBlockChangesPerTick = 20000;
    public static int maxRenderChunks = 4096;
    public static int maxWorldgenChunks = 1024;
    public static String outputDirectory = "worldgenrender";

    /** 3D 正交渲染单位清晰度预设：名称借用常见视频档位，但语义是每个方块/单位的采样密度。 */
    public enum Render3dResolution
    {
        P720(24, "720P"),
        P1080(32, "1080P"),
        P1440(48, "1440P"),
        P2160(64, "4K"),
        P4320(128, "8K");

        private final int pixelsPerBlock;
        private final String label;

        Render3dResolution(int pixelsPerBlock, String label)
        {
            this.pixelsPerBlock = pixelsPerBlock;
            this.label = label;
        }

        public int pixelsPerBlock()
        {
            return pixelsPerBlock;
        }

        public String label()
        {
            return label;
        }

        public static Render3dResolution parse(String value)
        {
            if (value == null || value.isBlank()) return render3dResolution;
            String normalized = value.trim().replace("-", "").replace("_", "");
            for (Render3dResolution resolution : values())
            {
                if (resolution.name().equalsIgnoreCase(normalized) || resolution.label().equalsIgnoreCase(normalized)) return resolution;
            }
            throw new IllegalArgumentException("未知 3D 渲染精度：" + value + "，可用 p720/720p/p1080/1080p/p1440/1440p/p2160/4k/p4320/8k");
        }
    }

    /** 3D 正交捕获四方向取向。 */
    public enum Render3dOrientation
    {
        NE(45.0F),
        NW(135.0F),
        SW(225.0F),
        SE(315.0F);

        private final float yawDegrees;

        Render3dOrientation(float yawDegrees)
        {
            this.yawDegrees = yawDegrees;
        }

        public float yawDegrees()
        {
            return yawDegrees;
        }

        public static Render3dOrientation parse(String value)
        {
            if (value == null || value.isBlank()) return render3dOrientation;
            for (Render3dOrientation orientation : values())
            {
                if (orientation.name().equalsIgnoreCase(value)) return orientation;
            }
            throw new IllegalArgumentException("未知正交取向：" + value + "，可用 NE/NW/SW/SE");
        }
    }

    /**
     * 配置加载/重载后，将 ForgeConfigSpec 中的值同步到静态字段，便于运行时读取。
     *
     * @param event 配置事件。
     */
    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        commandPermissionLevel = COMMAND_PERMISSION_LEVEL.get();
        maxChunksPerTick = MAX_CHUNKS_PER_TICK.get();
        renderPixelsPerBlock = RENDER_PIXELS_PER_BLOCK.get();
        render3dResolution = RENDER_3D_RESOLUTION.get();
        render3dOrientation = RENDER_3D_ORIENTATION.get();
        render3dPixelsPerBlock = RENDER_3D_PIXELS_PER_BLOCK.get();
        render3dVerticalScale = RENDER_3D_VERTICAL_SCALE.get();
        objExportIncludeInteriorFaces = OBJ_EXPORT_INCLUDE_INTERIOR_FACES.get();
        renderUseBlockTextures = RENDER_USE_BLOCK_TEXTURES.get();
        renderTextureFallbackToMapColor = RENDER_TEXTURE_FALLBACK_TO_MAP_COLOR.get();
        hotRegenerationPreloadChunksPerTick = HOT_REGENERATION_PRELOAD_CHUNKS_PER_TICK.get();
        hotRegenerationMaxConcurrentChunkFutures = HOT_REGENERATION_MAX_CONCURRENT_CHUNK_FUTURES.get();
        hotRegenerationVisibleRadiusExtra = HOT_REGENERATION_VISIBLE_RADIUS_EXTRA.get();
        hotRegenerationClientResyncAttempts = HOT_REGENERATION_CLIENT_RESYNC_ATTEMPTS.get();
        maxBlockChangesPerTick = MAX_BLOCK_CHANGES_PER_TICK.get();
        maxRenderChunks = MAX_RENDER_CHUNKS.get();
        maxWorldgenChunks = MAX_WORLDGEN_CHUNKS.get();
        outputDirectory = OUTPUT_DIRECTORY.get();
    }
}
