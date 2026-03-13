package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;

public class LocateFixerBlockCommand {

    private static final int ADMIN_PERMISSION_LEVEL = 2;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("block")
                        .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                        .then(Commands.argument("block", ResourceLocationArgument.id())
                                .suggests((context, builder) -> {
                                    return SharedSuggestionProvider.suggestResource(
                                            context.getSource().registryAccess()
                                                    .lookupOrThrow(Registries.BLOCK)
                                                    .listElementIds()
                                                    .map(ResourceKey::location),
                                            builder
                                    );
                                })
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String blockId = ResourceLocationArgument.getId(context, "block").toString();
                                    ServerLevel level = source.getLevel();
                                    BlockPos origin = BlockPos.containing(source.getPosition());

                                    source.sendSuccess(() -> Component.literal("🔍 Scanning chunks for block '" + blockId + "'..."), false);
                                    AsyncLocateHandler.locateBlockAsync(source, blockId, origin, level);
                                    return 1;
                                }))));
    }
}
