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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 导出目标区块范围内所有非空气方块的 OBJ 几何模型。 */
public final class WorldGenObjExportTask extends AbstractWorldGenTask
{
    private static final double EPSILON = 1.0E-6D;

    private final Path output;
    private final Path materialOutput;
    private final int minBlockX;
    private final int minBlockZ;
    private final int maxBlockX;
    private final int maxBlockZ;
    private final int minY;
    private final int maxY;
    private final int totalColumns;
    private final Map<String, MaterialInfo> materials = new LinkedHashMap<>();
    private final Map<BlockState, List<AABB>> shapeCache = new HashMap<>();
    private final Map<BlockState, String> materialNameCache = new HashMap<>();
    private final Map<String, Integer> vertexCache = new HashMap<>();
    private BufferedWriter writer;
    private int currentX;
    private int currentZ;
    private int processedColumns;
    private int vertexIndex = 1;
    private int exportedBlocks;
    private int exportedFaces;
    private String currentMaterial = "";
    private boolean started;
    private boolean failed;

    public WorldGenObjExportTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output)
    {
        super(source, level, range);
        this.output = output;
        this.materialOutput = replaceExtension(output, ".mtl");
        this.minBlockX = range.minChunkX() * 16;
        this.minBlockZ = range.minChunkZ() * 16;
        this.maxBlockX = range.maxChunkX() * 16 + 15;
        this.maxBlockZ = range.maxChunkZ() * 16 + 15;
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight() - 1;
        this.totalColumns = range.widthBlocks() * range.heightBlocks();
        this.currentX = minBlockX;
        this.currentZ = minBlockZ;
    }

    @Override public String name() { return "exportObj"; }

    @Override
    public void tick(MinecraftServer server)
    {
        if (done || failed) return;
        try
        {
            if (!started) start();

            int budget = Math.max(1, Config.maxBlockChangesPerTick / Math.max(1, maxY - minY + 1));
            while (!cancelled && budget-- > 0 && currentZ <= maxBlockZ)
            {
                exportColumn(currentX, currentZ);
                processedColumns++;
                processedChunks = Math.min(range.chunkCount(), processedColumns / 256);
                advanceColumn();
            }

            if (cancelled)
            {
                closeQuietly(writer);
                done = true;
                return;
            }

            if (currentZ > maxBlockZ)
            {
                finish();
            }
        }
        catch (Throwable throwable)
        {
            failed = true;
            done = true;
            closeQuietly(writer);
            source.sendFailure(Component.literal("exportObj 失败：" + throwable.getMessage()));
        }
    }

    @Override
    public Component status()
    {
        return Component.literal(name() + " columns=" + processedColumns + "/" + totalColumns
                + " blocks=" + exportedBlocks
                + " faces=" + exportedFaces
                + " mode=" + (Config.objExportIncludeInteriorFaces ? "all-faces" : "shape-exposed-faces"));
    }

    private void start() throws IOException
    {
        Files.createDirectories(output.getParent());
        writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
        writer.write("# WorldGenRender OBJ export\n");
        writer.write("# dimension " + level.dimension().location() + " range " + range + "\n");
        writer.write("# geometry is exported from server-side VoxelShape AABBs, not a single cube per block\n");
        writer.write("mtllib " + materialOutput.getFileName() + "\n");
        writer.write("o worldgenrender_" + sanitizeObjectName(level.dimension().location().toString()) + "\n");
        started = true;
    }

    private void exportColumn(int worldX, int worldZ) throws IOException
    {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(worldX, minY, worldZ);
        BlockPos.MutableBlockPos neighborMutable = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++)
        {
            mutable.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(mutable);
            if (!shouldExport(state)) continue;

            List<AABB> boxes = getBoxes(state, mutable);
            if (boxes.isEmpty()) continue;

            String material = materialName(state, mutable);
            useMaterial(material);
            exportedBlocks++;
            for (AABB box : boxes)
            {
                writeBox(worldX, y, worldZ, box, boxes, neighborMutable);
            }
        }
    }

    private List<AABB> getBoxes(BlockState state, BlockPos pos)
    {
        return shapeCache.computeIfAbsent(state, ignored -> {
            VoxelShape shape = state.getShape(level, pos);
            if (shape.isEmpty()) shape = state.getVisualShape(level, pos, net.minecraft.world.phys.shapes.CollisionContext.empty());
            if (shape.isEmpty()) shape = state.getOcclusionShape(level, pos);
            return shape.isEmpty() ? List.of() : List.copyOf(shape.toAabbs());
        });
    }

    private boolean shouldExport(BlockState state)
    {
        return !state.isAir() && state.getRenderShape() != RenderShape.INVISIBLE;
    }

    private void writeBox(int x, int y, int z, AABB box, List<AABB> sameBlockBoxes, BlockPos.MutableBlockPos neighborMutable) throws IOException
    {
        for (Direction direction : Direction.values())
        {
            if (Config.objExportIncludeInteriorFaces || shouldWriteBoxFace(x, y, z, box, sameBlockBoxes, direction, neighborMutable))
            {
                writeFace(x, y, z, box, direction);
                exportedFaces++;
            }
        }
    }

    private boolean shouldWriteBoxFace(int x, int y, int z, AABB box, List<AABB> sameBlockBoxes, Direction direction, BlockPos.MutableBlockPos neighborMutable)
    {
        if (sameBlockCoversFace(box, sameBlockBoxes, direction)) return false;
        if (!touchesBlockBoundary(box, direction)) return true;

        int nx = x + direction.getStepX();
        int ny = y + direction.getStepY();
        int nz = z + direction.getStepZ();
        if (nx < minBlockX || nx > maxBlockX || nz < minBlockZ || nz > maxBlockZ || ny < minY || ny > maxY) return true;

        neighborMutable.set(nx, ny, nz);
        BlockState neighbor = level.getBlockState(neighborMutable);
        return !neighborCoversFace(neighbor, neighborMutable, direction.getOpposite(), box);
    }

    private boolean sameBlockCoversFace(AABB sourceBox, List<AABB> boxes, Direction direction)
    {
        for (AABB other : boxes)
        {
            if (other == sourceBox) continue;
            if (!touchesSameBlockFace(sourceBox, other, direction)) continue;
            if (faceCoverageContains(sourceBox, other, direction)) return true;
        }
        return false;
    }

    private boolean touchesSameBlockFace(AABB source, AABB cover, Direction direction)
    {
        return switch (direction)
                {
                    case DOWN -> Math.abs(source.minY - cover.maxY) <= EPSILON;
                    case UP -> Math.abs(source.maxY - cover.minY) <= EPSILON;
                    case NORTH -> Math.abs(source.minZ - cover.maxZ) <= EPSILON;
                    case SOUTH -> Math.abs(source.maxZ - cover.minZ) <= EPSILON;
                    case WEST -> Math.abs(source.minX - cover.maxX) <= EPSILON;
                    case EAST -> Math.abs(source.maxX - cover.minX) <= EPSILON;
                };
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
        if (!shouldExport(neighbor)) return false;
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

    private void useMaterial(String material) throws IOException
    {
        if (material.equals(currentMaterial)) return;
        writer.write("usemtl " + material + "\n");
        currentMaterial = material;
    }

    private String materialName(BlockState state, BlockPos pos)
    {
        return materialNameCache.computeIfAbsent(state, ignored -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String name = sanitizeObjectName(id.toString());
            materials.computeIfAbsent(name, key -> new MaterialInfo(name, mapColorArgb(state, pos)));
            return name;
        });
    }

    private int mapColorArgb(BlockState state, BlockPos pos)
    {
        MapColor color = state.getMapColor(level, pos);
        if (color == MapColor.NONE) return 0xFF999999;
        int modifier = MapColor.Brightness.NORMAL.modifier;
        int r = ((color.col >> 16) & 255) * modifier / 255;
        int g = ((color.col >> 8) & 255) * modifier / 255;
        int b = (color.col & 255) * modifier / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void writeFace(int x, int y, int z, AABB box, Direction direction) throws IOException
    {
        double x0 = x - minBlockX + box.minX;
        double y0 = y - minY + box.minY;
        double z0 = z - minBlockZ + box.minZ;
        double x1 = x - minBlockX + box.maxX;
        double y1 = y - minY + box.maxY;
        double z1 = z - minBlockZ + box.maxZ;

        double[][] vertices = switch (direction)
                {
                    case UP -> new double[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                    case DOWN -> new double[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
                    case NORTH -> new double[][] {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
                    case SOUTH -> new double[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
                    case WEST -> new double[][] {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
                    case EAST -> new double[][] {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
                };

        int[] indices = new int[4];
        for (int i = 0; i < vertices.length; i++)
        {
            indices[i] = vertexIndex(vertices[i][0], vertices[i][1], vertices[i][2]);
        }
        writer.write("f " + indices[0] + " " + indices[1] + " " + indices[2] + " " + indices[3] + "\n");
    }

    private int vertexIndex(double x, double y, double z) throws IOException
    {
        String key = String.format(Locale.ROOT, "%.6f %.6f %.6f", x, y, z);
        Integer existing = vertexCache.get(key);
        if (existing != null) return existing;

        int index = vertexIndex++;
        vertexCache.put(key, index);
        writer.write("v " + key + "\n");
        return index;
    }

    private void advanceColumn()
    {
        currentX++;
        if (currentX > maxBlockX)
        {
            currentX = minBlockX;
            currentZ++;
        }
    }

    private void finish() throws IOException
    {
        writer.close();
        writeMaterials();
        done = true;
        source.sendSuccess(() -> Component.literal("exportObj 完成：" + output.toAbsolutePath()
                + "，材质库 " + materialOutput.toAbsolutePath()
                + "，导出方块 " + exportedBlocks
                + "，面 " + exportedFaces
                + "，模式 " + (Config.objExportIncludeInteriorFaces ? "包含内部面" : "按 VoxelShape 剔除外露面")), true);
    }

    private void writeMaterials() throws IOException
    {
        try (BufferedWriter materialWriter = Files.newBufferedWriter(materialOutput, StandardCharsets.UTF_8))
        {
            materialWriter.write("# WorldGenRender OBJ material colors\n");
            for (MaterialInfo material : materials.values())
            {
                int r = (material.argb >> 16) & 255;
                int g = (material.argb >> 8) & 255;
                int b = material.argb & 255;
                materialWriter.write("newmtl " + material.name + "\n");
                materialWriter.write(String.format(Locale.ROOT, "Kd %.6f %.6f %.6f\n", r / 255.0D, g / 255.0D, b / 255.0D));
                materialWriter.write("Ka 0.000000 0.000000 0.000000\n");
                materialWriter.write("Ks 0.000000 0.000000 0.000000\n");
                materialWriter.write("d 1.000000\n\n");
            }
        }
    }

    private static Path replaceExtension(Path path, String extension)
    {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String replaced = (dot >= 0 ? name.substring(0, dot) : name) + extension;
        Path parent = path.getParent();
        return parent == null ? Path.of(replaced) : parent.resolve(replaced);
    }

    private static String sanitizeObjectName(String name)
    {
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') builder.append(c);
            else builder.append('_');
        }
        return builder.toString();
    }

    private static void closeQuietly(Closeable closeable)
    {
        if (closeable == null) return;
        try
        {
            closeable.close();
        }
        catch (IOException ignored)
        {
        }
    }

    private record MaterialInfo(String name, int argb) {}
}
