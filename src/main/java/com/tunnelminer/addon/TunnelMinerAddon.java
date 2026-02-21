package com.tunnelminer.addon;

import com.tunnelminer.addon.hud.DistanceHud;
import com.tunnelminer.addon.hud.EtaHud;
import com.tunnelminer.addon.modules.TunnelMinerModule;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class TunnelMinerAddon extends MeteorAddon {

    public static final Category CATEGORY  = new Category("TunnelMiner");
    public static final HudGroup HUD_GROUP = new HudGroup("TunnelMiner");

    @Override
    public void onInitialize() {
        // Modules are added here (category already registered by this point)
        Modules.get().add(new TunnelMinerModule());

        // HUD elements
        Hud.get().register(DistanceHud.INFO);
        Hud.get().register(EtaHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        // Category MUST be registered here, not in onInitialize
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.tunnelminer.addon";
    }
}