package org.example;

import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    @Override
    public void onEnable() {
        // Register event listeners
        getServer().getPluginManager().registerEvents(new WindChargeListener(this), this);
        getServer().getPluginManager().registerEvents(new CocoPopsListener(this), this);
        getServer().getPluginManager().registerEvents(new AirstrikeListener(this), this);
        getServer().getPluginManager().registerEvents(new SniperRifleListener(this), this);
        getServer().getPluginManager().registerEvents(new MeteoriteListener(this), this);
        // Register commands
        if (getCommand("jerylplugin") != null) {
            getCommand("jerylplugin").setExecutor(new HelpCommand());
        }

        // Also register "help" argument hook if needed
        if (getCommand("help") != null) {
            getCommand("help").setExecutor(new HelpCommand());
        }

        // Register Smite Command
        if (getCommand("smite") != null) {
            getCommand("smite").setExecutor(new SmiteCommand(this));
        }
        // register cocopops commmand
        if (getCommand("cocopops") != null) {
            getCommand("cocopops").setExecutor(new CocoPopsCommand());
        }

        // Register airstrike command
        if (getCommand("airstrike") != null) {
            getCommand("airstrike").setExecutor(new AirstrikeCommand());
        }
        // Register huntingrifle command
        if (getCommand("huntingrifle") != null) {
            getCommand("huntingrifle").setExecutor(new SniperCommand());
        }
        // Register meteorite command
        if (getCommand("meteorite") != null) {
            getCommand("meteorite").setExecutor(new MeteoriteCommand(this));
        }

        getLogger().info("JerylPlugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("JerylPlugin disabled.");
    }
}