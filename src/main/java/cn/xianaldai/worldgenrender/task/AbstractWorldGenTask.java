package cn.xianaldai.worldgenrender.task;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** 范围型 WorldGenRender 任务公共基类。 */
public abstract class AbstractWorldGenTask implements WorldGenManagedTask
{
    protected final CommandSourceStack source;
    protected final ServerLevel level;
    protected final ChunkRange range;
    protected boolean done;
    protected boolean cancelled;
    protected int processedChunks;

    protected AbstractWorldGenTask(CommandSourceStack source, ServerLevel level, ChunkRange range)
    {
        this.source = source;
        this.level = level;
        this.range = range;
        source.sendSuccess(() -> Component.literal("已提交任务 " + name() + "，维度 " + level.dimension().location() + "，范围 " + range), true);
    }

    @Override public boolean isDone() { return done; }

    @Override
    public void cancel()
    {
        cancelled = true;
        done = true;
        source.sendSuccess(() -> Component.literal("任务 " + name() + " 已取消。"), true);
    }

    @Override
    public Component status()
    {
        return Component.literal(name() + " " + processedChunks + "/" + range.chunkCount() + " chunks");
    }
}