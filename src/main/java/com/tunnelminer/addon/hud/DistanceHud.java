package com.tunnelminer.addon.hud;

import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import com.tunnelminer.addon.TunnelMinerAddon;
import com.tunnelminer.addon.modules.TunnelMinerModule;

public class DistanceHud extends HudElement {

    public static final HudElementInfo<DistanceHud> INFO =
        new HudElementInfo<>(TunnelMinerAddon.HUD_GROUP, "tunnel-distance",
            "Shows blocks remaining to the tunnel target.", DistanceHud::new);

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> showTarget = sg.add(new BoolSetting.Builder()
        .name("show-target").description("Also show the target coordinates.")
        .defaultValue(true).build());

    private final Setting<Boolean> showProgress = sg.add(new BoolSetting.Builder()
        .name("show-progress").description("Show X/Total progress text.")
        .defaultValue(true).build());

    public DistanceHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        TunnelMinerModule mod = TunnelMinerModule.INSTANCE;

        if (mod == null || !mod.isActive()) {
            String inactive = "TunnelMiner: Inactive";
            renderer.text(inactive, x, y, Color.WHITE, false);
            setSize(renderer.textWidth(inactive, false), renderer.textHeight(false));
            return;
        }

        int left  = mod.getBlocksLeft();
        int total = mod.getTotalBlocks();
        int dX    = mod.getDestX();
        int dZ    = mod.getDestZ();

        double lineH = renderer.textHeight(false);
        double maxW  = 0;
        double curY  = y;

        // Line 1: blocks left
        String line1 = "Blocks left: " + left;
        renderer.text(line1, x, curY, Color.WHITE, false);
        maxW = Math.max(maxW, renderer.textWidth(line1, false));
        curY += lineH + 2;

        // Line 2: progress
        if (showProgress.get() && total > 0) {
            int done = total - left;
            String line2 = "Progress: " + done + " / " + total;
            renderer.text(line2, x, curY, Color.WHITE, false);
            maxW = Math.max(maxW, renderer.textWidth(line2, false));
            curY += lineH + 2;
        }

        // Line 3: target coords
        if (showTarget.get()) {
            String line3 = "Target: X" + dX + "  Z" + dZ;
            renderer.text(line3, x, curY, Color.WHITE, false);
            maxW = Math.max(maxW, renderer.textWidth(line3, false));
            curY += lineH + 2;
        }

        setSize(maxW, curY - y);
    }
}