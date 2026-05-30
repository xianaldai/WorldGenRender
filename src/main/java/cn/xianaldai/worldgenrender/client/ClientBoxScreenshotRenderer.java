package cn.xianaldai.worldgenrender.client;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.network.ClientBoxRenderRequestPacket;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelDataManager;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 客户端范围渲染入口：使用类似 Mineshot 思路的正交离屏画布捕获，不再回退到伪 3D 光栅路径。
 * <p> 为什么不能做反选 :(
 * */
@OnlyIn(Dist.CLIENT)
public final class ClientBoxScreenshotRenderer
{
    private static final long MAX_CLIENT_GPU_BLOCKS = 15_000_000L;
    private static final long MAX_CLIENT_GPU_CANVAS_PIXELS = 1_073_741_824L;
    private static final int MAX_CLIENT_GPU_IMAGE_DIMENSION = 0xFFFF;
    private static final int MAX_CLIENT_GPU_SEGMENT_DIMENSION = 4096;
    private ClientBoxScreenshotRenderer() {}

    public static void handle(ClientBoxRenderRequestPacket packet)
    {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> renderSelectedBoxOnly(minecraft, packet));
    }

    private static void renderSelectedBoxOnly(Minecraft minecraft, ClientBoxRenderRequestPacket packet)
    {
        if (minecraft.level == null || minecraft.player == null) return;
        ClientLevel level = minecraft.level;
        ResourceLocation currentDimension = level.dimension().location();
        if (!currentDimension.equals(packet.dimension()))
        {
            minecraft.player.displayClientMessage(Component.literal("WorldGenRender 客户端范围渲染失败：当前客户端维度为 " + currentDimension + "，但请求维度为 " + packet.dimension()), false);
            return;
        }
        try
        {
            NativeImage image;
            try
            {
                image = renderBoxImageGpu(minecraft, level, packet);
            }
            catch (ClientRenderTooLargeException tooLarge)
            {
                minecraft.player.displayClientMessage(Component.literal("WorldGenRender 客户端正交范围渲染已拒绝：" + tooLarge.getMessage()), false);
                return;
            }
            catch (Throwable gpuFailure)
            {
                minecraft.player.displayClientMessage(Component.literal("WorldGenRender 正交离屏渲染失败，未回退到旧伪 3D 路径：" + gpuFailure.getClass().getSimpleName() + ": " + gpuFailure.getMessage()), false);
                return;
            }
            Path output = outputPath(minecraft.gameDirectory, packet.fileName());
            Files.createDirectories(output.getParent());
            image.writeToFile(output);
            int savedWidth = image.getWidth();
            int savedHeight = image.getHeight();
            image.close();
            minecraft.player.displayClientMessage(Component.literal("WorldGenRender 客户端范围 PNG 已保存(GPU 正交净室捕获, " + savedWidth + "x" + savedHeight + ")：" + output), false);
        }
        catch (Exception exception)
        {
            minecraft.player.displayClientMessage(Component.literal("WorldGenRender 客户端范围渲染失败：" + exception.getMessage()), false);
        }
    }

    private static NativeImage renderBoxImageGpu(Minecraft minecraft, ClientLevel level, ClientBoxRenderRequestPacket packet)
    {
        Box box = Box.of(packet.first(), packet.second(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int blocksX = box.maxX - box.minX + 1;
        int blocksY = box.maxY - box.minY + 1;
        int blocksZ = box.maxZ - box.minZ + 1;
        long blockCount = (long)blocksX * (long)blocksY * (long)blocksZ;
        if (blockCount > MAX_CLIENT_GPU_BLOCKS)
        {
            throw new ClientRenderTooLargeException("选区包含 " + blockCount + " 个方块，超过客户端 GPU 离屏逐方块渲染上限 " + MAX_CLIENT_GPU_BLOCKS + "。请缩小选区或使用服务端导出/分块渲染。当前限制用于避免客户端长时间卡死。");
        }
        int[] imageSize = chooseOutputSize(minecraft, packet.playerPerspective(), blocksX, blocksY, blocksZ);
        validateCanvasMemory(imageSize[0], imageSize[1]);
        CanvasPlan plan = buildCanvasPlan(minecraft, level, packet, box, imageSize[0], imageSize[1]);
        if (plan.width() <= MAX_CLIENT_GPU_SEGMENT_DIMENSION && plan.height() <= MAX_CLIENT_GPU_SEGMENT_DIMENSION)
        {
            return renderCanvasSegment(minecraft, level, plan, new CanvasSegment(0, 0, plan.width(), plan.height()));
        }
        return renderCanvasInSegments(minecraft, level, plan);
    }

    @SuppressWarnings("removal")
    private static RenderSelection collectRenderSelection(ClientLevel level, Box box, Frustum frustum, ModelDataManager modelDataManager, BlockEntityRenderDispatcher blockEntityDispatcher, ClientBoxRenderRequestPacket packet)
    {
        List<RenderBlock> blocks = new ArrayList<>();
        List<RenderBlockEntity> blockEntities = new ArrayList<>();
        Vec3 cameraPosition = packet.playerPerspective()
                ? new Vec3(packet.cameraX(), packet.cameraY(), packet.cameraZ())
                : new Vec3((box.minX + box.maxX + 1) / 2.0D, (box.minY + box.maxY + 1) / 2.0D, (box.minZ + box.maxZ + 1) / 2.0D);
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int y = box.minY; y <= box.maxY; y++)
        {
            for (int z = box.minZ; z <= box.maxZ; z++)
            {
                for (int x = box.minX; x <= box.maxX; x++)
                {
                    if (frustum != null && !frustum.isVisible(new AABB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D))) continue;
                    mutablePos.set(x, y, z);
                    BlockState state = level.getBlockState(mutablePos);
                    if (!state.isAir())
                    {
                        ChunkRenderTypeSet renderLayers = ItemBlockRenderTypes.getRenderLayers(state);
                        if (renderLayers != null && !renderLayers.isEmpty())
                        {
                            ModelData modelData = modelDataManager == null ? ModelData.EMPTY : modelDataManager.getAt(mutablePos);
                            if (modelData == null) modelData = ModelData.EMPTY;
                            blocks.add(new RenderBlock(mutablePos.immutable(), state, renderLayers, modelData, state.getSeed(mutablePos)));
                        }
                    }
                    BlockEntity blockEntity = level.getBlockEntity(mutablePos);
                    if (blockEntity == null || blockEntity.isRemoved()) continue;
                    BlockEntityRenderer<BlockEntity> renderer = uncheckedBlockEntityRenderer(blockEntityDispatcher, blockEntity);
                    if (renderer == null) continue;
                    if (!renderer.shouldRenderOffScreen(blockEntity) && !renderer.shouldRender(blockEntity, cameraPosition)) continue;
                    blockEntities.add(new RenderBlockEntity(blockEntity, renderer, LevelRenderer.getLightColor(level, blockEntity.getBlockPos())));
                }
            }
        }
        return new RenderSelection(blocks, blockEntities);
    }

    private static int[] chooseOutputSize(Minecraft minecraft, boolean playerPerspective, int blocksX, int blocksY, int blocksZ)
    {
        int unitPixels = Math.max(Config.render3dResolution.pixelsPerBlock(), Config.render3dPixelsPerBlock);
        int diagonalSpan = blocksX + blocksZ;
        int margin = unitPixels * 8;
        int width = diagonalSpan * unitPixels * 2 + margin * 2;
        int height = (blocksY + diagonalSpan) * unitPixels * 2 + margin * 2;
        return new int[] {Math.max(64, clampImageDimension(width)), Math.max(64, clampImageDimension(height))};
    }

    private static int clampImageDimension(int value)
    {
        return Math.max(64, Math.min(MAX_CLIENT_GPU_IMAGE_DIMENSION, value));
    }

    private static void validateCanvasMemory(int width, int height)
    {
        long pixels = (long)width * (long)height;
        if (pixels > MAX_CLIENT_GPU_CANVAS_PIXELS)
        {
            long megaPixels = pixels / 1_000_000L;
            long limitMegaPixels = MAX_CLIENT_GPU_CANVAS_PIXELS / 1_000_000L;
            throw new ClientRenderTooLargeException("目标正交画布约 " + megaPixels + " MP，超过客户端安全上限 " + limitMegaPixels + " MP。当前上限已按 Mineshot/Targa 65535 单轴思路放宽，但 PNG 仍需保留总像素内存保护；请降低 render3dResolution/render3dPixelsPerBlock 或缩小选区。");
        }
    }

    private static CanvasPlan buildCanvasPlan(Minecraft minecraft, ClientLevel level, ClientBoxRenderRequestPacket packet, Box box, int width, int height)
    {
        double centerX = (box.minX + box.maxX + 1) / 2.0D;
        double centerY = (box.minY + box.maxY + 1) / 2.0D;
        double centerZ = (box.minZ + box.maxZ + 1) / 2.0D;
        Config.Render3dOrientation orientation = Config.Render3dOrientation.parse(packet.orientation());
        PoseStack viewPose = createIsometricViewPose(centerX, centerY, centerZ, orientation);
        OrthoBounds bounds = orthographicBounds(box, viewPose.last().pose(), (float)width / (float)height);
        RenderSelection renderSelection = collectRenderSelection(level, box, null, level.getModelDataManager(), minecraft.getBlockEntityRenderDispatcher(), packet);
        return new CanvasPlan(width, height, viewPose, bounds, renderSelection, new SelectionClipLevel(level, box));
    }

    private static PoseStack createIsometricViewPose(double centerX, double centerY, double centerZ, Config.Render3dOrientation orientation)
    {
        PoseStack viewPose = new PoseStack();
        viewPose.mulPose(Axis.XP.rotationDegrees(60.0F));
        viewPose.mulPose(Axis.YP.rotationDegrees(orientation.yawDegrees()));
        viewPose.translate(-centerX, -centerY, -centerZ);
        return viewPose;
    }

    private static NativeImage renderCanvasInSegments(Minecraft minecraft, ClientLevel level, CanvasPlan plan)
    {
        NativeImage result = new NativeImage(plan.width(), plan.height(), true);
        clear(result);
        int segmentsX = (int)Math.ceil(plan.width() / (double)MAX_CLIENT_GPU_SEGMENT_DIMENSION);
        int segmentsY = (int)Math.ceil(plan.height() / (double)MAX_CLIENT_GPU_SEGMENT_DIMENSION);
        for (int segmentY = 0; segmentY < segmentsY; segmentY++)
        {
            for (int segmentX = 0; segmentX < segmentsX; segmentX++)
            {
                int x = segmentX * MAX_CLIENT_GPU_SEGMENT_DIMENSION;
                int y = segmentY * MAX_CLIENT_GPU_SEGMENT_DIMENSION;
                int segmentWidth = Math.min(MAX_CLIENT_GPU_SEGMENT_DIMENSION, plan.width() - x);
                int segmentHeight = Math.min(MAX_CLIENT_GPU_SEGMENT_DIMENSION, plan.height() - y);
                NativeImage segmentImage = renderCanvasSegment(minecraft, level, plan, new CanvasSegment(x, y, segmentWidth, segmentHeight));
                blitSegmentImage(segmentImage, result, x, y);
                segmentImage.close();
            }
        }
        return result;
    }

    private static NativeImage renderCanvasSegment(Minecraft minecraft, ClientLevel level, CanvasPlan plan, CanvasSegment segment)
    {
        TextureTarget target = new TextureTarget(segment.width(), segment.height(), true, Minecraft.ON_OSX);
        RenderSystem.backupProjectionMatrix();
        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        try
        {
            minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setBlurMipmap(false, true);
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.viewport(0, 0, segment.width(), segment.height());
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();

            OrthoBounds segmentBounds = segmentBounds(plan.bounds(), plan.width(), plan.height(), segment);
            Matrix4f projectionMatrix = new Matrix4f().ortho(segmentBounds.left(), segmentBounds.right(), segmentBounds.bottom(), segmentBounds.top(), -10000.0F, 10000.0F);
            RenderSystem.setProjectionMatrix(projectionMatrix, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
            modelViewStack.setIdentity();
            modelViewStack.mulPoseMatrix(plan.viewPose().last().pose());
            RenderSystem.applyModelViewMatrix();
            PoseStack renderPose = new PoseStack();

            BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShaderGameTime(level.getGameTime(), 0.0F);
            minecraft.gameRenderer.lightTexture().turnOnLightLayer();
            List<RenderBlock> renderBlocks = plan.renderSelection().blocks();
            for (RenderType renderType : RenderType.chunkBufferLayers())
            {
                BufferBuilder builder = new BufferBuilder(renderType.bufferSize());
                MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(builder);
                boolean wroteLayer = false;
                for (RenderBlock block : renderBlocks)
                {
                    if (!block.renderLayers().contains(renderType)) continue;
                    wroteLayer = true;
                    renderPose.pushPose();
                    renderPose.translate(block.pos().getX(), block.pos().getY(), block.pos().getZ());
                    RandomSource random = RandomSource.create(block.randomSeed());
                    dispatcher.renderBatched(block.state(), block.pos(), plan.levelView(), renderPose, bufferSource.getBuffer(renderType), true, random, block.modelData(), renderType);
                    renderPose.popPose();
                    FluidState fluidState = block.state().getFluidState();
                    if (!fluidState.isEmpty() && ItemBlockRenderTypes.getRenderLayer(fluidState) == renderType) dispatcher.renderLiquid(block.pos(), plan.levelView(), bufferSource.getBuffer(renderType), block.state(), fluidState);
                }
                if (wroteLayer && builder.building())
                {
                    builder.setQuadSorting(RenderSystem.getVertexSorting());
                    renderType.setupRenderState();
                    target.bindWrite(false);
                    RenderSystem.viewport(0, 0, segment.width(), segment.height());
                    BufferUploader.drawWithShader(builder.end());
                    renderType.clearRenderState();
                }
            }
            renderBlockEntitiesToTarget(minecraft, plan.renderSelection().blockEntities(), renderPose, target, segment.width(), segment.height());
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            return Screenshot.takeScreenshot(target);
        }
        finally
        {
            minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).restoreLastBlurMipmap();
            target.destroyBuffers();
            if (minecraft.getMainRenderTarget() != null)
            {
                minecraft.getMainRenderTarget().bindWrite(true);
                RenderSystem.viewport(0, 0, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
            }
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        }
    }

    private static OrthoBounds segmentBounds(OrthoBounds full, int fullWidth, int fullHeight, CanvasSegment segment)
    {
        float worldWidth = full.right() - full.left();
        float worldHeight = full.top() - full.bottom();
        float left = full.left() + worldWidth * segment.x() / fullWidth;
        float right = full.left() + worldWidth * (segment.x() + segment.width()) / fullWidth;
        float top = full.top() - worldHeight * segment.y() / fullHeight;
        float bottom = full.top() - worldHeight * (segment.y() + segment.height()) / fullHeight;
        return new OrthoBounds(left, right, bottom, top);
    }

    private static void blitSegmentImage(NativeImage segmentImage, NativeImage result, int dstX, int dstY)
    {
        for (int y = 0; y < segmentImage.getHeight(); y++)
        {
            for (int x = 0; x < segmentImage.getWidth(); x++)
            {
                result.setPixelRGBA(dstX + x, dstY + y, segmentImage.getPixelRGBA(x, y));
            }
        }
    }

    private static OrthoBounds orthographicBounds(Box box, Matrix4f viewMatrix, float aspect)
    {
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int xIndex = 0; xIndex < 2; xIndex++)
        {
            float x = xIndex == 0 ? box.minX : box.maxX + 1.0F;
            for (int yIndex = 0; yIndex < 2; yIndex++)
            {
                float y = yIndex == 0 ? box.minY : box.maxY + 1.0F;
                for (int zIndex = 0; zIndex < 2; zIndex++)
                {
                    float z = zIndex == 0 ? box.minZ : box.maxZ + 1.0F;
                    Vector4f projected = viewMatrix.transform(new Vector4f(x, y, z, 1.0F));
                    minX = Math.min(minX, projected.x());
                    maxX = Math.max(maxX, projected.x());
                    minY = Math.min(minY, projected.y());
                    maxY = Math.max(maxY, projected.y());
                }
            }
        }
        float width = Math.max(1.0F, maxX - minX);
        float height = Math.max(1.0F, maxY - minY);
        float centerX = (minX + maxX) * 0.5F;
        float centerY = (minY + maxY) * 0.5F;
        width *= 1.12F;
        height *= 1.12F;
        if (width / height < aspect) width = height * aspect;
        else height = width / aspect;
        return new OrthoBounds(centerX - width * 0.5F, centerX + width * 0.5F, centerY - height * 0.5F, centerY + height * 0.5F);
    }

    private static void renderBlockEntitiesToTarget(Minecraft minecraft, List<RenderBlockEntity> blockEntities, PoseStack poseStack, TextureTarget target, int width, int height)
    {
        target.bindWrite(false);
        RenderSystem.viewport(0, 0, width, height);
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        for (RenderBlockEntity renderBlockEntity : blockEntities)
        {
            BlockPos pos = renderBlockEntity.blockEntity().getBlockPos();
            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            renderBlockEntity.renderer().render(renderBlockEntity.blockEntity(), 0.0F, poseStack, bufferSource, renderBlockEntity.packedLight(), OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        target.bindWrite(false);
        RenderSystem.viewport(0, 0, width, height);
        bufferSource.endBatch();
        target.bindWrite(false);
        RenderSystem.viewport(0, 0, width, height);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockEntityRenderer<BlockEntity> uncheckedBlockEntityRenderer(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity)
    {
        return (BlockEntityRenderer)dispatcher.getRenderer(blockEntity);
    }

    private static void clear(NativeImage image)
    {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setPixelRGBA(x, y, 0x00000000);
    }

    private static Path outputPath(File gameDirectory, String fileName) throws IOException
    {
        return gameDirectory.toPath().resolve("screenshots").resolve(sanitizeFileName(fileName)).normalize();
    }

    private static String sanitizeFileName(String fileName)
    {
        String normalized = fileName == null ? "" : fileName.trim();
        if (normalized.isEmpty()) return defaultFileName();
        normalized = normalized.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..")) return defaultFileName();
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".png")) normalized = normalized + ".png";
        return "worldgenrender/" + normalized;
    }

    private static String defaultFileName()
    {
        return "worldgenrender/render3d_box_client_" + System.currentTimeMillis() + ".png";
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        private static Box of(BlockPos first, BlockPos second, int levelMinY, int levelMaxY)
        {
            return new Box(Math.min(first.getX(), second.getX()), Math.max(levelMinY, Math.min(first.getY(), second.getY())), Math.min(first.getZ(), second.getZ()), Math.max(first.getX(), second.getX()), Math.min(levelMaxY, Math.max(first.getY(), second.getY())), Math.max(first.getZ(), second.getZ()));
        }
    }

    private record RenderSelection(List<RenderBlock> blocks, List<RenderBlockEntity> blockEntities) {}

    private record OrthoBounds(float left, float right, float bottom, float top) {}

    private record CanvasPlan(int width, int height, PoseStack viewPose, OrthoBounds bounds, RenderSelection renderSelection, BlockAndTintGetter levelView) {}

    private record CanvasSegment(int x, int y, int width, int height) {}

    private record RenderBlock(BlockPos pos, BlockState state, ChunkRenderTypeSet renderLayers, ModelData modelData, long randomSeed) {}

    private record RenderBlockEntity(BlockEntity blockEntity, BlockEntityRenderer<BlockEntity> renderer, int packedLight) {}

    private static final class SelectionClipLevel implements BlockAndTintGetter
    {
        private final ClientLevel delegate;
        private final Box box;
        private final BlockState air = Blocks.AIR.defaultBlockState();

        private SelectionClipLevel(ClientLevel delegate, Box box)
        {
            this.delegate = delegate;
            this.box = box;
        }

        private boolean inside(BlockPos pos)
        {
            return pos.getX() >= box.minX && pos.getX() <= box.maxX
                    && pos.getY() >= box.minY && pos.getY() <= box.maxY
                    && pos.getZ() >= box.minZ && pos.getZ() <= box.maxZ;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos)
        {
            return inside(pos) ? delegate.getBlockEntity(pos) : null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos)
        {
            return inside(pos) ? delegate.getBlockState(pos) : air;
        }

        @Override
        public FluidState getFluidState(BlockPos pos)
        {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public float getShade(net.minecraft.core.Direction direction, boolean shade)
        {
            return delegate.getShade(direction, shade);
        }

        @Override
        public LevelLightEngine getLightEngine()
        {
            return delegate.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver)
        {
            return delegate.getBlockTint(pos, resolver);
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos)
        {
            return delegate.getBrightness(layer, pos);
        }

        @Override
        public int getRawBrightness(BlockPos pos, int amount)
        {
            return delegate.getRawBrightness(pos, amount);
        }

        @Override
        public int getHeight()
        {
            return delegate.getHeight();
        }

        @Override
        public int getMinBuildHeight()
        {
            return delegate.getMinBuildHeight();
        }
    }

    private static final class ClientRenderTooLargeException extends RuntimeException
    {
        private ClientRenderTooLargeException(String message)
        {
            super(message);
        }
    }

}
