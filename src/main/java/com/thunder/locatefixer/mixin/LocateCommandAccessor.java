package com.thunder.locatefixer.mixin;

import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.server.commands.LocateCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocateCommand.class)
public interface LocateCommandAccessor {

    @Accessor("ERROR_STRUCTURE_NOT_FOUND")
    static DynamicCommandExceptionType getStructureNotFound() {
        throw new AssertionError();
    }

    @Accessor("ERROR_STRUCTURE_INVALID")
    static DynamicCommandExceptionType getStructureInvalid() {
        throw new AssertionError();
    }

    @Accessor("ERROR_BIOME_NOT_FOUND")
    static DynamicCommandExceptionType getBiomeNotFound() {
        throw new AssertionError();
    }
}