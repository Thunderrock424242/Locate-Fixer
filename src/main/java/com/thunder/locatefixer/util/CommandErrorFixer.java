package com.thunder.locatefixer.util;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thunder.locatefixer.config.LocateFixerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.event.CommandEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public final class CommandErrorFixer {
    private static final int MAX_SUGGESTIONS = 3;

    private CommandErrorFixer() {
    }

    public static void handle(CommandEvent event) {
        if (!isEnabled()) {
            return;
        }

        ParseResults<CommandSourceStack> parseResults = event.getParseResults();
        CommandSyntaxException exception = Commands.getParseException(parseResults);
        if (exception == null) {
            return;
        }

        CommandSourceStack source = parseResults.getContext().getSource();
        String command = parseResults.getReader().getString();
        if (sendImprovedError(source, command)) {
            event.setCanceled(true);
        }
    }

    public static boolean sendUnknownStructure(
            CommandSourceStack source,
            ResourceOrTagKeyArgument.Result<Structure> structure
    ) {
        if (!isEnabled()) {
            return false;
        }

        Registry<Structure> registry = source.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Optional<ResourceKey<Structure>> resourceKey = structure.unwrap().left();
        if (resourceKey.isPresent() && registry.getHolder(resourceKey.get()).isEmpty()) {
            String raw = structure.asPrintable();
            sendUnknownRegistryError(
                    source,
                    "structure",
                    raw,
                    registry.keySet().stream(),
                    suggestion -> "/locate structure " + suggestion
            );
            return true;
        }

        Optional<TagKey<Structure>> tagKey = structure.unwrap().right();
        if (tagKey.isPresent() && registry.getTag(tagKey.get()).isEmpty()) {
            String raw = structure.asPrintable();
            sendUnknownRegistryError(
                    source,
                    "structure tag",
                    raw,
                    registry.getTagNames().map(TagKey::location),
                    suggestion -> "/locate structure #" + suggestion
            );
            return true;
        }

        return false;
    }

    private static boolean sendImprovedError(CommandSourceStack source, String command) {
        List<Token> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            return false;
        }

        String root = tokens.get(0).value();
        return switch (root) {
            case "locate" -> handleLocate(source, command, tokens);
            case "summon" -> handleRegistryArgument(source, command, tokens, 1, "entity",
                    Registries.ENTITY_TYPE, token -> token.value());
            case "give" -> handleRegistryArgument(source, command, tokens, 2, "item",
                    Registries.ITEM, CommandErrorFixer::resourcePart);
            case "effect" -> handleEffect(source, command, tokens);
            default -> false;
        };
    }

    private static boolean handleLocate(CommandSourceStack source, String command, List<Token> tokens) {
        if (tokens.size() == 1) {
            sendMissingSuggestion(source, "locate target type", "biome", "/locate biome ");
            return true;
        }

        Token type = tokens.get(1);
        return switch (type.value()) {
            case "biome" -> handleRegistryArgument(source, command, tokens, 2, "biome",
                    Registries.BIOME, token -> token.value());
            case "structure" -> handleRegistryArgument(source, command, tokens, 2, "structure",
                    Registries.STRUCTURE, token -> token.value());
            case "poi" -> handleRegistryArgument(source, command, tokens, 2, "point of interest",
                    Registries.POINT_OF_INTEREST_TYPE, token -> token.value());
            default -> {
                String suggestion = closestLiteral(type.value(), List.of("structure", "biome", "poi", "feature", "dimension", "nearest"));
                yield sendLiteralSuggestion(source, "locate target type", type.value(), suggestion, replaceToken(command, type, suggestion));
            }
        };
    }

    private static boolean handleEffect(CommandSourceStack source, String command, List<Token> tokens) {
        if (tokens.size() == 1) {
            sendMissingSuggestion(source, "effect action", "give", "/effect give ");
            return true;
        }

        Token action = tokens.get(1);
        if (!action.value().equals("give") && !action.value().equals("clear")) {
            String suggestion = closestLiteral(action.value(), List.of("give", "clear"));
            return sendLiteralSuggestion(source, "effect action", action.value(), suggestion, replaceToken(command, action, suggestion));
        }

        if (tokens.size() <= 3) {
            return false;
        }

        return handleRegistryArgument(source, command, tokens, 3, "effect",
                Registries.MOB_EFFECT, token -> token.value());
    }

    private static <T> boolean handleRegistryArgument(
            CommandSourceStack source,
            String command,
            List<Token> tokens,
            int argumentIndex,
            String label,
            ResourceKey<? extends Registry<T>> registryKey,
            Function<Token, String> resourceExtractor
    ) {
        if (tokens.size() <= argumentIndex) {
            return false;
        }

        Token token = tokens.get(argumentIndex);
        String raw = resourceExtractor.apply(token);
        boolean tag = raw.startsWith("#");
        String id = tag ? raw.substring(1) : raw;
        if (id.isBlank()) {
            return false;
        }

        Registry<T> registry = source.registryAccess().registryOrThrow(registryKey);
        ResourceLocation resourceLocation = ResourceLocation.tryParse(id);
        if (resourceLocation != null) {
            if (tag && registry.getTag(TagKey.create(registryKey, resourceLocation)).isPresent()) {
                return false;
            }
            if (!tag && registry.containsKey(resourceLocation)) {
                return false;
            }
        }

        int resourceEnd = token.start() + raw.length();
        Token replaceRange = new Token(token.value(), token.start(), resourceEnd);
        Stream<ResourceLocation> candidates = tag
                ? registry.getTagNames().map(TagKey::location)
                : registry.keySet().stream();
        sendUnknownRegistryError(
                source,
                tag ? label + " tag" : label,
                raw,
                candidates,
                suggestion -> "/" + replaceToken(command, replaceRange, (tag ? "#" : "") + suggestion)
        );
        return true;
    }

    private static void sendUnknownRegistryError(
            CommandSourceStack source,
            String label,
            String raw,
            Stream<ResourceLocation> candidates,
            Function<String, String> commandBuilder
    ) {
        List<String> suggestions = findClosest(raw, candidates);
        source.sendFailure(Component.literal("Unknown " + label + " \"" + raw + "\"."));

        if (suggestions.isEmpty()) {
            source.sendFailure(Component.literal("No close registry match found. Use Tab completion to browse valid ids.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        MutableComponent message = Component.literal("Did you mean ");
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) {
                message.append(i == suggestions.size() - 1 ? Component.literal(" or ") : Component.literal(", "));
            }

            String suggestion = suggestions.get(i);
            message.append(clickableSuggestion(suggestion, commandBuilder.apply(suggestion)));
        }
        message.append(Component.literal("?"));
        source.sendFailure(message);
    }

    private static boolean sendLiteralSuggestion(CommandSourceStack source, String label, String raw, String suggestion, String command) {
        if (suggestion == null || suggestion.isBlank() || command == null || command.isBlank()) {
            return false;
        }

        source.sendFailure(Component.literal("Unknown " + label + " \"" + raw + "\"."));
        source.sendFailure(Component.literal("Did you mean ").append(clickableSuggestion(suggestion, command)).append(Component.literal("?")));
        return true;
    }

    private static void sendMissingSuggestion(CommandSourceStack source, String label, String suggestion, String command) {
        source.sendFailure(Component.literal("Missing " + label + "."));
        source.sendFailure(Component.literal("Try ").append(clickableSuggestion(suggestion, command)).append(Component.literal(".")));
    }

    private static MutableComponent clickableSuggestion(String label, String command) {
        return Component.literal("\"" + label + "\"")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Suggest " + command))));
    }

    private static List<String> findClosest(String raw, Stream<ResourceLocation> candidates) {
        String normalizedRaw = normalize(raw.startsWith("#") ? raw.substring(1) : raw);
        return candidates
                .map(ResourceLocation::toString)
                .distinct()
                .map(candidate -> new Match(candidate, score(normalizedRaw, candidate)))
                .filter(match -> match.score() <= threshold(normalizedRaw))
                .sorted(Comparator.comparingInt(Match::score).thenComparing(Match::value))
                .limit(MAX_SUGGESTIONS)
                .map(Match::value)
                .toList();
    }

    private static String closestLiteral(String raw, List<String> literals) {
        String normalizedRaw = normalize(raw);
        return literals.stream()
                .map(literal -> new Match(literal, levenshtein(normalizedRaw, normalize(literal))))
                .min(Comparator.comparingInt(Match::score).thenComparing(Match::value))
                .map(Match::value)
                .orElse(null);
    }

    private static int score(String normalizedRaw, String candidate) {
        String normalizedCandidate = normalize(candidate);
        String pathOnly = normalize(pathOnly(candidate));
        int distance = Math.min(levenshtein(normalizedRaw, normalizedCandidate), levenshtein(normalizedRaw, pathOnly));
        if (normalizedRaw.length() >= 4 && pathOnly.contains(normalizedRaw)) {
            distance = 0;
        } else if (normalizedRaw.contains(pathOnly)) {
            distance = Math.min(distance, Math.abs(pathOnly.length() - normalizedRaw.length()));
        }
        return distance;
    }

    private static int threshold(String normalizedRaw) {
        int length = normalizedRaw.length();
        if (length <= 4) {
            return 1;
        }
        if (length <= 8) {
            return 2;
        }
        return Math.max(3, length / 3);
    }

    private static int levenshtein(String left, String right) {
        int[] costs = new int[right.length() + 1];
        for (int j = 0; j < costs.length; j++) {
            costs[j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            costs[0] = i;
            int previous = i - 1;
            for (int j = 1; j <= right.length(); j++) {
                int old = costs[j];
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                costs[j] = Math.min(Math.min(costs[j] + 1, costs[j - 1] + 1), previous + cost);
                previous = old;
            }
        }

        return costs[right.length()];
    }

    private static String pathOnly(String value) {
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private static String normalize(String value) {
        return pathOnly(value)
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace("/", "");
    }

    private static String resourcePart(Token token) {
        String value = token.value();
        int componentStart = value.indexOf('[');
        int legacyNbtStart = value.indexOf('{');
        int end = value.length();
        if (componentStart >= 0) {
            end = Math.min(end, componentStart);
        }
        if (legacyNbtStart >= 0) {
            end = Math.min(end, legacyNbtStart);
        }
        return value.substring(0, end);
    }

    private static String replaceToken(String command, Token token, String replacement) {
        return command.substring(0, token.start()) + replacement + command.substring(token.end());
    }

    private static List<Token> tokenize(String command) {
        List<Token> tokens = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < command.length(); i++) {
            if (Character.isWhitespace(command.charAt(i))) {
                if (start >= 0) {
                    tokens.add(new Token(command.substring(start, i), start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }

        if (start >= 0) {
            tokens.add(new Token(command.substring(start), start, command.length()));
        }
        return tokens;
    }

    private static boolean isEnabled() {
        try {
            return LocateFixerConfig.SERVER.enableCommandErrorFixer.get();
        } catch (IllegalStateException ignored) {
            return true;
        }
    }

    private record Token(String value, int start, int end) {
    }

    private record Match(String value, int score) {
    }
}
