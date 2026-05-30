package cn.xianaldai.worldgenrender.task;

import cn.xianaldai.worldgenrender.WorldGenRender;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 单活动任务队列：世界访问在服务器线程分片执行，PNG 编码交给后台线程。 */
@Mod.EventBusSubscriber(modid = WorldGenRender.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldGenRenderTaskManager
{
    private static final Queue<WorldGenManagedTask> QUEUE = new ArrayDeque<>();
    private static WorldGenManagedTask activeTask;
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WorldGenRender PNG Writer");
        thread.setDaemon(true);
        return thread;
    });

    private WorldGenRenderTaskManager() {}

    public static ExecutorService ioExecutor() { return IO_EXECUTOR; }

    public static synchronized void submit(WorldGenManagedTask task) { QUEUE.add(task); }

    public static synchronized int cancelAll()
    {
        int count = QUEUE.size();
        QUEUE.forEach(WorldGenManagedTask::cancel);
        QUEUE.clear();
        if (activeTask != null)
        {
            activeTask.cancel();
            activeTask = null;
            count++;
        }
        return count;
    }

    public static synchronized Component status()
    {
        String active = activeTask == null ? "无" : activeTask.status().getString();
        return Component.literal("WorldGenRender 当前任务：" + active + "；排队任务：" + QUEUE.size());
    }

    public static void shutdown()
    {
        cancelAll();
        IO_EXECUTOR.shutdownNow();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;

        WorldGenManagedTask task;
        synchronized (WorldGenRenderTaskManager.class)
        {
            if (activeTask == null) activeTask = QUEUE.poll();
            task = activeTask;
        }
        if (task == null) return;

        task.tick(event.getServer());
        if (task.isDone())
        {
            synchronized (WorldGenRenderTaskManager.class)
            {
                if (activeTask == task) activeTask = null;
            }
        }
    }
}