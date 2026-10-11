package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import com.thunder.locatefixer.util.CommandErrorFixer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Optional travel is a child of the version's existing native target argument. */
public final class LocateTeleportCommand {
    private static final DynamicCommandExceptionType INVALID_STRUCTURE = new DynamicCommandExceptionType(
            target -> Component.translatable("commands.locate.structure.invalid", target));

    private LocateTeleportCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        boolean ownsLocate = LocateRuntime.shouldInterceptVanillaLocate();
        if (nativeArgument(dispatcher, "structure", ResourceOrTagKeyArgument.class)) {
            LocateTeleportArgument.append(dispatcher, "structure", ownsLocate,
                    LocateTeleportCommand::allowed, LocateTeleportCommand::structure);
        }
        if (nativeArgument(dispatcher, "biome", ResourceOrTagArgument.class)) {
            LocateTeleportArgument.append(dispatcher, "biome", ownsLocate,
                    LocateTeleportCommand::allowed, LocateTeleportCommand::biome);
        }
        if (nativeArgument(dispatcher, "poi", ResourceOrTagArgument.class)) {
            LocateTeleportArgument.append(dispatcher, "poi", ownsLocate,
                    LocateTeleportCommand::allowed, LocateTeleportCommand::poi);
        }
    }

    private static boolean nativeArgument(CommandDispatcher<CommandSourceStack> dispatcher, String type, Class<?> parser) {
        CommandNode<CommandSourceStack> locate = dispatcher.getRoot().getChild("locate");
        CommandNode<CommandSourceStack> branch = locate == null ? null : locate.getChild(type);
        CommandNode<CommandSourceStack> target = branch == null ? null : branch.getChild(type);
        return target instanceof ArgumentCommandNode<?, ?> argument && parser.isInstance(argument.getType());
    }

    private static boolean allowed(CommandSourceStack source) {
        return source.hasPermission(2) && LocateRuntime.shouldInterceptVanillaLocate();
    }

    private static int structure(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = playerSource(context);
        ResourceOrTagKeyArgument.Result<Structure> target = ResourceOrTagKeyArgument.getResourceOrTagKey(
                context, "structure", Registries.STRUCTURE, INVALID_STRUCTURE);
        if (CommandErrorFixer.sendUnknownStructure(source, target)) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        AsyncLocateHandler.locateStructureAsync(source, target, BlockPos.containing(source.getPosition()), level, true);
        return 1;
    }

    private static int biome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = playerSource(context);
        ResourceOrTagArgument.Result<Biome> target = ResourceOrTagArgument.getResourceOrTag(
                context, "biome", Registries.BIOME);
        ServerLevel level = source.getLevel();
        AsyncLocateHandler.locateBiomeAsync(source, target, BlockPos.containing(source.getPosition()), level, true);
        return 1;
    }

    private static int poi(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = playerSource(context);
        ResourceOrTagArgument.Result<PoiType> target = ResourceOrTagArgument.getResourceOrTag(
                context, "poi", Registries.POINT_OF_INTEREST_TYPE);
        ServerLevel level = source.getLevel();
        AsyncLocateHandler.locatePoiAsync(source, target, BlockPos.containing(source.getPosition()), level, true);
        return 1;
    }

    private static CommandSourceStack playerSource(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        source.getPlayerOrException();
        return source;
    }
}
