package org.example;

import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    @Override
    public void onEnable() {
        // Register event listener
        getServer().getPluginManager().registerEvents(new WindChargeListener(this), this);

        // Register commands
        if (getCommand("jerylplugin") != null) {
            getCommand("jerylplugin").setExecutor(new HelpCommand());
        }

        // Also register "help" argument hook if needed
        if (getCommand("help") != null) {
            getCommand("help").setExecutor(new HelpCommand());
        }

        getLogger().info("JerylPlugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("JerylPlugin disabled.");
    }
}