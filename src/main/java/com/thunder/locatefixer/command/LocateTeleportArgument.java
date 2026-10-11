package com.thunder.locatefixer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;

import java.util.function.Predicate;

/** Adds explicit travel without rebuilding or replacing the native locate arguments. */
public final class LocateTeleportArgument {
    private LocateTeleportArgument() {
    }

    public static <S> boolean append(CommandDispatcher<S> dispatcher,
                                     String targetType,
                                     boolean ownsLocate,
                                     Predicate<S> allowed,
                                     Command<S> command) {
        if (!ownsLocate) {
            return false;
        }
        CommandNode<S> locate = dispatcher.getRoot().getChild("locate");
        CommandNode<S> type = locate == null ? null : locate.getChild(targetType);
        CommandNode<S> target = type == null ? null : type.getChild(targetType);
        if (!(target instanceof ArgumentCommandNode<?, ?>) || target.getChild("teleport") != null) {
            return false;
        }
        target.addChild(LiteralArgumentBuilder.<S>literal("teleport")
                .requires(allowed)
                .executes(command)
                .build());
        return true;
    }
}
