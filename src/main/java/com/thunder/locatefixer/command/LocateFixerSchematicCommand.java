package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.thunder.locatefixer.schematic.SchematicLocatorRegistry;
import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public class LocateFixerSchematicCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("schematic")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    for (String id : SchematicLocatorRegistry.getAllRegisteredIds()) {
                                        builder.suggest(id);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String id = StringArgumentType.getString(ctx, "name");
                                    ServerLevel level = source.getLevel();
                                    BlockPos origin = BlockPos.containing(source.getPosition());

                                    return SchematicLocatorRegistry.locate(id, level, origin, 256000)
                                            .map(pos -> {
                                                // ✅ Use string-based result helper for schematic names
                                                LocateResultHelper.sendResult(source, "commands.locate.structure.success", id, origin, pos, false);
                                                LocateResultHelper.startTeleportCountdown(source, level, pos, false);
                                                return 1;
                                            })
                                            .orElseGet(() -> {
                                                source.sendFailure(Component.literal("❌ Schematic '" + id + "' not found or has no registered location."));
                                                return 0;
                                            });
                                }))));
    }
}