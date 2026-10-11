package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.job.LocateJobSubmissions;
import com.thunder.locatefixer.schematic.SchematicLocatorRegistry;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class LocateFixerSchematicCommand {
    private static final int MAX_RADIUS = 256_000;

    private LocateFixerSchematicCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("schematic")
                        .then(Commands.literal("record")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests((ctx, builder) -> suggestNames(builder))
                                        .executes(ctx -> record(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")
                                        ))))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests((ctx, builder) -> suggestNames(builder))
                                .executes(ctx -> locate(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")
                                )))));
    }

    private static CompletableFuture<Suggestions> suggestNames(SuggestionsBuilder builder) {
        for (String id : SchematicLocatorRegistry.getAllRegisteredIds()) {
            builder.suggest(id);
        }
        return builder.buildFuture();
    }

    private static int locate(CommandSourceStack source, String id) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        if (!SchematicLocatorRegistry.isRegistered(id)) {
            source.sendFailure(Component.literal("❌ No schematic named '" + id + "' is registered."));
            return 0;
        }

        boolean accepted = LocateJobSubmissions.submit(source, LocatorTargetType.CUSTOM,
                "schematic:" + id, origin, level, MAX_RADIUS, job -> {
            job.searching(25, "Searching the schematic registry");
            Optional<BlockPos> position = AsyncLocateHandler.callOnServerThread(level,
                    () -> SchematicLocatorRegistry.locate(id, level, origin, MAX_RADIUS));
            job.cancellationToken().throwIfCancelled();
            if (position.isEmpty()) {
                String message = "Schematic '" + id + "' has no position recorded in this dimension. "
                        + "After pasting it, stand at its anchor and run /locate schematic record " + id + ".";
                level.getServer().execute(() -> source.sendFailure(Component.literal("❌ " + message)));
                job.notFound(message);
                return;
            }

            BlockPos found = position.get();
            level.getServer().execute(() -> LocateResultHelper.sendResult(
                    source, "commands.locate.structure.success", id, origin, found, false));
            job.found(new LocatorResult(LocatorTargetType.CUSTOM, "schematic:" + id,
                    level.dimension().location().toString(), found,
                    "locatefixer:schematic-registry", "recorded-schematic-position",
                    Instant.now(), true, true, Map.of()));
        });
        if (accepted) {
            source.sendSuccess(() -> Component.literal("🔍 Schematic search queued."), false);
        }
        return accepted ? 1 : 0;
    }

    private static int record(CommandSourceStack source, String id) {
        ServerLevel level = source.getLevel();
        BlockPos position = BlockPos.containing(source.getPosition());
        SchematicLocatorRegistry.registerSchematicPosition(id, level, position);
        source.sendSuccess(() -> Component.literal("✅ Recorded schematic '" + id + "' in "
                + level.dimension().location() + " at " + position.getX() + " " + position.getY() + " "
                + position.getZ() + "."), true);
        return 1;
    }
}
