package cn.xianaldai.worldgenrender;

import cn.xianaldai.worldgenrender.command.WorldGenRenderCommands;
import cn.xianaldai.worldgenrender.network.WorldGenRenderNetwork;
import cn.xianaldai.worldgenrender.task.WorldGenRenderTaskManager;
import cn.xianaldai.worldgenrender.util.WorldGenOperations;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * @author xianaldai
 * @since 1.0.0
 */
@Mod(WorldGenRender.MODID)
public class WorldGenRender
{
    public static final String MODID = "worldgenrender";

    private static final Logger LOGGER = LogUtils.getLogger();

    public WorldGenRender(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    /**
     * 通用初始化阶段回调，客户端与服务端都会执行。
     *
     * @param event Forge 通用初始化事件。
     */
    private void commonSetup(final FMLCommonSetupEvent event)
    {
        event.enqueueWork(WorldGenRenderNetwork::register);
        LOGGER.info("WorldGenRender common setup started");
    }

    /**
     * 服务器启动事件回调。
     *
     * @param event 服务器启动事件。
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("WorldGenRender server starting");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        WorldGenRenderCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        WorldGenRenderTaskManager.shutdown();
        int pendingDeletes = WorldGenOperations.pendingDimensionDeletionCount();
        if (pendingDeletes > 0)
        {
            LOGGER.warn("WorldGenRender has {} dimension clear operation(s) queued; .mca files will be deleted after the server has stopped.", pendingDeletes);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event)
    {
        WorldGenOperations.DeleteSummary summary = WorldGenOperations.deletePendingDimensionContents();
        if (summary.deletedFiles() > 0 || summary.failedFiles() > 0)
        {
            LOGGER.warn("WorldGenRender deleted {} queued dimension .mca file(s), failed {} file(s).", summary.deletedFiles(), summary.failedFiles());
        }
    }
}