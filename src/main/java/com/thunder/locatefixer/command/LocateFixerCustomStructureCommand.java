package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.thunder.locatefixer.api.StructureLocatorRegistry;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * Adds a fallback branch under /locate structure for custom structures that are
 * registered in StructureLocatorRegistry but are not present in vanilla's
 * structure registry.
 */
public final class LocateFixerCustomStructureCommand {

    private LocateFixerCustomStructureCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("structure")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("structure", StringArgumentType.string())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(StructureLocatorRegistry.getRegisteredStructureIds(), builder))
                                .executes(ctx -> locate(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "structure")
                                )))));
    }

    private static int locate(CommandSourceStack source, String rawId) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (!StructureLocatorRegistry.isRegistered(id)) {
            source.sendFailure(Component.literal("❌ Unknown structure id: " + rawId));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        AsyncLocateHandler.locateCustomStructureAsync(source, id, origin, level);
        return 1;
    }
}
