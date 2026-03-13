package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

public class LocateFixerNearestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locatex")
                .then(Commands.literal("structure")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> {
                                    Registry<Structure> structures = ctx.getSource().getLevel().registryAccess()
                                            .registryOrThrow(Registries.STRUCTURE);
                                    return SharedSuggestionProvider.suggestResource(structures.keySet(), builder);
                                })
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> {
                                            ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                            int count = IntegerArgumentType.getInteger(ctx, "count");
                                            return AsyncLocateHandler.locateNearestStructuresAsync(ctx.getSource(), id, count);
                                        }))))
                .then(Commands.literal("biome")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> {
                                    Registry<Biome> biomes = ctx.getSource().getLevel().registryAccess()
                                            .registryOrThrow(Registries.BIOME);
                                    return SharedSuggestionProvider.suggestResource(biomes.keySet(), builder);
                                })
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> {
                                            ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                            int count = IntegerArgumentType.getInteger(ctx, "count");
                                            return AsyncLocateHandler.locateNearestBiomesAsync(ctx.getSource(), id, count);
                                        })))));
    }
}
