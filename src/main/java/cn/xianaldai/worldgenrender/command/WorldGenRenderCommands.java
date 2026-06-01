package cn.xianaldai.worldgenrender.command;

import cn.xianaldai.worldgenrender.Config;
import cn.xianaldai.worldgenrender.WorldGenRender;
import cn.xianaldai.worldgenrender.network.ClientBoxRenderRequestPacket;
import cn.xianaldai.worldgenrender.network.WorldGenRenderNetwork;
import cn.xianaldai.worldgenrender.task.ChunkRange;
import cn.xianaldai.worldgenrender.task.WorldGenRenderTaskManager;
import cn.xianaldai.worldgenrender.task.render.WorldGenObjExportTask;
import cn.xianaldai.worldgenrender.task.render.WorldGenRender3dTask;
import cn.xianaldai.worldgenrender.task.render.WorldGenRenderTask;
import cn.xianaldai.worldgenrender.task.worldgen.WorldGenClearDimensionTask;
import cn.xianaldai.worldgenrender.task.worldgen.WorldGenClearTask;
import cn.xianaldai.worldgenrender.task.worldgen.WorldGenPreloadTask;
import cn.xianaldai.worldgenrender.task.worldgen.WorldGenResetSavedTask;
import cn.xianaldai.worldgenrender.util.WorldGenOperations;
import cn.xianaldai.worldgenrender.util.WorldGenSelectionManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.nio.file.Path;

/** 注册并实现 WorldGenRender 的服务端命令树。 */
public final class WorldGenRenderCommands
{
    private WorldGenRenderCommands() {}

    /** 注册 /worldgenrender 命令。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal(WorldGenRender.MODID)
                .requires(source -> source.hasPermission(Config.commandPermissionLevel))
                .then(tasksCommand())
                .then(cancelCommand())
                .then(renderCommand())
                .then(render3dCommand())
                .then(exportObjCommand())
                .then(preloadCommand())
                .then(clearRangeCommand())
                .then(clearDimensionCommand())
                .then(resetSavedCommand()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tasksCommand()
    {
        return Commands.literal("tasks").executes(context -> {
            context.getSource().sendSuccess(() -> WorldGenRenderTaskManager.status(), false);
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cancelCommand()
    {
        return Commands.literal("cancel").executes(context -> {
            int cancelled = WorldGenRenderTaskManager.cancelAll();
            context.getSource().sendSuccess(() -> Component.literal("已取消 " + cancelled + " 个 WorldGenRender 任务。"), true);
            return cancelled;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> renderCommand()
    {
        return Commands.literal("render")
                .then(dimensionArgument()
                        .then(renderRangeArguments(WorldGenRenderCommands::render)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> render3dCommand()
    {
        return Commands.literal("render3d")
                .then(Commands.literal("orientation")
                        .then(Commands.argument("orientation", StringArgumentType.word())
                                .executes(WorldGenRenderCommands::setRender3dOrientation)))
                .then(Commands.literal("quality")
                        .then(Commands.argument("quality", StringArgumentType.word())
                                .executes(WorldGenRenderCommands::setRender3dQuality)))
                .then(Commands.literal("cutaway")
                        .then(orientationLiteralArguments((context, orientation) -> Commands.literal(orientation.name().toLowerCase())
                                .then(dimensionArgument()
                                        .then(renderRangeArguments((rangeContext, fileName) -> render3d(rangeContext, fileName, true, orientation))))))
                        .then(dimensionArgument()
                                .then(renderRangeArguments((context, fileName) -> render3d(context, fileName, true, Config.render3dOrientation)))))
                .then(Commands.literal("box")
                        .then(orientationLiteralArguments((context, orientation) -> Commands.literal(orientation.name().toLowerCase())
                                .executes(boxContext -> renderSelectedBox(boxContext, null, orientation))
                                .then(Commands.argument("file", StringArgumentType.greedyString())
                                        .executes(boxContext -> renderSelectedBox(boxContext, StringArgumentType.getString(boxContext, "file"), orientation)))))
                        .executes(context -> renderSelectedBox(context, null, Config.render3dOrientation))
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(context -> renderSelectedBox(context, StringArgumentType.getString(context, "file"), Config.render3dOrientation))))
                .then(orientationLiteralArguments((context, orientation) -> Commands.literal(orientation.name().toLowerCase())
                        .then(dimensionArgument()
                                .then(renderRangeArguments((rangeContext, fileName) -> render3d(rangeContext, fileName, false, orientation))))))
                .then(dimensionArgument()
                        .then(renderRangeArguments((context, fileName) -> render3d(context, fileName, false, Config.render3dOrientation))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> exportObjCommand()
    {
        return Commands.literal("exportObj")
                .then(dimensionArgument()
                        .then(renderRangeArguments(WorldGenRenderCommands::exportObj)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> preloadCommand()
    {
        return Commands.literal("preload")
                .then(dimensionArgument()
                        .then(rangeArguments(WorldGenRenderCommands::preload)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> clearRangeCommand()
    {
        return Commands.literal("clear")
                .then(dimensionArgument()
                        .then(clearRangeArguments())
                        .then(Commands.literal("all")
                                .then(Commands.literal("confirm")
                                        .executes(WorldGenRenderCommands::clearDimension))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> clearDimensionCommand()
    {
        return Commands.literal("clearDimension")
                .then(dimensionArgument()
                        .then(Commands.literal("confirm")
                                .executes(WorldGenRenderCommands::clearDimension)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resetSavedCommand()
    {
        return Commands.literal("resetSaved")
                .then(dimensionArgument()
                        .then(rangeArguments(WorldGenRenderCommands::resetSaved)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> dimensionArgument()
    {
        return Commands.argument("dimension", ResourceLocationArgument.id());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> rangeArguments(Command<CommandSourceStack> command)
    {
        return Commands.argument("fromChunkX", IntegerArgumentType.integer())
                .then(Commands.argument("fromChunkZ", IntegerArgumentType.integer())
                        .then(Commands.argument("toChunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("toChunkZ", IntegerArgumentType.integer())
                                        .executes(command))));
    }

    private interface RenderCommandExecutor
    {
        int run(CommandContext<CommandSourceStack> context, String fileName);
    }

    private interface OrientationCommandFactory
    {
        LiteralArgumentBuilder<CommandSourceStack> create(CommandContext<CommandSourceStack> context, Config.Render3dOrientation orientation);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> orientationLiteralArguments(OrientationCommandFactory factory)
    {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ne");
        root.redirect(factory.create(null, Config.Render3dOrientation.NE).build());
        return Commands.literal("orientation")
                .then(factory.create(null, Config.Render3dOrientation.NE))
                .then(factory.create(null, Config.Render3dOrientation.NW))
                .then(factory.create(null, Config.Render3dOrientation.SW))
                .then(factory.create(null, Config.Render3dOrientation.SE));
    }

    private static int setRender3dOrientation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        String value = StringArgumentType.getString(context, "orientation");
        Config.render3dOrientation = Config.Render3dOrientation.parse(value);
        context.getSource().sendSuccess(() -> Component.literal("WorldGenRender render3d 默认正交取向已切换为 " + Config.render3dOrientation.name() + "。命令中也可使用 orientation ne/nw/sw/se 临时指定。"), true);
        return 1;
    }

    private static int setRender3dQuality(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        String value = StringArgumentType.getString(context, "quality");
        Config.render3dResolution = Config.Render3dResolution.parse(value);
        context.getSource().sendSuccess(() -> Component.literal("WorldGenRender render3d 热命令精度已切换为 " + Config.render3dResolution.label() + "(" + Config.render3dResolution.pixelsPerBlock() + "px/unit)。"), true);
        return 1;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> renderRangeArguments(RenderCommandExecutor executor)
    {
        return Commands.argument("fromChunkX", IntegerArgumentType.integer())
                .then(Commands.argument("fromChunkZ", IntegerArgumentType.integer())
                        .then(Commands.argument("toChunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("toChunkZ", IntegerArgumentType.integer())
                                        .executes(context -> executor.run(context, null))
                                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                                .executes(context -> executor.run(context, StringArgumentType.getString(context, "file")))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> clearRangeArguments()
    {
        return Commands.argument("fromChunkX", IntegerArgumentType.integer())
                .then(Commands.argument("fromChunkZ", IntegerArgumentType.integer())
                        .then(Commands.argument("toChunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("toChunkZ", IntegerArgumentType.integer())
                                        .executes(context -> clear(context, false))
                                        .then(Commands.argument("saveAfter", BoolArgumentType.bool())
                                                .executes(context -> clear(context, BoolArgumentType.getBool(context, "saveAfter")))))));
    }

    private static int render(CommandContext<CommandSourceStack> context, String fileName)
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = getLevel(context);
        ChunkRange range = getRange(context);
        if (!validateChunkCount(source, range, Config.maxRenderChunks, "render")) return 0;

        Path output = WorldGenOperations.resolveOutputPath(source.getServer(), level, range, fileName, ".png");
        WorldGenRenderTaskManager.submit(new WorldGenRenderTask(source, level, range, output));
        return range.chunkCount();
    }

    private static int render3d(CommandContext<CommandSourceStack> context, String fileName, boolean cutaway, Config.Render3dOrientation orientation)
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = getLevel(context);
        ChunkRange range = getRange(context);
        if (!validateChunkCount(source, range, Config.maxRenderChunks, cutaway ? "render3d cutaway" : "render3d")) return 0;

        ServerPlayer player = source.getPlayer();
        if (player != null && player.level() == level && WorldGenRenderNetwork.CHANNEL.isRemotePresent(player.connection.connection))
        {
            BlockPos first = new BlockPos(range.minChunkX() << 4, level.getMinBuildHeight(), range.minChunkZ() << 4);
            BlockPos second = new BlockPos((range.maxChunkX() << 4) + 15, level.getMaxBuildHeight() - 1, (range.maxChunkZ() << 4) + 15);
            String clientFileName = fileName == null || fileName.isBlank() ? defaultClientRange3dFileName(level, range, cutaway) : fileName;
            WorldGenRenderNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ClientBoxRenderRequestPacket(
                    level.dimension().location(),
                    first,
                    second,
                    player.getYRot(),
                    player.getXRot(),
                    player.getX(),
                    player.getEyeY(),
                    player.getZ(),
                    false,
                    cutaway,
                    orientation.name(),
                    clientFileName));
            source.sendSuccess(() -> Component.literal("已请求客户端按正交捕获画布渲染指定 chunk 范围内方块的 " + (cutaway ? "render3d cutaway" : "render3d") + " PNG；取向 " + orientation.name() + "；输出位于客户端 screenshots/worldgenrender 目录。"), false);
            return range.chunkCount();
        }

        Path output = WorldGenOperations.resolveOutputPath(source.getServer(), level, range, fileName, ".png");
        if (player != null)
        {
            source.sendSuccess(() -> Component.literal("无法使用客户端正交捕获（玩家不在目标维度或客户端没有 WorldGenRender 网络通道），改用服务端正交 3D 渲染。"), false);
        }
        WorldGenRenderTaskManager.submit(new WorldGenRender3dTask(source, level, range, output, cutaway, orientation.yawDegrees()));
        return range.chunkCount();
    }

    private static int renderSelectedBox(CommandContext<CommandSourceStack> context, String fileName, Config.Render3dOrientation orientation)
    {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try
        {
            player = source.getPlayerOrException();
        }
        catch (Exception exception)
        {
            source.sendFailure(Component.literal("render3d box 需要由玩家执行，因为选区由铁粒右键方块设置。"));
            return 0;
        }

        WorldGenSelectionManager.Selection selection = WorldGenSelectionManager.selection(player);
        if (selection == null || !selection.isComplete())
        {
            source.sendFailure(Component.literal("尚未设置完整选区：手持铁粒右键两个方块分别设置点 1 和点 2；潜行右键可清除后重选。"));
            return 0;
        }

        ServerLevel level = source.getServer().getLevel(selection.dimension());
        if (level == null)
        {
            source.sendFailure(Component.literal("选区所在维度不存在：" + selection.dimension().location()));
            return 0;
        }
        if (player.level() != level)
        {
            source.sendFailure(Component.literal("当前玩家不在选区维度：" + selection.dimension().location()));
            return 0;
        }

        BlockPos first = selection.first();
        BlockPos second = selection.second();
        int minY = Math.max(level.getMinBuildHeight(), Math.min(first.getY(), second.getY()));
        int maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(first.getY(), second.getY()));
        if (minY > maxY)
        {
            source.sendFailure(Component.literal("render3d box 的 Y 范围不在当前维度高度内。"));
            return 0;
        }

        ChunkRange range = ChunkRange.of(SectionPos.blockToSectionCoord(first.getX()), SectionPos.blockToSectionCoord(first.getZ()), SectionPos.blockToSectionCoord(second.getX()), SectionPos.blockToSectionCoord(second.getZ()));
        if (!validateChunkCount(source, range, Config.maxRenderChunks, "render3d box")) return 0;

        if (WorldGenRenderNetwork.CHANNEL.isRemotePresent(player.connection.connection))
        {
            String clientFileName = fileName == null || fileName.isBlank() ? defaultClientBoxFileName(level, first, second) : fileName;
            WorldGenRenderNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ClientBoxRenderRequestPacket(
                    selection.dimension().location(),
                    first,
                    second,
                    player.getYRot(),
                    player.getXRot(),
                    player.getX(),
                    player.getEyeY(),
                    player.getZ(),
                    true,
                    true,
                    orientation.name(),
                    clientFileName));
            source.sendSuccess(() -> Component.literal("已请求客户端使用正交离屏画布保存 render3d box PNG；取向 " + orientation.name() + "；输出位于客户端 screenshots/worldgenrender 目录。"), false);
            return range.chunkCount();
        }

        Path output = WorldGenOperations.resolveOutputPath(source.getServer(), level, range, fileName, ".png");
        source.sendSuccess(() -> Component.literal("执行玩家客户端没有 WorldGenRender 网络通道，改用服务端正交 3D 渲染。"), false);
        WorldGenRenderTaskManager.submit(new WorldGenRender3dTask(source, level, range, output, orientation.yawDegrees(), 60.0F, player.getX(), player.getEyeY(), player.getZ(), first.getX(), first.getY(), first.getZ(), second.getX(), second.getY(), second.getZ()));
        return range.chunkCount();
    }

    private static String defaultClientBoxFileName(ServerLevel level, BlockPos first, BlockPos second)
    {
        return level.dimension().location().toString().replace(':', '_') + "_box_"
                + first.getX() + "_" + first.getY() + "_" + first.getZ() + "_"
                + second.getX() + "_" + second.getY() + "_" + second.getZ() + ".png";
    }

    private static String defaultClientRange3dFileName(ServerLevel level, ChunkRange range, boolean cutaway)
    {
        return level.dimension().location().toString().replace(':', '_')
                + (cutaway ? "_cutaway_" : "_3d_")
                + range.minChunkX() + "_" + range.minChunkZ() + "_"
                + range.maxChunkX() + "_" + range.maxChunkZ() + ".png";
    }

    private static int exportObj(CommandContext<CommandSourceStack> context, String fileName)
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = getLevel(context);
        ChunkRange range = getRange(context);
        if (!validateChunkCount(source, range, Config.maxRenderChunks, "exportObj")) return 0;

        Path output = WorldGenOperations.resolveOutputPath(source.getServer(), level, range, fileName, ".obj");
        WorldGenRenderTaskManager.submit(new WorldGenObjExportTask(source, level, range, output));
        return range.chunkCount();
    }

    private static int preload(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = getLevel(context);
        ChunkRange range = getRange(context);
        if (!validateChunkCount(source, range, Config.maxWorldgenChunks, "preload")) return 0;

        WorldGenRenderTaskManager.submit(new WorldGenPreloadTask(source, level, range));
        return range.chunkCount();
    }

    private static int clear(CommandContext<CommandSourceStack> context, boolean saveAfter)
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = getLevel(context);
        ChunkRange range = getRange(context);
        if (!validateChunkCount(source, range, Config.maxWorldgenChunks, "clear")) return 0;

        WorldGenRenderTaskManager.submit(new WorldGenClearTask(source, level, range, saveAfter));
        return range.chunkCount();
    }

    private static int clearDimension(CommandContext<CommandSourceStack> context)
    {
        context.getSource().sendSuccess(() -> Component.literal("警告：clearDimension 是在线热重生成：会临时把目标维度玩家切为旁观、释放玩家 ticket，并清空该维度磁盘中的 chunk/entities/poi 条目；玩家附近区块不会再跳过。"), true);
        ServerLevel level = getLevel(context);
        WorldGenRenderTaskManager.submit(new WorldGenClearDimensionTask(context.getSource(), level));
        return 1;
    }

    private static int resetSaved(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = getLevel(context);
        ChunkRange range = getRange(context);
        if (!validateChunkCount(source, range, Config.maxWorldgenChunks, "resetSaved")) return 0;

        WorldGenRenderTaskManager.submit(new WorldGenResetSavedTask(source, level, range));
        return range.chunkCount();
    }

    private static ServerLevel getLevel(CommandContext<CommandSourceStack> context)
    {
        ResourceLocation id = ResourceLocationArgument.getId(context, "dimension");
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel level = context.getSource().getServer().getLevel(key);
        if (level == null)
        {
            throw new IllegalArgumentException("未知维度：" + id);
        }
        return level;
    }

    private static ChunkRange getRange(CommandContext<CommandSourceStack> context)
    {
        return ChunkRange.of(
                IntegerArgumentType.getInteger(context, "fromChunkX"),
                IntegerArgumentType.getInteger(context, "fromChunkZ"),
                IntegerArgumentType.getInteger(context, "toChunkX"),
                IntegerArgumentType.getInteger(context, "toChunkZ"));
    }

    private static boolean validateChunkCount(CommandSourceStack source, ChunkRange range, int limit, String commandName)
    {
        if (range.chunkCount() > limit)
        {
            source.sendFailure(Component.literal("范围包含 " + range.chunkCount() + " 个区块，超过 " + commandName + " 限制 " + limit + "。请缩小范围或调整配置。"));
            return false;
        }
        return true;
    }
}
