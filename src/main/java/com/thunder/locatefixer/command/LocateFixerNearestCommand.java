package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class LocateFixerNearestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("nearest")
                        .then(Commands.literal("structure")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> AsyncLocateHandler.locateNearestStructuresAsync(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count")
                                        ))))
                        .then(Commands.literal("biome")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> AsyncLocateHandler.locateNearestBiomesAsync(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count")
                                        ))))));
    }
}
