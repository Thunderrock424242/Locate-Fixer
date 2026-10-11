package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocateTeleportArgumentTest {
    private static final Source PLAYER = new Source(2, true);
    private static final Source CONSOLE = new Source(2, false);
    private static final SimpleCommandExceptionType PLAYER_REQUIRED = new SimpleCommandExceptionType(
            new LiteralMessage("A player is required."));
    private static final SimpleCommandExceptionType INVALID_TARGET = new SimpleCommandExceptionType(
            new LiteralMessage("Invalid target."));

    @Test
    void suffixPreservesNativeArgumentAndOrdinaryExecution() throws Exception {
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger travel = new AtomicInteger();
        AtomicReference<String> received = new AtomicReference<>();
        CommandDispatcher<Source> dispatcher = dispatcher(ordinary);
        CommandNode<Source> originalTarget = target(dispatcher, "structure");
        Object originalCommand = originalTarget.getCommand();

        assertTrue(append(dispatcher, "structure", true, travel, received));
        assertSame(originalTarget, target(dispatcher, "structure"));
        assertSame(originalCommand, target(dispatcher, "structure").getCommand());
        assertEquals(17, dispatcher.execute("locate structure minecraft:stronghold", PLAYER));
        assertEquals(1, ordinary.get());
        assertEquals(0, travel.get());
        assertEquals(23, dispatcher.execute("locate structure minecraft:stronghold teleport", PLAYER));
        assertEquals("minecraft:stronghold", received.get());
        assertEquals(1, ordinary.get());
        assertEquals(1, travel.get());
    }

    @Test
    void everySupportedTargetKeepsNamespacedAndTagArguments() throws Exception {
        for (String type : new String[]{"structure", "biome", "poi"}) {
            AtomicInteger travel = new AtomicInteger();
            AtomicReference<String> received = new AtomicReference<>();
            CommandDispatcher<Source> dispatcher = dispatcher(new AtomicInteger());
            assertTrue(append(dispatcher, type, true, travel, received));
            assertEquals(23, dispatcher.execute("locate " + type + " #datapack:rare_targets teleport", PLAYER));
            assertEquals("#datapack:rare_targets", received.get());
            assertEquals(1, travel.get());
        }
    }

    @Test
    void vanillaPermissionRemainsRequiredForBothForms() {
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger travel = new AtomicInteger();
        CommandDispatcher<Source> dispatcher = dispatcher(ordinary);
        append(dispatcher, "biome", true, travel, new AtomicReference<>());
        Source unprivileged = new Source(1, true);
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("locate biome minecraft:plains", unprivileged));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("locate biome minecraft:plains teleport", unprivileged));
        assertEquals(0, ordinary.get());
        assertEquals(0, travel.get());
    }

    @Test
    void consoleCanLocateButCannotRequestPlayerTravel() throws Exception {
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger travel = new AtomicInteger();
        CommandDispatcher<Source> dispatcher = dispatcher(ordinary);
        append(dispatcher, "poi", true, travel, new AtomicReference<>());
        assertEquals(17, dispatcher.execute("locate poi minecraft:home", CONSOLE));
        CommandSyntaxException failure = assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("locate poi minecraft:home teleport", CONSOLE));
        assertEquals(PLAYER_REQUIRED, failure.getType());
        assertEquals(0, travel.get());
    }

    @Test
    void conflictingLocateOwnerReceivesAnUnchangedTree() throws Exception {
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger travel = new AtomicInteger();
        CommandDispatcher<Source> dispatcher = dispatcher(ordinary);
        assertFalse(append(dispatcher, "structure", false, travel, new AtomicReference<>()));
        assertNull(target(dispatcher, "structure").getChild("teleport"));
        assertEquals(17, dispatcher.execute("locate structure minecraft:stronghold", PLAYER));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("locate structure minecraft:stronghold teleport", PLAYER));
        assertEquals(1, ordinary.get());
        assertEquals(0, travel.get());
    }

    @Test
    void existingTeleportOwnerIsNotReplacedAndRepeatedRegistrationIsHarmless() throws Exception {
        CommandDispatcher<Source> dispatcher = dispatcher(new AtomicInteger());
        CommandNode<Source> owned = LiteralArgumentBuilder.<Source>literal("teleport")
                .executes(context -> 41).build();
        target(dispatcher, "structure").addChild(owned);
        assertFalse(append(dispatcher, "structure", true, new AtomicInteger(), new AtomicReference<>()));
        assertSame(owned, target(dispatcher, "structure").getChild("teleport"));
        assertEquals(41, dispatcher.execute("locate structure minecraft:stronghold teleport", PLAYER));
    }

    @Test
    void malformedTargetsAndTrailingArgumentsDoNotSubmitSearches() {
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger travel = new AtomicInteger();
        CommandDispatcher<Source> dispatcher = dispatcher(ordinary);
        append(dispatcher, "biome", true, travel, new AtomicReference<>());
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("locate biome invalid? teleport", PLAYER));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("locate biome minecraft:plains teleport extra", PLAYER));
        assertEquals(0, ordinary.get());
        assertEquals(0, travel.get());
    }

    @Test
    void missingOrReplacedArgumentPathsAreNotFabricated() {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        assertFalse(append(dispatcher, "structure", true, new AtomicInteger(), new AtomicReference<>()));
        assertNull(dispatcher.getRoot().getChild("locate"));
        dispatcher.register(LiteralArgumentBuilder.<Source>literal("locate")
                .then(LiteralArgumentBuilder.<Source>literal("structure")
                        .then(LiteralArgumentBuilder.<Source>literal("structure"))));
        assertFalse(append(dispatcher, "structure", true, new AtomicInteger(), new AtomicReference<>()));
        assertNull(target(dispatcher, "structure").getChild("teleport"));
    }

    private static boolean append(CommandDispatcher<Source> dispatcher, String type, boolean owns,
                                  AtomicInteger travel, AtomicReference<String> received) {
        return LocateTeleportArgument.append(dispatcher, type, owns, source -> source.permission() >= 2,
                context -> {
                    if (!context.getSource().player()) {
                        throw PLAYER_REQUIRED.create();
                    }
                    received.set(context.getArgument(type, String.class));
                    travel.incrementAndGet();
                    return 23;
                });
    }

    private static CommandDispatcher<Source> dispatcher(AtomicInteger ordinary) {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        LiteralArgumentBuilder<Source> root = LiteralArgumentBuilder.<Source>literal("locate")
                .requires(source -> source.permission() >= 2);
        // A strict surrogate for the native resource/tag parser keeps this test independent
        // of Minecraft registry/bootstrap state while exercising Brigadier's actual tree.
        ArgumentType<String> targetParser = reader -> {
            int start = reader.getCursor();
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
                reader.skip();
            }
            String value = reader.getString().substring(start, reader.getCursor());
            if (!value.matches("#?[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
                throw INVALID_TARGET.createWithContext(reader);
            }
            return value;
        };
        for (String type : new String[]{"structure", "biome", "poi"}) {
            root.then(LiteralArgumentBuilder.<Source>literal(type)
                    .then(RequiredArgumentBuilder.<Source, String>argument(type, targetParser)
                            .executes(context -> {
                                ordinary.incrementAndGet();
                                return 17;
                            })));
        }
        dispatcher.register(root);
        return dispatcher;
    }

    private static CommandNode<Source> target(CommandDispatcher<Source> dispatcher, String type) {
        return dispatcher.getRoot().getChild("locate").getChild(type).getChild(type);
    }

    private record Source(int permission, boolean player) {
    }
}
