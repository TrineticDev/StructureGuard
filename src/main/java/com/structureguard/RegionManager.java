package com.structureguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.FlagContext;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.*;

/**
 * Manages WorldGuard regions for protected structures.
 * Each structure gets exactly one region.
 */
public class RegionManager {
    
    private final StructureGuardPlugin plugin;
    private boolean worldGuardAvailable = false;
    
    public RegionManager(StructureGuardPlugin plugin) {
        this.plugin = plugin;
        checkWorldGuard();
    }
    
    private void checkWorldGuard() {
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            worldGuardAvailable = true;
            plugin.getLogger().info("WorldGuard integration enabled");
        } catch (ClassNotFoundException e) {
            worldGuardAvailable = false;
        }
    }
    
    public boolean isWorldGuardAvailable() {
        return worldGuardAvailable;
    }
    
    /**
     * Create a WorldGuard region for a structure.
     * 
     * @param info Structure information
     * @param radius Radius in blocks from center
     * @param yMin Minimum Y level
     * @param yMax Maximum Y level
     * @return The region ID if created, null if failed
     */
    public String createRegion(StructureDatabase.StructureInfo info, int radius, int yMin, int yMax) {
        if (!worldGuardAvailable) return null;
        
        try {
            World bukkitWorld = Bukkit.getWorld(info.world);
            if (bukkitWorld == null) {
                plugin.getLogger().warning("World not found: " + info.world);
                return null;
            }
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = container.get(weWorld);
            
            if (regionManager == null) {
                plugin.getLogger().warning("No region manager for world: " + info.world);
                return null;
            }
            
            String regionId = info.generateRegionId();
            
            // Check if region already exists
            if (regionManager.hasRegion(regionId)) {
                plugin.getConfigManager().debug("Region already exists: " + regionId);
                return regionId;
            }
            
            // Create region
            BlockVector3 min = BlockVector3.at(info.x - radius, yMin, info.z - radius);
            BlockVector3 max = BlockVector3.at(info.x + radius, yMax, info.z + radius);
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionId, min, max);
            
            // Apply default flags from config
            applyDefaultFlags(region);
            
            regionManager.addRegion(region);
            
            // Update database
            plugin.getDatabase().setRegionId(info.world, info.type, info.x, info.z, regionId);
            
            plugin.getConfigManager().debug("Created region: " + regionId);
            return regionId;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create region: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Create a WorldGuard region for a structure with specific flags.
     * Used by the on-demand protection system.
     */
    public String createRegionWithFlags(StructureDatabase.StructureInfo info, int radius, 
                                        int yMin, int yMax, Map<String, String> flags) {
        if (!worldGuardAvailable) return null;
        
        try {
            World bukkitWorld = Bukkit.getWorld(info.world);
            if (bukkitWorld == null) {
                plugin.getLogger().warning("World not found: " + info.world);
                return null;
            }
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = container.get(weWorld);
            
            if (regionManager == null) {
                plugin.getLogger().warning("No region manager for world: " + info.world);
                return null;
            }
            
            String regionId = info.generateRegionId();
            
            // Check if region already exists
            if (regionManager.hasRegion(regionId)) {
                plugin.getConfigManager().debug("Region already exists: " + regionId);
                return regionId;
            }
            
            // Create region
            BlockVector3 min = BlockVector3.at(info.x - radius, yMin, info.z - radius);
            BlockVector3 max = BlockVector3.at(info.x + radius, yMax, info.z + radius);
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionId, min, max);
            
            // Apply provided flags
            applyFlags(region, flags);
            
            regionManager.addRegion(region);
            
            // Update database
            plugin.getDatabase().setRegionId(info.world, info.type, info.x, info.z, regionId);
            
            plugin.getConfigManager().debug("Created region with custom flags: " + regionId);
            return regionId;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create region: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Apply flags from a map to a region.
     */
    @SuppressWarnings("unchecked")
    private void applyFlags(ProtectedRegion region, Map<String, String> flags) {
        for (Map.Entry<String, String> entry : flags.entrySet()) {
            String flagName = entry.getKey();
            String flagValue = entry.getValue();
            
            try {
                FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
                Flag<?> flag = flagRegistry.get(flagName);
                
                if (flag == null) {
                    flag = Flags.fuzzyMatchFlag(flagRegistry, flagName);
                }
                
                if (flag == null) {
                    plugin.getConfigManager().debug("Unknown flag: " + flagName);
                    continue;
                }
                
                if (flag instanceof StateFlag) {
                    // Handle state flags (allow/deny)
                    StateFlag stateFlag = (StateFlag) flag;
                    if ("allow".equalsIgnoreCase(flagValue)) {
                        region.setFlag(stateFlag, StateFlag.State.ALLOW);
                    } else if ("deny".equalsIgnoreCase(flagValue)) {
                        region.setFlag(stateFlag, StateFlag.State.DENY);
                    }
                } else {
                    // Handle other flag types (StringFlag, etc.)
                    try {
                        FlagContext context = FlagContext.create()
                            .setSender(com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapCommandSender(Bukkit.getConsoleSender()))
                            .setInput(flagValue)
                            .build();
                        Object parsedValue = flag.parseInput(context);
                        region.setFlag((Flag<Object>) flag, parsedValue);
                        plugin.getConfigManager().debug("Set flag " + flagName + " = " + flagValue);
                    } catch (Exception e) {
                        plugin.getConfigManager().debug("Failed to parse flag " + flagName + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                plugin.getConfigManager().debug("Could not set flag " + flagName + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Remove a WorldGuard region.
     */
    public boolean removeRegion(String worldName, String regionId) {
        if (!worldGuardAvailable) return false;
        
        try {
            World bukkitWorld = Bukkit.getWorld(worldName);
            if (bukkitWorld == null) return false;
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = 
                WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
            
            if (regionManager == null || !regionManager.hasRegion(regionId)) {
                return false;
            }
            
            regionManager.removeRegion(regionId);
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove region: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Remove all regions matching a structure pattern.
     */
    public int clearRegions(String pattern) {
        if (!worldGuardAvailable) return 0;
        
        int removed = 0;
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId != null && removeRegion(info.world, info.regionId)) {
                removed++;
            }
        }
        
        // Update database
        plugin.getDatabase().clearRegions(pattern);
        
        return removed;
    }
    
    /**
     * Remove ALL StructureGuard regions (sg_*) from WorldGuard across all worlds.
     * Used by /sg clearregions *
     */
    public int clearAllStructureGuardRegions() {
        if (!worldGuardAvailable) return 0;
        
        int removed = 0;
        
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            
            for (World bukkitWorld : Bukkit.getWorlds()) {
                com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
                com.sk89q.worldguard.protection.managers.RegionManager regionManager = container.get(weWorld);
                
                if (regionManager == null) continue;
                
                // Find all regions that start with "sg_"
                List<String> toRemove = new ArrayList<>();
                for (String regionId : regionManager.getRegions().keySet()) {
                    if (regionId.startsWith("sg_")) {
                        toRemove.add(regionId);
                    }
                }
                
                // Remove them
                for (String regionId : toRemove) {
                    regionManager.removeRegion(regionId);
                    removed++;
                    plugin.getConfigManager().debug("Removed region: " + regionId + " from " + bukkitWorld.getName());
                }
            }
            
            plugin.getLogger().info("Cleared " + removed + " StructureGuard regions from all worlds");
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clear all regions: " + e.getMessage());
        }
        
        return removed;
    }
    
    /**
     * Remove ALL StructureGuard regions (sg_*) from a specific world.
     */
    public int clearAllStructureGuardRegionsInWorld(String worldName) {
        if (!worldGuardAvailable) return 0;
        
        int removed = 0;
        
        try {
            World bukkitWorld = Bukkit.getWorld(worldName);
            if (bukkitWorld == null) {
                plugin.getLogger().warning("World not found: " + worldName);
                return 0;
            }
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = 
                WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
            
            if (regionManager == null) return 0;
            
            List<String> toRemove = new ArrayList<>();
            for (String regionId : regionManager.getRegions().keySet()) {
                if (regionId.startsWith("sg_")) {
                    toRemove.add(regionId);
                }
            }
            
            for (String regionId : toRemove) {
                regionManager.removeRegion(regionId);
                removed++;
            }
            
            plugin.getLogger().info("Cleared " + removed + " StructureGuard regions from " + worldName);
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clear regions in world: " + e.getMessage());
        }
        
        return removed;
    }
    
    /**
     * Remove regions matching a pattern in a specific world.
     */
    public int clearRegionsInWorld(String pattern, String worldName) {
        if (!worldGuardAvailable) return 0;
        
        int removed = 0;
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId != null && info.world.equals(worldName)) {
                if (removeRegion(info.world, info.regionId)) {
                    removed++;
                }
            }
        }
        
        // Update database for this pattern (only clears region IDs, keeps structure entries)
        plugin.getDatabase().clearRegions(pattern);
        
        return removed;
    }
    
    /**
     * Set a flag on all regions matching a structure pattern.
     * Supports both standard WorldGuard flags and custom flags.
     */
    public int setFlag(String pattern, String flagName, String value) {
        if (!worldGuardAvailable) return 0;
        
        int updated = 0;
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId != null) {
                if (setRegionFlag(info.world, info.regionId, flagName, value)) {
                    updated++;
                }
            }
        }
        
        return updated;
    }
    
    /**
     * Set a flag on a specific region.
     */
    @SuppressWarnings("unchecked")
    public boolean setRegionFlag(String worldName, String regionId, String flagName, String value) {
        if (!worldGuardAvailable) return false;
        
        try {
            World bukkitWorld = Bukkit.getWorld(worldName);
            if (bukkitWorld == null) return false;
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = 
                WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
            
            if (regionManager == null) return false;
            
            ProtectedRegion region = regionManager.getRegion(regionId);
            if (region == null) return false;
            
            // Find the flag
            FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
            Flag<?> flag = flagRegistry.get(flagName);
            
            if (flag == null) {
                // Try standard flags
                flag = Flags.fuzzyMatchFlag(flagRegistry, flagName);
            }
            
            if (flag == null) {
                plugin.getLogger().warning("Unknown flag: " + flagName);
                return false;
            }
            
            // Parse and set the value
            if (value.equalsIgnoreCase("allow") && flag instanceof StateFlag) {
                region.setFlag((StateFlag) flag, StateFlag.State.ALLOW);
            } else if (value.equalsIgnoreCase("deny") && flag instanceof StateFlag) {
                region.setFlag((StateFlag) flag, StateFlag.State.DENY);
            } else if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("clear")) {
                region.setFlag(flag, null);
            } else {
                // Try to parse the value for the flag type
                try {
                    FlagContext context = FlagContext.create()
                        .setSender(com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapCommandSender(Bukkit.getConsoleSender()))
                        .setInput(value)
                        .build();
                    Object parsedValue = flag.parseInput(context);
                    region.setFlag((Flag<Object>) flag, parsedValue);
                } catch (Exception e) {
                    plugin.getLogger().warning("Invalid value '" + value + "' for flag " + flagName);
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set flag: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get all flags on a region.
     */
    public Map<String, String> getRegionFlags(String worldName, String regionId) {
        Map<String, String> flags = new LinkedHashMap<>();
        if (!worldGuardAvailable) return flags;
        
        try {
            World bukkitWorld = Bukkit.getWorld(worldName);
            if (bukkitWorld == null) return flags;
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = 
                WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
            
            if (regionManager == null) return flags;
            
            ProtectedRegion region = regionManager.getRegion(regionId);
            if (region == null) return flags;
            
            for (Map.Entry<Flag<?>, Object> entry : region.getFlags().entrySet()) {
                flags.put(entry.getKey().getName(), String.valueOf(entry.getValue()));
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get flags: " + e.getMessage());
        }
        
        return flags;
    }
    
    /**
     * Apply default protection flags from config.
     */
    @SuppressWarnings("unchecked")
    private void applyDefaultFlags(ProtectedRegion region) {
        Map<String, String> defaultFlags = plugin.getConfigManager().getDefaultFlags();
        
        for (Map.Entry<String, String> entry : defaultFlags.entrySet()) {
            try {
                FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
                Flag<?> flag = flagRegistry.get(entry.getKey());
                
                if (flag == null) {
                    flag = Flags.fuzzyMatchFlag(flagRegistry, entry.getKey());
                }
                
                if (flag == null) {
                    plugin.getConfigManager().debug("Unknown flag: " + entry.getKey());
                    continue;
                }
                
                String value = entry.getValue();
                
                if (flag instanceof StateFlag) {
                    // Handle state flags (allow/deny)
                    if (value.equalsIgnoreCase("allow")) {
                        region.setFlag((StateFlag) flag, StateFlag.State.ALLOW);
                    } else if (value.equalsIgnoreCase("deny")) {
                        region.setFlag((StateFlag) flag, StateFlag.State.DENY);
                    }
                } else {
                    // Handle other flag types (StringFlag, etc.)
                    try {
                        FlagContext context = FlagContext.create()
                            .setSender(com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapCommandSender(Bukkit.getConsoleSender()))
                            .setInput(value)
                            .build();
                        Object parsedValue = flag.parseInput(context);
                        region.setFlag((Flag<Object>) flag, parsedValue);
                        plugin.getConfigManager().debug("Set flag " + entry.getKey() + " = " + value);
                    } catch (Exception e) {
                        plugin.getConfigManager().debug("Failed to parse flag " + entry.getKey() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                plugin.getConfigManager().debug("Failed to set default flag " + entry.getKey() + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Get region info for a structure.
     */
    public ProtectedRegion getRegion(String worldName, String regionId) {
        if (!worldGuardAvailable) return null;
        
        try {
            World bukkitWorld = Bukkit.getWorld(worldName);
            if (bukkitWorld == null) return null;
            
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = 
                WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
            
            if (regionManager == null) return null;
            
            return regionManager.getRegion(regionId);
            
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Add an owner to all regions matching a pattern.
     * @param pattern Structure pattern to match
     * @param target Player name or g:groupname for permission groups
     * @return Number of regions updated
     */
    public int addOwner(String pattern, String target) {
        if (!worldGuardAvailable) return 0;
        
        int updated = 0;
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId != null) {
                if (addOwnerToRegion(info.world, info.regionId, target)) {
                    updated++;
                }
            }
        }
        
        return updated;
    }
    
    /**
     * Remove an owner from all regions matching a pattern.
     */
    public int removeOwner(String pattern, String target) {
        if (!worldGuardAvailable) return 0;
        
        int updated = 0;
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId != null) {
                if (removeOwnerFromRegion(info.world, info.regionId, target)) {
                    updated++;
                }
            }
        }
        
        return updated;
    }
    
    /**
     * Add a member to all regions matching a pattern.
     */
    public int addMember(String pattern, String target) {
        if (!worldGuardAvailable) return 0;
        
        int updated = 0;
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId != null) {
                if (addMemberToRegion(info.world, info.regionId, target)) {
                    updated++;
                }
            }
        }
        
        return updated;
    }
    
    /**
     * Remove a member from all regions matching a pattern.
     */
    public int removeMember(String pattern, String target) {
        if (!worldGuardAvailable) return 0;
        
        int updated = 0;
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId != null) {
                if (removeMemberFromRegion(info.world, info.regionId, target)) {
                    updated++;
                }
            }
        }
        
        return updated;
    }
    
    /**
     * Add owner to specific region.
     */
    private boolean addOwnerToRegion(String worldName, String regionId, String target) {
        try {
            ProtectedRegion region = getRegion(worldName, regionId);
            if (region == null) return false;
            
            if (target.startsWith("g:")) {
                // Permission group
                String group = target.substring(2);
                region.getOwners().addGroup(group);
            } else {
                // Player name - try to get UUID
                try {
                    java.util.UUID uuid = Bukkit.getOfflinePlayer(target).getUniqueId();
                    region.getOwners().addPlayer(uuid);
                } catch (Exception e) {
                    // Fallback to name-based (works for some WG versions)
                    region.getOwners().addPlayer(target);
                }
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add owner: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Remove owner from specific region.
     */
    private boolean removeOwnerFromRegion(String worldName, String regionId, String target) {
        try {
            ProtectedRegion region = getRegion(worldName, regionId);
            if (region == null) return false;
            
            if (target.startsWith("g:")) {
                String group = target.substring(2);
                region.getOwners().removeGroup(group);
            } else {
                try {
                    java.util.UUID uuid = Bukkit.getOfflinePlayer(target).getUniqueId();
                    region.getOwners().removePlayer(uuid);
                } catch (Exception e) {
                    region.getOwners().removePlayer(target);
                }
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove owner: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Add member to specific region.
     */
    private boolean addMemberToRegion(String worldName, String regionId, String target) {
        try {
            ProtectedRegion region = getRegion(worldName, regionId);
            if (region == null) return false;
            
            if (target.startsWith("g:")) {
                String group = target.substring(2);
                region.getMembers().addGroup(group);
            } else {
                try {
                    java.util.UUID uuid = Bukkit.getOfflinePlayer(target).getUniqueId();
                    region.getMembers().addPlayer(uuid);
                } catch (Exception e) {
                    region.getMembers().addPlayer(target);
                }
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add member: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Remove member from specific region.
     */
    private boolean removeMemberFromRegion(String worldName, String regionId, String target) {
        try {
            ProtectedRegion region = getRegion(worldName, regionId);
            if (region == null) return false;
            
            if (target.startsWith("g:")) {
                String group = target.substring(2);
                region.getMembers().removeGroup(group);
            } else {
                try {
                    java.util.UUID uuid = Bukkit.getOfflinePlayer(target).getUniqueId();
                    region.getMembers().removePlayer(uuid);
                } catch (Exception e) {
                    region.getMembers().removePlayer(target);
                }
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove member: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Sync region settings from config file.
     * Updates all existing regions with the current flags from their matching protection rules.
     */
    @SuppressWarnings("unchecked")
    public void syncFromConfig() {
        if (!worldGuardAvailable) {
            plugin.getLogger().warning("WorldGuard not available - cannot sync regions");
            return;
        }
        
        plugin.getLogger().info("Syncing region settings from config...");
        
        int updatedCount = 0;
        int errorCount = 0;
        
        // Get all protected structures from database
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getProtectedStructures("");
        
        for (StructureDatabase.StructureInfo info : structures) {
            if (info.regionId == null) continue;
            
            try {
                World bukkitWorld = Bukkit.getWorld(info.world);
                if (bukkitWorld == null) continue;
                
                com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
                com.sk89q.worldguard.protection.managers.RegionManager regionManager = 
                    WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
                
                if (regionManager == null) continue;
                
                ProtectedRegion region = regionManager.getRegion(info.regionId);
                if (region == null) continue;
                
                // Get the protection rule for this structure type
                ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(info.type);
                
                // Determine which flags to apply
                Map<String, String> flagsToApply;
                if (rule != null && rule.flags != null && !rule.flags.isEmpty()) {
                    // Use rule-specific flags (which already include defaults merged in)
                    flagsToApply = rule.flags;
                } else {
                    // Fall back to default flags only
                    flagsToApply = plugin.getConfigManager().getDefaultFlags();
                }
                
                // Apply flags to region
                for (Map.Entry<String, String> entry : flagsToApply.entrySet()) {
                    String flagName = entry.getKey();
                    String flagValue = entry.getValue();
                    
                    try {
                        FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
                        Flag<?> flag = flagRegistry.get(flagName);
                        
                        if (flag == null) {
                            flag = Flags.fuzzyMatchFlag(flagRegistry, flagName);
                        }
                        
                        if (flag == null) {
                            plugin.getConfigManager().debug("Unknown flag: " + flagName);
                            continue;
                        }
                        
                        if (flag instanceof StateFlag) {
                            // Handle state flags (allow/deny)
                            if (flagValue.equalsIgnoreCase("allow")) {
                                region.setFlag((StateFlag) flag, StateFlag.State.ALLOW);
                            } else if (flagValue.equalsIgnoreCase("deny")) {
                                region.setFlag((StateFlag) flag, StateFlag.State.DENY);
                            }
                        } else {
                            // Handle other flag types (StringFlag, etc.)
                            FlagContext context = FlagContext.create()
                                .setSender(com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapCommandSender(Bukkit.getConsoleSender()))
                                .setInput(flagValue)
                                .build();
                            Object parsedValue = flag.parseInput(context);
                            region.setFlag((Flag<Object>) flag, parsedValue);
                        }
                    } catch (Exception e) {
                        plugin.getConfigManager().debug("Failed to set flag " + flagName + " on " + info.regionId + ": " + e.getMessage());
                    }
                }
                
                updatedCount++;
                plugin.getConfigManager().debug("Synced flags for region: " + info.regionId);
                
            } catch (Exception e) {
                errorCount++;
                plugin.getConfigManager().debug("Failed to sync region " + info.regionId + ": " + e.getMessage());
            }
        }
        
        plugin.getLogger().info("Synced " + updatedCount + " regions from config" + 
            (errorCount > 0 ? " (" + errorCount + " errors)" : ""));
        
        // Now check for unprotected structures that should be protected
        protectUnprotectedStructures();
    }
    
    /**
     * Create regions for structures in the database that match protection rules but have no region.
     * Called during reload to retroactively protect discovered structures.
     */
    public int protectUnprotectedStructures() {
        if (!worldGuardAvailable) {
            return 0;
        }
        
        int createdCount = 0;
        
        // Get all unprotected structures from database
        List<StructureDatabase.StructureInfo> unprotected = plugin.getDatabase().getUnprotectedStructures("");
        
        plugin.getConfigManager().debug("Checking " + unprotected.size() + " unprotected structures in database...");
        
        for (StructureDatabase.StructureInfo info : unprotected) {
            // Check if there's now a matching protection rule
            ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(info.type);
            
            if (rule == null || !rule.enabled) {
                continue; // No matching enabled rule
            }
            
            // Check if world is disabled
            if (plugin.getConfigManager().isWorldDisabled(info.world)) {
                continue;
            }
            
            // Create region for this structure
            try {
                String regionId = createRegionWithFlags(info, rule.radius, rule.yMin, rule.yMax, rule.flags);
                
                if (regionId != null) {
                    createdCount++;
                    plugin.getLogger().info("Auto-protected " + info.type + " at " + info.x + "," + info.z + " -> " + regionId);
                }
            } catch (Exception e) {
                plugin.getConfigManager().debug("Failed to protect " + info.type + ": " + e.getMessage());
            }
        }
        
        if (createdCount > 0) {
            plugin.getLogger().info("Created " + createdCount + " new regions for previously discovered structures");
        }
        
        return createdCount;
    }
    
    /**
     * Get all available flag names for tab completion.
     */
    public List<String> getAvailableFlags() {
        List<String> flags = new ArrayList<>();
        
        if (!worldGuardAvailable) return flags;
        
        try {
            FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
            for (Flag<?> flag : registry.getAll()) {
                flags.add(flag.getName());
            }
            Collections.sort(flags);
        } catch (Exception e) {
            // Fallback to common flags
            flags.addAll(Arrays.asList(
                "build", "block-break", "block-place", "use", "interact",
                "pvp", "mob-spawning", "mob-damage", "creeper-explosion",
                "tnt", "fire-spread", "entry", "exit", "greeting", "farewell"
            ));
        }
        
        return flags;
    }
}
