package cn.xianaldai.worldgenrender.task;

/** 表示闭区间二维区块范围。 */
public record ChunkRange(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)
{
    public static ChunkRange of(int x1, int z1, int x2, int z2)
    {
        return new ChunkRange(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public int widthChunks() { return maxChunkX - minChunkX + 1; }
    public int heightChunks() { return maxChunkZ - minChunkZ + 1; }
    public int widthBlocks() { return widthChunks() * 16; }
    public int heightBlocks() { return heightChunks() * 16; }
    public int chunkCount() { return widthChunks() * heightChunks(); }

    @Override
    public String toString()
    {
        return "[" + minChunkX + "," + minChunkZ + "] -> [" + maxChunkX + "," + maxChunkZ + "] (" + chunkCount() + " chunks)";
    }
}