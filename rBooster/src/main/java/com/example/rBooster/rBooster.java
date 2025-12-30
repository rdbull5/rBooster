package com.example.rBooster;

import org.bukkit.plugin.java.JavaPlugin;

public class rBooster extends JavaPlugin {

    private static rBooster instance;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("rBooster aktif! - always free rProject");

        loadModules();
    }

    @Override
    public void onDisable() {
        getLogger().info("rBooster kapatıldı. - always free rProject");
    }

    private void loadModules() {
        new BoosterDiscordAPI(this);
        new MinecraftBoostAPI(this);
        new ConfigManager(this);
    }

    public static rBooster getInstance() {
        return instance;
    }
}