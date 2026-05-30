package cn.xianaldai.worldgenrender.task.render;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.AbstractWorldGenTask;
import cn.xianaldai.worldgenrender.task.ChunkRange;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 采样目标区块或方块空间内可见方块面，并输出正交 3D PNG。默认只渲染表面/边缘；cutaway/box 模式才渲染地下剖面。 */
public final class WorldGenRender3dTask extends AbstractWorldGenTask
{
    private static final double EPSILON = 1.0E-6D;
    private static final AABB FULL_BLOCK_BOX = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    private static final int BOUNDARY_EMPTY_FILL = 0xFF101418;

    private final Path output;
    private final boolean cutaway;
    private final boolean blockBoxMode;
    private final double yawRadians;
    private final double pitchRadians;
    private final double viewX;
    private final double viewY;
    private final double viewZ;
    private final double rightX;
    private final double rightY;
    private final double rightZ;
    private final double upX;
    private final double upY;
    private final double upZ;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final int blocksX;
    private final int blocksZ;
    private final int minBlockX;
    private final int minBlockZ;
    private final int maxBlockX;
    private final int maxBlockZ;
    private final int minY;
    private final int maxY;
    private final List<RenderFace> faces = new ArrayList<>();
    private final Map<BlockState, Integer> colorCache = new HashMap<>();
    private final Map<BlockState, List<AABB>> shapeCache = new HashMap<>();
    private final TextureResolver textureResolver = new TextureResolver();
    private int currentX;
    private int currentZ;
    private int sampledFaces;
    private boolean failed;

    public WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output)
    {
        this(source, level, range, output, false);
    }

    public WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, boolean cutaway)
    {
        this(source, level, range, output, cutaway, Config.render3dOrientation.yawDegrees());
    }

    public WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, boolean cutaway, float yawDegrees)
    {
        this(source, level, range, output, cutaway, false, yawDegrees,
                range.minChunkX() * 16,
                level.getMinBuildHeight(),
                range.minChunkZ() * 16,
                range.maxChunkX() * 16 + 15,
                level.getMaxBuildHeight() - 1,
                range.maxChunkZ() * 16 + 15);
    }

    public WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, int x1, int y1, int z1, int x2, int y2, int z2)
    {
        this(source, level, range, output, 45.0F, x1, y1, z1, x2, y2, z2);
    }

    public WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, float yawDegrees, int x1, int y1, int z1, int x2, int y2, int z2)
    {
        this(source, level, range, output, yawDegrees, 60.0F, x1, y1, z1, x2, y2, z2);
    }

    public WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, float yawDegrees, float pitchDegrees, int x1, int y1, int z1, int x2, int y2, int z2)
    {
        this(source, level, range, output, yawDegrees, pitchDegrees, (x1 + x2 + 1) * 0.5D, (y1 + y2 + 1) * 0.5D, (z1 + z2 + 1) * 0.5D, x1, y1, z1, x2, y2, z2);
    }

    public WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, float yawDegrees, float pitchDegrees, double cameraWorldX, double cameraWorldY, double cameraWorldZ, int x1, int y1, int z1, int x2, int y2, int z2)
    {
        this(source, level, range, output, true, true, yawDegrees, pitchDegrees, cameraWorldX, cameraWorldY, cameraWorldZ,
                Math.min(x1, x2),
                Math.max(level.getMinBuildHeight(), Math.min(y1, y2)),
                Math.min(z1, z2),
                Math.max(x1, x2),
                Math.min(level.getMaxBuildHeight() - 1, Math.max(y1, y2)),
                Math.max(z1, z2));
    }

    private WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, boolean cutaway, boolean blockBoxMode, float yawDegrees, int minBlockX, int minY, int minBlockZ, int maxBlockX, int maxY, int maxBlockZ)
    {
        this(source, level, range, output, cutaway, blockBoxMode, yawDegrees, 60.0F, minBlockX, minY + 64.0D, minBlockZ, minBlockX, minY, minBlockZ, maxBlockX, maxY, maxBlockZ);
    }

    private WorldGenRender3dTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output, boolean cutaway, boolean blockBoxMode, float yawDegrees, float pitchDegrees, double cameraWorldX, double cameraWorldY, double cameraWorldZ, int minBlockX, int minY, int minBlockZ, int maxBlockX, int maxY, int maxBlockZ)
    {
        super(source, level, range);
        this.output = output;
        this.cutaway = cutaway;
        this.blockBoxMode = blockBoxMode;
        this.yawRadians = Math.toRadians(yawDegrees);
        this.pitchRadians = Math.toRadians(Math.max(-89.0F, Math.min(89.0F, pitchDegrees)));
        this.viewX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        this.viewY = -Math.sin(pitchRadians);
        this.viewZ = Math.cos(yawRadians) * Math.cos(pitchRadians);
        double horizontalLength = Math.max(EPSILON, Math.hypot(viewX, viewZ));
        this.rightX = -viewZ / horizontalLength;
        this.rightY = 0.0D;
        this.rightZ = viewX / horizontalLength;
        this.upX = rightY * viewZ - rightZ * viewY;
        this.upY = rightZ * viewX - rightX * viewZ;
        this.upZ = rightX * viewY - rightY * viewX;
        this.cameraX = cameraWorldX - minBlockX;
        this.cameraY = cameraWorldY;
        this.cameraZ = cameraWorldZ - minBlockZ;
        this.minBlockX = minBlockX;
        this.minBlockZ = minBlockZ;
        this.maxBlockX = maxBlockX;
        this.maxBlockZ = maxBlockZ;
        this.minY = minY;
        this.maxY = maxY;
        this.blocksX = maxBlockX - minBlockX + 1;
        this.blocksZ = maxBlockZ - minBlockZ + 1;
        this.currentX = range.minChunkX();
        this.currentZ = range.minChunkZ();
    }

    @Override public String name() { return blockBoxMode ? "renderBox" : (cutaway ? "render3dCutaway" : "render3d"); }

    @Override
    public void tick(MinecraftServer server)
    {
        if (done || failed) return;
        try
        {
            int budget = Config.maxChunksPerTick;
            while (!cancelled && budget-- > 0 && currentZ <= range.maxChunkZ())
            {
                sampleChunkVisibleFaces(currentX, currentZ);
                processedChunks++;
                advance();
            }

            if (cancelled)
            {
                done = true;
                return;
            }

            if (currentZ > range.maxChunkZ())
            {
                renderImage();
            }
        }
        catch (Throwable throwable)
        {
            failed = true;
            done = true;
            source.sendFailure(Component.literal(name() + " 失败：" + throwable.getMessage()));
        }
    }

    @Override
    public Component status()
    {
        return Component.literal(name() + " " + processedChunks + "/" + range.chunkCount() + " chunks"
                + " blocks=" + blocksX + "x" + (maxY - minY + 1) + "x" + blocksZ
                + " quality=" + Config.render3dResolution.label() + "(" + renderUnitPixels() + "px/unit)"
                + " projection=orthographic"
                + " mode=" + (blockBoxMode ? "block-box" : (cutaway ? "cutaway" : "surface-edge")));
    }

    private void sampleChunkVisibleFaces(int chunkX, int chunkZ)
    {
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++)
        {
            for (int localX = 0; localX < 16; localX++)
            {
                int worldX = chunkPos.getMinBlockX() + localX;
                int worldZ = chunkPos.getMinBlockZ() + localZ;
                if (worldX < minBlockX || worldX > maxBlockX || worldZ < minBlockZ || worldZ > maxBlockZ) continue;
                if (cutaway) sampleCutawayColumn(worldX, worldZ, pos, neighborPos);
                else sampleSurfaceColumn(worldX, worldZ, pos, neighborPos);
            }
        }
    }

    private void sampleSurfaceColumn(int worldX, int worldZ, BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos neighborPos)
    {
        SurfaceSample terrain = findTerrainSurface(worldX, worldZ, pos);
        SurfaceSample visible = findSurface(worldX, worldZ, pos);
        if (terrain == null && visible == null) return;

        int localBlockX = worldX - minBlockX;
        int localBlockZ = worldZ - minBlockZ;
        if (terrain != null)
        {
            int color = colorFor(terrain.state, pos);
            boolean fluid = !terrain.state.getFluidState().isEmpty();
            faces.add(new RenderFace(localBlockX, terrain.y, localBlockZ, FULL_BLOCK_BOX, Direction.UP, color, fluid, terrain.state));
            sampledFaces++;

            addSurfaceHeightTransition(worldX, terrain.y, worldZ, localBlockX, localBlockZ, Direction.EAST, pos, color);
            addSurfaceHeightTransition(worldX, terrain.y, worldZ, localBlockX, localBlockZ, Direction.SOUTH, pos, color);
            addSurfaceHeightTransition(worldX, terrain.y, worldZ, localBlockX, localBlockZ, Direction.WEST, pos, color);
            addSurfaceHeightTransition(worldX, terrain.y, worldZ, localBlockX, localBlockZ, Direction.NORTH, pos, color);
            addNearBoundaryWallIfNeeded(worldX, terrain.y, worldZ, localBlockX, localBlockZ, Direction.EAST, pos);
            addNearBoundaryWallIfNeeded(worldX, terrain.y, worldZ, localBlockX, localBlockZ, Direction.SOUTH, pos);
        }

        if (visible != null && (terrain == null || visible.y > terrain.y))
        {
            addDecorativeSurface(worldX, worldZ, localBlockX, localBlockZ, terrain == null ? minY - 1 : terrain.y, visible.y, pos);
        }
    }

    private void addNearBoundaryWallIfNeeded(int worldX, int surfaceY, int worldZ, int localBlockX, int localBlockZ, Direction direction, BlockPos.MutableBlockPos pos)
    {
        if (direction == Direction.EAST && worldX != maxBlockX) return;
        if (direction == Direction.SOUTH && worldZ != maxBlockZ) return;

        for (int y = surfaceY; y >= minY; y--)
        {
            pos.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(pos);
            int color;
            boolean fluid = false;
            if (shouldRenderState(state))
            {
                color = colorFor(state, pos);
                fluid = !state.getFluidState().isEmpty();
            }
            else
            {
                color = BOUNDARY_EMPTY_FILL;
            }
            faces.add(new RenderFace(localBlockX, y, localBlockZ, FULL_BLOCK_BOX, direction, color, fluid, state));
            sampledFaces++;
        }
    }

    private boolean shouldRenderTerrainSide(BlockState state)
    {
        return shouldRenderTerrainSurface(state) && state.getFluidState().isEmpty();
    }

    private boolean shouldRenderTerrainSurface(BlockState state)
    {
        return shouldRenderState(state) && (state.blocksMotion() || !state.getFluidState().isEmpty());
    }

    private void addSurfaceHeightTransition(int worldX, int surfaceY, int worldZ, int localBlockX, int localBlockZ, Direction direction, BlockPos.MutableBlockPos pos, int surfaceColor)
    {
        int nx = worldX + direction.getStepX();
        int nz = worldZ + direction.getStepZ();
        if (nx < minBlockX || nx > maxBlockX || nz < minBlockZ || nz > maxBlockZ) return;

        SurfaceSample neighborSurface = findTerrainSurface(nx, nz, pos);
        int neighborY = neighborSurface == null ? minY - 1 : neighborSurface.y;
        if (surfaceY <= neighborY) return;

        int fillColor = surfaceColor;
        for (int y = surfaceY; y > neighborY; y--)
        {
            pos.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(pos);
            int color;
            if (shouldRenderTerrainSide(state))
            {
                color = colorFor(state, pos);
                fillColor = color;
            }
            else
            {
                color = fillColor;
            }

            faces.add(new RenderFace(localBlockX, y, localBlockZ, FULL_BLOCK_BOX, direction, color, false, state));
            sampledFaces++;
        }
    }

    private SurfaceSample findSurface(int worldX, int worldZ, BlockPos.MutableBlockPos pos)
    {
        int topY = Math.min(maxY, level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1);
        return findSurfaceFrom(worldX, worldZ, topY, pos, false);
    }

    private SurfaceSample findTerrainSurface(int worldX, int worldZ, BlockPos.MutableBlockPos pos)
    {
        int topY = Math.min(maxY, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1);
        return findSurfaceFrom(worldX, worldZ, topY, pos, true);
    }

    private SurfaceSample findSurfaceFrom(int worldX, int worldZ, int topY, BlockPos.MutableBlockPos pos, boolean terrainOnly)
    {
        for (int y = topY; y >= minY; y--)
        {
            pos.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(pos);
            if (terrainOnly ? shouldRenderTerrainSurface(state) : shouldRenderState(state)) return new SurfaceSample(y, state);
        }
        return null;
    }

    private void addDecorativeSurface(int worldX, int worldZ, int localBlockX, int localBlockZ, int terrainY, int visibleY, BlockPos.MutableBlockPos pos)
    {
        for (int y = terrainY + 1; y <= visibleY; y++)
        {
            pos.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(pos);
            if (!shouldRenderState(state) || shouldRenderTerrainSurface(state)) continue;

            int color = colorFor(state, pos);
            boolean fluid = !state.getFluidState().isEmpty();
            faces.add(new RenderFace(localBlockX, y, localBlockZ, FULL_BLOCK_BOX, Direction.UP, color, fluid, state));
            sampledFaces++;
        }
    }

    private void sampleCutawayColumn(int worldX, int worldZ, BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos neighborPos)
    {
        int topY = blockBoxMode ? maxY : Math.min(maxY, level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1);
        for (int y = topY; y >= minY; y--)
        {
            pos.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(pos);
            if (!shouldRenderState(state)) continue;

            int localBlockX = worldX - minBlockX;
            int localBlockZ = worldZ - minBlockZ;
            int color = colorFor(state, pos);
            if (!state.getFluidState().isEmpty())
            {
                addFluidSurfaceIfNeeded(worldX, y, worldZ, localBlockX, localBlockZ, color, neighborPos);
                continue;
            }

            List<AABB> boxes = getBoxes(state, pos);
            if (boxes.isEmpty()) continue;

            for (AABB box : boxes)
            {
                for (Direction direction : visibleDirections())
                {
                    if (shouldDrawBoxFace(worldX, y, worldZ, box, direction, neighborPos))
                    {
                        faces.add(new RenderFace(localBlockX, y, localBlockZ, box, direction, color, false, state));
                        sampledFaces++;
                    }
                }
                if (!blockBoxMode)
                {
                    addCutawayBoundaryFaceIfNeeded(worldX, y, worldZ, localBlockX, localBlockZ, box, Direction.WEST, color);
                    addCutawayBoundaryFaceIfNeeded(worldX, y, worldZ, localBlockX, localBlockZ, box, Direction.NORTH, color);
                }
            }
        }
    }

    private Direction[] visibleDirections()
    {
        if (!blockBoxMode) return new Direction[] {Direction.UP, Direction.EAST, Direction.SOUTH};
        List<Direction> directions = new ArrayList<>(6);
        for (Direction direction : Direction.values())
        {
            if (faceLooksTowardCamera(direction)) directions.add(direction);
        }
        return directions.toArray(Direction[]::new);
    }

    private boolean faceLooksTowardCamera(Direction direction)
    {
        return direction.getStepX() * viewX + direction.getStepY() * viewY + direction.getStepZ() * viewZ < -EPSILON;
    }

    private void addCutawayBoundaryFaceIfNeeded(int worldX, int y, int worldZ, int localBlockX, int localBlockZ, AABB box, Direction direction, int color)
    {
        if (direction == Direction.WEST && worldX != minBlockX) return;
        if (direction == Direction.NORTH && worldZ != minBlockZ) return;
        if (!touchesBlockBoundary(box, direction)) return;

        faces.add(new RenderFace(localBlockX, y, localBlockZ, box, direction, color, false, null));
        sampledFaces++;
    }

    private void addFluidSurfaceIfNeeded(int worldX, int y, int worldZ, int localBlockX, int localBlockZ, int color, BlockPos.MutableBlockPos neighborPos)
    {
        neighborPos.set(worldX, y + 1, worldZ);
        BlockState above = level.getBlockState(neighborPos);
        FluidState aboveFluid = above.getFluidState();
        if (!aboveFluid.isEmpty()) return;
        if (shouldRenderState(above) && above.canOcclude()) return;

        faces.add(new RenderFace(localBlockX, y, localBlockZ, FULL_BLOCK_BOX, Direction.UP, color, true, null));
        sampledFaces++;
    }

    private List<AABB> getBoxes(BlockState state, BlockPos pos)
    {
        return shapeCache.computeIfAbsent(state, ignored -> {
            VoxelShape shape = state.getShape(level, pos);
            if (shape.isEmpty()) shape = state.getVisualShape(level, pos, CollisionContext.empty());
            if (shape.isEmpty()) shape = state.getOcclusionShape(level, pos);
            return shape.isEmpty() ? List.of() : List.copyOf(shape.toAabbs());
        });
    }

    private boolean shouldDrawBoxFace(int x, int y, int z, AABB box, Direction direction, BlockPos.MutableBlockPos neighborPos)
    {
        if (!touchesBlockBoundary(box, direction)) return true;

        int nx = x + direction.getStepX();
        int ny = y + direction.getStepY();
        int nz = z + direction.getStepZ();
        if (nx < minBlockX || nx > maxBlockX || nz < minBlockZ || nz > maxBlockZ || ny < minY || ny > maxY) return true;

        neighborPos.set(nx, ny, nz);
        BlockState neighbor = level.getBlockState(neighborPos);
        return !neighborCoversFace(neighbor, neighborPos, direction.getOpposite(), box);
    }

    private boolean touchesBlockBoundary(AABB box, Direction direction)
    {
        return switch (direction)
                {
                    case DOWN -> box.minY <= EPSILON;
                    case UP -> box.maxY >= 1.0D - EPSILON;
                    case NORTH -> box.minZ <= EPSILON;
                    case SOUTH -> box.maxZ >= 1.0D - EPSILON;
                    case WEST -> box.minX <= EPSILON;
                    case EAST -> box.maxX >= 1.0D - EPSILON;
                };
    }

    private boolean neighborCoversFace(BlockState neighbor, BlockPos neighborPos, Direction face, AABB sourceBox)
    {
        if (!shouldRenderState(neighbor)) return false;
        if (!neighbor.getFluidState().isEmpty()) return face == Direction.DOWN || face == Direction.UP;
        List<AABB> boxes = getBoxes(neighbor, neighborPos);
        if (boxes.isEmpty()) return false;

        for (AABB neighborBox : boxes)
        {
            if (!touchesBlockBoundary(neighborBox, face)) continue;
            if (faceCoverageContains(sourceBox, neighborBox, face)) return true;
        }
        return false;
    }

    private boolean faceCoverageContains(AABB source, AABB cover, Direction face)
    {
        return switch (face.getAxis())
                {
                    case X -> cover.minY <= source.minY + EPSILON && cover.maxY >= source.maxY - EPSILON
                            && cover.minZ <= source.minZ + EPSILON && cover.maxZ >= source.maxZ - EPSILON;
                    case Y -> cover.minX <= source.minX + EPSILON && cover.maxX >= source.maxX - EPSILON
                            && cover.minZ <= source.minZ + EPSILON && cover.maxZ >= source.maxZ - EPSILON;
                    case Z -> cover.minX <= source.minX + EPSILON && cover.maxX >= source.maxX - EPSILON
                            && cover.minY <= source.minY + EPSILON && cover.maxY >= source.maxY - EPSILON;
                };
    }

    private boolean shouldRenderState(BlockState state)
    {
        return !state.isAir() && (state.getRenderShape() != RenderShape.INVISIBLE || !state.getFluidState().isEmpty());
    }

    private int colorFor(BlockState state, BlockPos pos)
    {
        return colorCache.computeIfAbsent(state, ignored -> {
            MapColor color = state.getMapColor(level, pos);
            return color == MapColor.NONE ? (!state.getFluidState().isEmpty() ? 0xFF2458FF : 0xFF777777) : toArgb(color, MapColor.Brightness.NORMAL);
        });
    }

    private void advance()
    {
        currentX++;
        if (currentX > range.maxChunkX())
        {
            currentX = range.minChunkX();
            currentZ++;
        }
    }

    private void renderImage() throws IOException
    {
        int unitPixels = renderUnitPixels();
        int halfW = Math.max(1, unitPixels);
        int halfH = Math.max(1, unitPixels / 2);
        int vertical = Math.max(1, unitPixels);
        int heightSpan = Math.max(1, maxY - minY + 1);
        int margin = Math.max(96, unitPixels * 8);
        int imageWidth = Math.max(1, (blocksX + blocksZ) * halfW + margin * 2);
        int imageHeight = Math.max(1, (blocksX + blocksZ) * halfH + (cutaway || blockBoxMode ? heightSpan * vertical : Math.min(64, heightSpan) * vertical) + margin * 2);
        int originX = blockBoxMode ? imageWidth / 2 : blocksZ * halfW + margin;
        int originY = blockBoxMode ? imageHeight / 2 : (cutaway ? heightSpan * vertical : Math.min(64, heightSpan) * vertical) + margin / 2;

        if (blockBoxMode) faces.sort(Comparator.comparingDouble(this::faceDepth));
        else faces.sort(Comparator.comparingInt(RenderFace::sortKey));

        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, imageWidth, imageHeight);

            for (RenderFace face : faces)
            {
                drawFace(graphics, face, originX, originY, halfW, halfH, vertical);
            }
            drawOutline(graphics, originX, originY, halfW, halfH, vertical);
        }
        finally
        {
            graphics.dispose();
        }

        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        done = true;
        source.sendSuccess(() -> Component.literal(name() + " 完成：" + output.toAbsolutePath()
                + "，模式 " + (blockBoxMode ? "空间两点盒选区正交" : (cutaway ? "地下剖面正交" : "表面边缘正交"))
                + "，清晰度 " + Config.render3dResolution.label() + "(" + unitPixels + "px/unit)"
                + "，图片尺寸 " + imageWidth + "x" + imageHeight
                + "，可见面 " + sampledFaces
                + "，范围 " + blocksX + "x" + heightSpan + "x" + blocksZ + " 方块"), true);
    }

    private int renderUnitPixels()
    {
        return Math.max(Config.render3dResolution.pixelsPerBlock(), Config.render3dPixelsPerBlock);
    }

    private void drawFace(Graphics2D graphics, RenderFace face, int originX, int originY, int halfW, int halfH, int vertical)
    {
        double x0 = face.x + face.box.minX;
        double y0 = face.y + face.box.minY;
        double z0 = face.z + face.box.minZ;
        double x1 = face.x + face.box.maxX;
        double y1 = face.y + face.box.maxY;
        double z1 = face.z + face.box.maxZ;

        double[][] vertices = switch (face.direction)
                {
                    case DOWN -> new double[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
                    case UP -> new double[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                    case EAST -> new double[][] {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
                    case SOUTH -> new double[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
                    case WEST -> new double[][] {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
                    case NORTH -> new double[][] {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
                };

        int[] xs = new int[4];
        int[] ys = new int[4];
        for (int i = 0; i < 4; i++)
        {
            ProjectedPoint point = project(vertices[i][0], vertices[i][1], vertices[i][2], originX, originY, halfW, halfH, vertical);
            xs[i] = point.x();
            ys[i] = point.y();
        }

        float brightness = switch (face.direction)
                {
                    case DOWN -> 0.46F;
                    case UP -> face.fluid ? 0.96F : 1.06F;
                    case EAST -> 0.60F;
                    case SOUTH -> 0.72F;
                    case WEST -> 0.58F;
                    case NORTH -> 0.68F;
                };
        Polygon polygon = new Polygon(xs, ys, 4);
        if (blockBoxMode && Config.renderUseBlockTextures && drawTexturedFace(graphics, face, originX, originY, halfW, halfH, vertical, brightness)) return;
        graphics.setColor(new Color(applyBrightness(face.argb, brightness), true));
        graphics.fillPolygon(polygon);
        graphics.setColor(new Color(face.fluid ? 0x330000AA : 0x22000000, true));
        graphics.drawPolygon(polygon);
    }

    private boolean drawTexturedFace(Graphics2D graphics, RenderFace face, int originX, int originY, int halfW, int halfH, int vertical, float brightness)
    {
        if (face.state == null) return false;
        TexturePixels texture = textureResolver.resolve(face.state, face.direction);
        if (texture == null) return false;
        int steps = Math.max(8, Math.min(32, Math.max(32, Config.render3dPixelsPerBlock) / 2));
        for (int v = 0; v < steps; v++)
        {
            double v0 = (double)v / steps;
            double v1 = (double)(v + 1) / steps;
            for (int u = 0; u < steps; u++)
            {
                double u0 = (double)u / steps;
                double u1 = (double)(u + 1) / steps;
                ProjectedPoint p0 = projectFacePoint(face, u0, v0, originX, originY, halfW, halfH, vertical);
                ProjectedPoint p1 = projectFacePoint(face, u1, v0, originX, originY, halfW, halfH, vertical);
                ProjectedPoint p2 = projectFacePoint(face, u1, v1, originX, originY, halfW, halfH, vertical);
                ProjectedPoint p3 = projectFacePoint(face, u0, v1, originX, originY, halfW, halfH, vertical);
                int argb = texture.sample(u, v, steps);
                if (((argb >>> 24) & 255) == 0)
                {
                    if (!Config.renderTextureFallbackToMapColor) continue;
                    argb = face.argb;
                }
                else if (shouldApplyBiomeTint(face, texture))
                {
                    argb = multiplyRgb(argb, face.argb);
                }
                graphics.setColor(new Color(applyBrightness(argb, brightness), true));
                graphics.fillPolygon(new int[] {p0.x(), p1.x(), p2.x(), p3.x()}, new int[] {p0.y(), p1.y(), p2.y(), p3.y()}, 4);
            }
        }
        return true;
    }

    private ProjectedPoint projectFacePoint(RenderFace face, double u, double v, int originX, int originY, int halfW, int halfH, int vertical)
    {
        double x0 = face.x + face.box.minX;
        double y0 = face.y + face.box.minY;
        double z0 = face.z + face.box.minZ;
        double x1 = face.x + face.box.maxX;
        double y1 = face.y + face.box.maxY;
        double z1 = face.z + face.box.maxZ;
        double x;
        double y;
        double z;
        switch (face.direction)
        {
            case UP -> { x = x0 + (x1 - x0) * u; y = y1; z = z0 + (z1 - z0) * v; }
            case DOWN -> { x = x0 + (x1 - x0) * u; y = y0; z = z1 - (z1 - z0) * v; }
            case EAST -> { x = x1; y = y0 + (y1 - y0) * v; z = z1 - (z1 - z0) * u; }
            case WEST -> { x = x0; y = y0 + (y1 - y0) * v; z = z0 + (z1 - z0) * u; }
            case SOUTH -> { x = x0 + (x1 - x0) * u; y = y0 + (y1 - y0) * v; z = z1; }
            case NORTH -> { x = x1 - (x1 - x0) * u; y = y0 + (y1 - y0) * v; z = z0; }
            default -> throw new IllegalStateException("Unexpected visible face " + face.direction);
        }
        return project(x, y, z, originX, originY, halfW, halfH, vertical);
    }

    private void drawOutline(Graphics2D graphics, int originX, int originY, int halfW, int halfH, int vertical)
    {
        graphics.setStroke(new BasicStroke(Math.max(1.0F, Math.max(32, Config.render3dPixelsPerBlock) / 16.0F)));
        graphics.setColor(new Color(0x44000000, true));
        ProjectedPoint p0 = project(0, minY, 0, originX, originY, halfW, halfH, vertical);
        ProjectedPoint p1 = project(blocksX, minY, 0, originX, originY, halfW, halfH, vertical);
        ProjectedPoint p2 = project(blocksX, minY, blocksZ, originX, originY, halfW, halfH, vertical);
        ProjectedPoint p3 = project(0, minY, blocksZ, originX, originY, halfW, halfH, vertical);
        int x0 = p0.x();
        int y0 = p0.y();
        int x1 = p1.x();
        int y1 = p1.y();
        int x2 = p2.x();
        int y2 = p2.y();
        int x3 = p3.x();
        int y3 = p3.y();
        if (cutaway) graphics.drawPolygon(new int[] {x0, x1, x2, x3}, new int[] {y0, y1, y2, y3}, 4);
    }

    private ProjectedPoint project(double x, double y, double z, int originX, int originY, int halfW, int halfH, int vertical)
    {
        if (blockBoxMode)
        {
            double centerX = blocksX * 0.5D;
            double centerY = (minY + maxY + 1) * 0.5D;
            double centerZ = blocksZ * 0.5D;
            double dx = x - centerX;
            double dy = y - centerY;
            double dz = z - centerZ;
            int screenX = (int)Math.round(originX + (dx * rightX + dy * rightY + dz * rightZ) * halfW);
            int screenY = (int)Math.round(originY - (dx * upX + dy * upY + dz * upZ) * vertical);
            return new ProjectedPoint(screenX, screenY);
        }
        return new ProjectedPoint(
                (int)Math.round(originX + (x - z) * halfW),
                (int)Math.round(originY + (x + z) * halfH - (y - minY) * vertical));
    }

    private double faceDepth(RenderFace face)
    {
        AABB box = face.box;
        double centerX = face.x + (box.minX + box.maxX) * 0.5D - blocksX * 0.5D;
        double centerY = face.y + (box.minY + box.maxY) * 0.5D - (minY + maxY + 1) * 0.5D;
        double centerZ = face.z + (box.minZ + box.maxZ) * 0.5D - blocksZ * 0.5D;
        return centerX * viewX + centerY * viewY + centerZ * viewZ;
    }

    private int toArgb(MapColor color, MapColor.Brightness brightness)
    {
        int modifier = brightness.modifier;
        int r = ((color.col >> 16) & 255) * modifier / 255;
        int g = ((color.col >> 8) & 255) * modifier / 255;
        int b = (color.col & 255) * modifier / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int applyBrightness(int argb, float brightness)
    {
        int a = (argb >>> 24) & 255;
        int r = Math.min(255, Math.max(0, Math.round(((argb >> 16) & 255) * brightness)));
        int g = Math.min(255, Math.max(0, Math.round(((argb >> 8) & 255) * brightness)));
        int b = Math.min(255, Math.max(0, Math.round((argb & 255) * brightness)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private record SurfaceSample(int y, BlockState state) {}

    private record ProjectedPoint(int x, int y) {}

    private boolean shouldApplyBiomeTint(RenderFace face, TexturePixels texture)
    {
        if (face.state == null || ((face.argb >>> 24) & 255) == 0) return false;
        int tintSaturation = rgbSaturation(face.argb);
        if (tintSaturation <= 24) return false;
        return texture.averageSaturation() < 40;
    }

    private static int multiplyRgb(int argb, int tintArgb)
    {
        int alpha = (argb >>> 24) & 255;
        if (alpha == 0) return argb;
        int r = ((argb >> 16) & 255) * ((tintArgb >> 16) & 255) / 255;
        int g = ((argb >> 8) & 255) * ((tintArgb >> 8) & 255) / 255;
        int b = (argb & 255) * (tintArgb & 255) / 255;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static int rgbSaturation(int argb)
    {
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min;
    }

    private final class TextureResolver
    {
        private final Map<String, TexturePixels> cache = new HashMap<>();
        private TexturePixels resolve(BlockState state, Direction direction)
        {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String key = blockId + "#" + direction.getName();
            if (cache.containsKey(key)) return cache.get(key);
            TexturePixels texture = loadTexture(blockId, direction);
            cache.put(key, texture);
            return texture;
        }

        private TexturePixels loadTexture(ResourceLocation blockId, Direction direction)
        {
            String namespace = blockId.getNamespace();
            String path = blockId.getPath();
            String strippedBlock = path.replace("_block", "");
            String strippedWall = path.replace("_wall", "");
            String strippedStairs = path.replace("_stairs", "");
            String strippedSlab = path.replace("_slab", "");
            String[] suffixes = switch (direction)
                    {
                        case UP -> new String[] {"_top", "_up", "", "_side", "_bottom", "_down"};
                        case DOWN -> new String[] {"_bottom", "_down", "_top", "_up", "", "_side"};
                        case NORTH -> new String[] {"_north", "_front", "_side", "", "_south", "_back", "_east", "_west", "_top", "_bottom"};
                        case SOUTH -> new String[] {"_south", "_back", "_side", "", "_north", "_front", "_east", "_west", "_top", "_bottom"};
                        case WEST -> new String[] {"_west", "_left", "_side", "", "_east", "_right", "_north", "_south", "_top", "_bottom"};
                        case EAST -> new String[] {"_east", "_right", "_side", "", "_west", "_left", "_north", "_south", "_top", "_bottom"};
                    };
            String[] bases = new String[] {path, strippedBlock, strippedWall, strippedStairs, strippedSlab};
            for (String base : bases)
            {
                for (String suffix : suffixes)
                {
                    TexturePixels texture = readClasspathTexture(namespace, base + suffix);
                    if (texture != null) return texture;
                }
            }
            return null;
        }

        private TexturePixels readClasspathTexture(String namespace, String texturePath)
        {
            String resource = "assets/" + namespace + "/textures/block/" + texturePath + ".png";
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            try (InputStream stream = loader.getResourceAsStream(resource))
            {
                if (stream == null) return null;
                BufferedImage image = ImageIO.read(stream);
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
                int[] pixels = new int[image.getWidth() * image.getHeight()];
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
                return new TexturePixels(pixels, image.getWidth(), image.getHeight());
            }
            catch (IOException ignored)
            {
                return null;
            }
        }
    }

    private record TexturePixels(int[] pixels, int width, int height, int averageSaturation)
    {
        private TexturePixels(int[] pixels, int width, int height)
        {
            this(pixels, width, height, computeAverageSaturation(pixels));
        }

        private static int computeAverageSaturation(int[] pixels)
        {
            long saturation = 0L;
            int count = 0;
            for (int argb : pixels)
            {
                if (((argb >>> 24) & 255) == 0) continue;
                saturation += rgbSaturation(argb);
                count++;
            }
            return count == 0 ? 0 : (int)(saturation / count);
        }

        private int sample(int x, int y, int pixelsPerBlock)
        {
            if (width <= 0 || height <= 0 || pixels.length == 0) return 0;
            int textureX = Math.min(width - 1, Math.max(0, x * width / pixelsPerBlock));
            int textureY = Math.min(height - 1, Math.max(0, y * height / pixelsPerBlock));
            return pixels[textureY * width + textureX];
        }
    }

    private record RenderFace(int x, int y, int z, AABB box, Direction direction, int argb, boolean fluid, BlockState state)
    {
        private int sortKey()
        {
            int directionOrder = switch (direction)
                    {
                        case UP -> 4;
                        case EAST -> 3;
                        case SOUTH -> 2;
                        case WEST -> 1;
                        case NORTH -> 0;
                        default -> direction.ordinal();
                    };
            return (x + z) * 65536 + y * 16 + directionOrder + (fluid ? 1 : 0);
        }
    }
}
