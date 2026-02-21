package com.tunnelminer.addon.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import com.tunnelminer.addon.TunnelMinerAddon;
import com.tunnelminer.addon.modules.TunnelMinerModule;

public class EtaHud extends HudElement {

    public static final HudElementInfo<EtaHud> INFO =
        new HudElementInfo<>(TunnelMinerAddon.HUD_GROUP, "tunnel-eta",
            "Shows estimated time remaining to finish the tunnel.", EtaHud::new);

    public EtaHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        TunnelMinerModule mod = TunnelMinerModule.INSTANCE;

        String text;

        if (mod == null || !mod.isActive()) {
            text = "ETA: --";
        } else {
            double eta = mod.getEtaSeconds();
            if (eta < 0) {
                text = "ETA: Calculating...";
            } else {
                text = "ETA: " + formatTime(eta);
            }
        }

        renderer.text(text, x, y, Color.WHITE, false);
        setSize(renderer.textWidth(text, false), renderer.textHeight(false));
    }

    private String formatTime(double totalSeconds) {
        long secs  = (long) totalSeconds;
        long hours = secs / 3600;
        long mins  = (secs % 3600) / 60;
        long s     = secs % 60;

        if (hours > 0)   return String.format("%dh %02dm %02ds", hours, mins, s);
        else if (mins > 0) return String.format("%dm %02ds", mins, s);
        else               return String.format("%ds", s);
    }
}