package com.thunder.locatefixer.probe;

import com.mojang.logging.LogUtils;
import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.job.LocateJobStatus;
import com.thunder.locatefixer.job.LocateJobSubmissions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable local test mod; never part of Locate Fixer's production sources or JAR. */
@Mod("locatefixer_ixeris_probe")
public final class IxerisProbe {
    private static final Logger LOG = LogUtils.getLogger();
    private int state;
    private final long started = System.nanoTime();
    private volatile boolean finished;
    private volatile String failure;
    private final AtomicBoolean pollQueued = new AtomicBoolean();
    private int events;

    public IxerisProbe() {
        LOG.info("IXERIS_PROBE fixture registered");
        MinecraftForge.EVENT_BUS.addListener(this::render);
    }

    private void render(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) tick();
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (++events % 100 == 0) LOG.info("IXERIS_PROBE state={}; screen={}; overlay={}", state,
                mc.screen == null ? "none" : mc.screen.getClass().getName(),
                mc.getOverlay() == null ? "none" : mc.getOverlay().getClass().getName());
        if (state == 0 && mc.screen instanceof AccessibilityOnboardingScreen && mc.getOverlay() == null) {
            mc.screen.onClose();
            return;
        }
        if (state == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            state = 1;
            LOG.info("IXERIS_PROBE opening singleplayer selection; Ixeris loaded={}", ModList.get().isLoaded("ixeris"));
            if (!ModList.get().isLoaded("ixeris")) throw new IllegalStateException("Ixeris was not loaded by the fixture");
            // Exercise the same world-selection/datapack loading boundary as the issue's mouse event.
            mc.setScreen(new SelectWorldScreen(mc.screen));
            LOG.info("IXERIS_PROBE world selection passed; creating new disposable world");
            mc.createWorldOpenFlows().createFreshLevel("issue89-" + System.currentTimeMillis(),
                    new LevelSettings("Ixeris issue 89 probe", GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(4401357362987183368L, true, false), WorldPresets::createNormalWorldDimensions);
        } else if (state == 1 && mc.player != null && mc.getSingleplayerServer() != null) {
            state = 2;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayers().get(0);
                var source = player.createCommandSourceStack();
                LOG.info("IXERIS_PROBE integrated world loaded; player={}; thread={}", player.getScoreboardName(), Thread.currentThread().getName());
                server.getCommands().performPrefixedCommand(source, "locate biome minecraft:plains");
            });
        } else if (state == 2 && mc.getSingleplayerServer() != null) {
            var server = mc.getSingleplayerServer();
            if (pollQueued.compareAndSet(false, true)) server.execute(() -> {
                try {
                    if (finished || server.getPlayerList().getPlayers().isEmpty()) return;
                    var source = server.getPlayerList().getPlayers().get(0).createCommandSourceStack();
                    var latest = LocateRuntime.jobs().latestFor(LocateJobSubmissions.sourceKey(source));
                    if (latest.isPresent() && latest.get().status().isTerminal()) {
                        LOG.info("IXERIS_PROBE locate completed; status={}; result={}", latest.get().status(), latest.get().result());
                        if (latest.get().status() != LocateJobStatus.FOUND || latest.get().result().isEmpty()) {
                            failure = "Locate did not produce a found result: " + latest.get().status();
                        }
                        finished = true;
                    }
                } finally {
                    pollQueued.set(false);
                }
            });
            if (finished) {
                if (failure != null) throw new IllegalStateException(failure);
                state = 3;
                LOG.info("IXERIS_PROBE PASS: world selection, integrated world and locate did not reproduce issue 89");
                mc.stop();
            }
        }
        if (state != 3 && System.nanoTime() - started > 180_000_000_000L) {
            LOG.error("IXERIS_PROBE TIMEOUT: state={}", state);
            throw new IllegalStateException("Ixeris reproduction fixture timed out in state " + state);
        }
    }
}
