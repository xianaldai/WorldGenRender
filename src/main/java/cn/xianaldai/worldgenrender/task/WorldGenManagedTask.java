package cn.xianaldai.worldgenrender.task;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** 可由任务管理器分片执行的服务端任务。 */
public interface WorldGenManagedTask
{
    String name();
    void tick(MinecraftServer server);
    boolean isDone();
    void cancel();
    Component status();
}