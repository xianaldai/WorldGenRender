package cn.xianaldai.worldgenrender.task.render;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.task.AbstractWorldGenTask;
import cn.xianaldai.worldgenrender.task.ChunkRange;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** 从服务端世界采样区块顶视图，并以流式 PNG 写入，避免整张大图常驻内存。 */
public final class WorldGenRenderTask extends AbstractWorldGenTask
{
    private final Path output;
    private final int pixelsPerBlock;
    private final int imageWidth;
    private final int imageHeight;
    private final int stripeRows;
    private final int[] stripePixels;
    private final TextureResolver textureResolver = new TextureResolver();
    private PngStreamWriter pngWriter;
    private int currentX;
    private int currentZ;
    private boolean streamStarted;
    private boolean failed;

    public WorldGenRenderTask(CommandSourceStack source, ServerLevel level, ChunkRange range, Path output)
    {
        super(source, level, range);
        this.output = output;
        this.pixelsPerBlock = Math.max(1, Config.renderPixelsPerBlock);
        this.imageWidth = Math.multiplyExact(range.widthBlocks(), pixelsPerBlock);
        this.imageHeight = Math.multiplyExact(range.heightBlocks(), pixelsPerBlock);
        this.stripeRows = Math.multiplyExact(16, pixelsPerBlock);
        this.stripePixels = new int[Math.multiplyExact(imageWidth, stripeRows)];
        this.currentX = range.minChunkX();
        this.currentZ = range.minChunkZ();
    }

    @Override public String name() { return "render"; }

    @Override
    public void tick(MinecraftServer server)
    {
        if (done || failed) return;

        try
        {
            if (!streamStarted)
            {
                startStreaming();
            }

            int budget = Config.maxChunksPerTick;
            while (!cancelled && budget-- > 0 && currentZ <= range.maxChunkZ())
            {
                sampleChunkIntoStripe(currentX, currentZ);
                processedChunks++;
                advanceAndFlushCompletedStripe();
            }

            if (cancelled)
            {
                closeQuietly(pngWriter);
                done = true;
                return;
            }

            if (currentZ > range.maxChunkZ())
            {
                finishStreaming();
            }
        }
        catch (Throwable throwable)
        {
            failed = true;
            done = true;
            closeQuietly(pngWriter);
            source.sendFailure(Component.literal("render PNG 流式写入失败：" + throwable.getMessage()));
        }
    }

    @Override
    public Component status()
    {
        return Component.literal(name() + " " + processedChunks + "/" + range.chunkCount() + " chunks"
                + " scale=" + pixelsPerBlock + "px/block"
                + " mode=" + (Config.renderUseBlockTextures ? "texture" : "map-color")
                + " image=" + imageWidth + "x" + imageHeight
                + " streaming-row=" + Math.max(0, currentZ - range.minChunkZ()) + "/" + range.heightChunks());
    }

    private void startStreaming() throws IOException
    {
        Files.createDirectories(output.getParent());
        pngWriter = new PngStreamWriter(output, imageWidth, imageHeight);
        streamStarted = true;
    }

    private void finishStreaming() throws IOException
    {
        pngWriter.close();
        done = true;
        source.sendSuccess(() -> Component.literal("render 完成：" + output.toAbsolutePath()
                + "，比例 " + pixelsPerBlock + " 像素/方块，图片尺寸 " + imageWidth + "x" + imageHeight
                + "，模式 " + (Config.renderUseBlockTextures ? "材质优先" : "地图色块")
                ), true);
    }

    private void sampleChunkIntoStripe(int chunkX, int chunkZ)
    {
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++)
        {
            for (int localX = 0; localX < 16; localX++)
            {
                int worldX = chunkPos.getMinBlockX() + localX;
                int worldZ = chunkPos.getMinBlockZ() + localZ;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
                int fallbackArgb = 0;
                BlockState state = null;
                if (y >= level.getMinBuildHeight())
                {
                    mutable.set(worldX, y, worldZ);
                    state = level.getBlockState(mutable);
                    if (!state.isAir())
                    {
                        MapColor color = state.getMapColor(level, mutable);
                        if (color != MapColor.NONE) fallbackArgb = toArgb(color, MapColor.Brightness.NORMAL);
                    }
                }
                int blockImageX = (chunkX - range.minChunkX()) * 16 + localX;
                int blockStripeZ = localZ;
                fillBlockPixels(blockImageX, blockStripeZ, worldX, worldZ, y);
            }
        }
    }

    private void fillBlockPixels(int blockImageX, int blockStripeZ, int worldX, int worldZ, int topY)
    {
        int startX = blockImageX * pixelsPerBlock;
        int startZ = blockStripeZ * pixelsPerBlock;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < pixelsPerBlock; dz++)
        {
            int row = (startZ + dz) * imageWidth + startX;
            for (int dx = 0; dx < pixelsPerBlock; dx++)
            {
                stripePixels[row + dx] = sampleColumnPixel(worldX, worldZ, topY, dx, dz, mutable);
            }
        }
    }

    private int sampleColumnPixel(int worldX, int worldZ, int topY, int pixelX, int pixelZ, BlockPos.MutableBlockPos mutable)
    {
        if (topY < level.getMinBuildHeight()) return 0;

        int outA = 0;
        int outR = 0;
        int outG = 0;
        int outB = 0;
        int minY = level.getMinBuildHeight();
        for (int y = topY; y >= minY && outA < 255; y--)
        {
            mutable.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(mutable);
            if (state.isAir()) continue;

            int argb = sampleStatePixel(state, mutable, pixelX, pixelZ);
            int alpha = (argb >>> 24) & 255;
            if (alpha == 0) continue;

            int remaining = 255 - outA;
            int contribution = alpha * remaining / 255;
            outR += ((argb >> 16) & 255) * contribution;
            outG += ((argb >> 8) & 255) * contribution;
            outB += (argb & 255) * contribution;
            outA += contribution;
        }

        if (outA <= 0) return 0;
        return (outA << 24)
                | (Math.min(255, outR / outA) << 16)
                | (Math.min(255, outG / outA) << 8)
                | Math.min(255, outB / outA);
    }

    private int sampleStatePixel(BlockState state, BlockPos pos, int pixelX, int pixelZ)
    {
        int fallbackArgb = 0;
        MapColor color = state.getMapColor(level, pos);
        if (color != MapColor.NONE) fallbackArgb = toArgb(color, MapColor.Brightness.NORMAL);

        TexturePixels texture = Config.renderUseBlockTextures && !state.isAir() ? textureResolver.resolve(state) : null;
        int argb = texture == null ? fallbackArgb : texture.sample(pixelX, pixelZ, pixelsPerBlock);
        if (texture != null && shouldApplyGenericTint(texture, fallbackArgb))
        {
            argb = multiplyRgb(argb, fallbackArgb);
        }
        if (((argb >>> 24) & 255) == 0 && Config.renderTextureFallbackToMapColor)
        {
            argb = fallbackArgb;
        }
        return argb;
    }

    private boolean shouldApplyGenericTint(TexturePixels texture, int fallbackArgb)
    {
        if (((fallbackArgb >>> 24) & 255) == 0) return false;
        return texture.averageSaturation() < 24 && rgbSaturation(fallbackArgb) > 32;
    }

    private int rgbSaturation(int argb)
    {
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min;
    }

    private int multiplyRgb(int argb, int tintArgb)
    {
        int alpha = (argb >>> 24) & 255;
        if (alpha == 0) return argb;
        int r = ((argb >> 16) & 255) * ((tintArgb >> 16) & 255) / 255;
        int g = ((argb >> 8) & 255) * ((tintArgb >> 8) & 255) / 255;
        int b = (argb & 255) * (tintArgb & 255) / 255;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private int toArgb(MapColor color, MapColor.Brightness brightness)
    {
        int modifier = brightness.modifier;
        int r = ((color.col >> 16) & 255) * modifier / 255;
        int g = ((color.col >> 8) & 255) * modifier / 255;
        int b = (color.col & 255) * modifier / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void advanceAndFlushCompletedStripe() throws IOException
    {
        currentX++;
        if (currentX > range.maxChunkX())
        {
            pngWriter.writeArgbRows(stripePixels, stripeRows);
            clearStripe();
            currentX = range.minChunkX();
            currentZ++;
        }
    }

    private void clearStripe()
    {
        java.util.Arrays.fill(stripePixels, 0);
    }

    private void closeQuietly(Closeable closeable)
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

    private static final class TextureResolver
    {
        private static final TexturePixels MISSING = new TexturePixels(new int[0], 0, 0);
        private final Map<String, TexturePixels> cache = new HashMap<>();

        private TexturePixels resolve(BlockState state)
        {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String key = blockId.toString();
            TexturePixels cached = cache.get(key);
            if (cached != null) return cached == MISSING ? null : cached;

            TexturePixels texture = loadTexture(blockId);
            cache.put(key, texture == null ? MISSING : texture);
            return texture;
        }

        private TexturePixels loadTexture(ResourceLocation blockId)
        {
            String namespace = blockId.getNamespace();
            String path = blockId.getPath();
            String[] candidates = new String[] {
                    path + "_top",
                    path,
                    path + "_side",
                    path.replace("_block", "") + "_top",
                    path.replace("_block", ""),
                    path.replace("_wall", "") + "_top",
                    path.replace("_stairs", ""),
                    path.replace("_slab", "")
            };
            for (String candidate : candidates)
            {
                TexturePixels texture = readClasspathTexture(namespace, candidate);
                if (texture != null) return texture;
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

    private static final class TexturePixels
    {
        private final int[] pixels;
        private final int width;
        private final int height;
        private final int averageSaturation;

        private TexturePixels(int[] pixels, int width, int height)
        {
            this.pixels = keepGameAlphaPixels(pixels);
            this.width = width;
            this.height = height;
            this.averageSaturation = computeAverageSaturation(this.pixels);
        }

        private static int[] keepGameAlphaPixels(int[] source)
        {
            int[] copy = new int[source.length];
            System.arraycopy(source, 0, copy, 0, source.length);
            return copy;
        }

        private static int computeAverageSaturation(int[] pixels)
        {
            long saturation = 0;
            int count = 0;
            for (int argb : pixels)
            {
                if (((argb >>> 24) & 255) == 0) continue;
                int r = (argb >> 16) & 255;
                int g = (argb >> 8) & 255;
                int b = argb & 255;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                saturation += max - min;
                count++;
            }
            return count == 0 ? 0 : (int) (saturation / count);
        }

        private int averageSaturation()
        {
            return averageSaturation;
        }

        private int sample(int x, int z, int pixelsPerBlock)
        {
            if (width <= 0 || height <= 0 || pixels.length == 0) return 0;
            int textureX = Math.min(width - 1, Math.max(0, x * width / pixelsPerBlock));
            int textureZ = Math.min(height - 1, Math.max(0, z * height / pixelsPerBlock));
            return pixels[textureZ * width + textureX];
        }
    }

    /** 极简 PNG 流式写入器：按 scanline 写入 RGBA 数据，不创建整张 BufferedImage。 */
    private static final class PngStreamWriter implements Closeable
    {
        private static final byte[] PNG_SIGNATURE = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

        private final DataOutputStream output;
        private final ByteArrayOutputStream compressedBuffer = new ByteArrayOutputStream(1 << 20);
        private final DeflaterOutputStream deflaterOutput;
        private final byte[] rowBuffer;
        private final int width;
        private final int height;
        private int rowsWritten;
        private boolean closed;

        private PngStreamWriter(Path path, int width, int height) throws IOException
        {
            this.width = width;
            this.height = height;
            this.output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
            this.deflaterOutput = new DeflaterOutputStream(compressedBuffer, new Deflater(Deflater.DEFAULT_COMPRESSION), true);
            this.rowBuffer = new byte[Math.multiplyExact(width, 4) + 1];
            output.write(PNG_SIGNATURE);
            writeIhdr();
        }

        private void writeArgbRows(int[] argbRows, int rowCount) throws IOException
        {
            for (int row = 0; row < rowCount; row++)
            {
                if (rowsWritten >= height) return;
                rowBuffer[0] = 0;
                int srcOffset = row * width;
                int dst = 1;
                for (int x = 0; x < width; x++)
                {
                    int argb = argbRows[srcOffset + x];
                    rowBuffer[dst++] = (byte) ((argb >> 16) & 255);
                    rowBuffer[dst++] = (byte) ((argb >> 8) & 255);
                    rowBuffer[dst++] = (byte) (argb & 255);
                    rowBuffer[dst++] = (byte) ((argb >>> 24) & 255);
                }
                deflaterOutput.write(rowBuffer);
                rowsWritten++;
                flushIdatIfLarge(false);
            }
        }

        @Override
        public void close() throws IOException
        {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try
            {
                while (rowsWritten < height)
                {
                    java.util.Arrays.fill(rowBuffer, (byte) 0);
                    deflaterOutput.write(rowBuffer);
                    rowsWritten++;
                }
                deflaterOutput.finish();
                flushIdatIfLarge(true);
                writeChunk("IEND", new byte[0]);
            }
            catch (IOException exception)
            {
                failure = exception;
                throw exception;
            }
            finally
            {
                try
                {
                    output.close();
                }
                catch (IOException closeFailure)
                {
                    if (failure == null) throw closeFailure;
                }
            }
        }

        private void writeIhdr() throws IOException
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(13);
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(width);
            data.writeInt(height);
            data.writeByte(8);
            data.writeByte(6);
            data.writeByte(0);
            data.writeByte(0);
            data.writeByte(0);
            writeChunk("IHDR", bytes.toByteArray());
        }

        private void flushIdatIfLarge(boolean force) throws IOException
        {
            if (!force && compressedBuffer.size() < (1 << 20)) return;
            if (compressedBuffer.size() <= 0) return;
            writeChunk("IDAT", compressedBuffer.toByteArray());
            compressedBuffer.reset();
        }

        private void writeChunk(String type, byte[] data) throws IOException
        {
            byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            output.writeInt(data.length);
            output.write(typeBytes);
            output.write(data);
            output.writeInt((int) crc.getValue());
        }
    }
}
