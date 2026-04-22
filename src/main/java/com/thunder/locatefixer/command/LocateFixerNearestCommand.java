package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class LocateFixerNearestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("nearest")
                        .requires(source -> {
                            try {
                                // Gate behind both permission level AND config
                                return source.hasPermission(2) &&
                                        LocateFixerConfig.SERVER.enableNearestCommand.get();
                            } catch (IllegalStateException e) {
                                return false; // Safely return false if config isn't ready
                            }
                        })
                        .then(Commands.literal("structure")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 25))
                                        .executes(ctx -> AsyncLocateHandler.locateNearestStructuresAsync(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count")
                                        ))))
                        .then(Commands.literal("biome")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 25))
                                        .executes(ctx -> AsyncLocateHandler.locateNearestBiomesAsync(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count")
                                        ))))));
    }
}