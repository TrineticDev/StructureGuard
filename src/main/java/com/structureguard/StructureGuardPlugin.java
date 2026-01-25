package com.structureguard;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * StructureGuard - Automatically create WorldGuard regions around structures.
 * Uses on-demand chunk load detection for efficient structure protection.
 */
public class StructureGuardPlugin extends JavaPlugin {
    
    private StructureDatabase database;
    private StructureFinder structureFinder;
    private RegionManager regionManager;
    private ConfigManager configManager;
    private ChunkLoadListener chunkLoadListener;
    
    @Override
    public void onEnable() {
        // Create plugin folder
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        // Initialize components
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        database = new StructureDatabase(this);
        structureFinder = new StructureFinder(this);
        
        // Initialize RegionManager - may fail if WorldGuard is missing/broken
        try {
            regionManager = new RegionManager(this);
        } catch (NoClassDefFoundError | Exception e) {
            getLogger().warning("Could not initialize WorldGuard integration: " + e.getMessage());
            getLogger().warning("Region protection features will be disabled.");
            regionManager = null;
        }
        
        // Register on-demand protection listener
        if (regionManager != null && regionManager.isWorldGuardAvailable()) {
            chunkLoadListener = new ChunkLoadListener(this);
            getServer().getPluginManager().registerEvents(chunkLoadListener, this);
            getLogger().info("On-demand structure protection enabled!");
        } else {
            getLogger().warning("WorldGuard not found! On-demand protection disabled.");
        }
        
        // Register commands
        CommandHandler commandHandler = new CommandHandler(this);
        getCommand("structureguard").setExecutor(commandHandler);
        getCommand("structureguard").setTabCompleter(commandHandler);
        
        getLogger().info("StructureGuard enabled!");
        
        if (regionManager == null || !regionManager.isWorldGuardAvailable()) {
            getLogger().warning("WorldGuard not found! Region protection features disabled.");
        }
    }
    
    @Override
    public void onDisable() {
        // Shutdown chunk processor first (flushes pending data)
        if (chunkLoadListener != null) {
            chunkLoadListener.shutdown();
        }
        
        if (database != null) {
            database.close();
        }
        getLogger().info("StructureGuard disabled!");
    }
    
    public void reload() {
        reloadConfig();
        configManager = new ConfigManager(this);
        // Sync any manual region edits from config
        if (regionManager != null) {
            regionManager.syncFromConfig();
        }
    }
    
    public StructureDatabase getDatabase() {
        return database;
    }
    
    public StructureFinder getStructureFinder() {
        return structureFinder;
    }
    
    public RegionManager getRegionManager() {
        return regionManager;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public ChunkLoadListener getChunkLoadListener() {
        return chunkLoadListener;
    }
}
