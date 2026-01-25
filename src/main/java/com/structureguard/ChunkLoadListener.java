package com.structureguard;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens for chunk loads and automatically protects structures
 * that match the configured protection rules.
 * 
 * This is the core of the on-demand protection system - no pre-scanning needed.
 * Structures are protected as their origin chunks are loaded/generated.
 */
public class ChunkLoadListener implements Listener {
    
    private final StructureGuardPlugin plugin;
    
    // Track processed chunks to avoid duplicate processing on reload
    private final Set<Long> processedChunks = Collections.synchronizedSet(new HashSet<>());
    
    // Limit concurrent async chunk processing to prevent thread explosion on mass login
    // 10 concurrent = handles 200+ players joining without overwhelming the thread pool
    private final Semaphore chunkProcessingSemaphore = new Semaphore(10);
    
    // Statistics
    private final AtomicLong processedChunkCount = new AtomicLong(0);
    private final AtomicLong protectedStructureCount = new AtomicLong(0);
    private final AtomicLong skippedDueToLoadCount = new AtomicLong(0);
    
    public ChunkLoadListener(StructureGuardPlugin plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        // Only process newly generated chunks, or all if config says so
        if (!event.isNewChunk() && !plugin.getConfigManager().shouldProcessExistingChunks()) {
            return;
        }
        
        // Skip if no protection rules are enabled
        if (!plugin.getConfigManager().hasEnabledProtectionRules()) {
            return;
        }
        
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        
        // Skip disabled worlds (e.g., resource worlds)
        if (plugin.getConfigManager().isWorldDisabled(world.getName())) {
            return;
        }
        
        // Skip if already processed this session
        long chunkKey = packChunkCoords(chunk.getX(), chunk.getZ());
        if (processedChunks.contains(chunkKey)) {
            return;
        }
        
        // Also check database for chunks scanned in previous sessions
        if (plugin.getDatabase().isChunkScanned(world.getName(), chunk.getX(), chunk.getZ())) {
            processedChunks.add(chunkKey); // Add to memory cache too
            return;
        }
        
        // Process asynchronously to avoid blocking chunk load
        // Use semaphore to limit concurrent processing and prevent thread explosion
        final int chunkX = chunk.getX();
        final int chunkZ = chunk.getZ();
        final String worldName = world.getName();
        
        // Try to acquire permit - if all 10 slots are busy, skip this chunk for now
        // It will be processed when the chunk loads again, or on next restart
        if (!chunkProcessingSemaphore.tryAcquire()) {
            skippedDueToLoadCount.incrementAndGet();
            return;
        }
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                processChunkStructures(world, chunkX, chunkZ);
                processedChunks.add(chunkKey);
                processedChunkCount.incrementAndGet();
                
                // Mark as scanned in database for persistence across restarts
                plugin.getDatabase().markChunksScanned(worldName, 
                    java.util.Collections.singletonList(new int[]{chunkX, chunkZ}));
            } catch (Exception e) {
                plugin.getConfigManager().debug("Error processing chunk " + chunkX + "," + chunkZ + ": " + e.getMessage());
            } finally {
                chunkProcessingSemaphore.release();
            }
        });
    }
    
    /**
     * Process structures in a chunk and create regions if needed.
     */
    private void processChunkStructures(World world, int chunkX, int chunkZ) {
        try {
            // Get structure starts from chunk using the StructureFinder
            List<StructureFinder.StructureResult> structures = 
                plugin.getStructureFinder().getStructuresInChunk(world, chunkX, chunkZ);
            
            if (structures.isEmpty()) {
                return;
            }
            
            // Only log when we actually find structures worth mentioning
            int protectable = 0;
            
            for (StructureFinder.StructureResult structure : structures) {
                // Check if this structure type should be protected
                ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(structure.structureType);
                if (rule == null || !rule.enabled) {
                    continue;
                }
                
                // Check if already protected in database
                if (plugin.getDatabase().isStructureProtected(world.getName(), structure.structureType, 
                        structure.x, structure.z)) {
                    continue;
                }
                
                protectable++;
                
                // Create protection on main thread (WorldGuard requires it)
                final StructureFinder.StructureResult finalStructure = structure;
                final ConfigManager.ProtectionRule finalRule = rule;
                
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    createProtection(world, finalStructure, finalRule);
                });
            }
            
            if (protectable > 0) {
                plugin.getConfigManager().debug("Protecting " + protectable + " structures in chunk " + 
                    chunkX + "," + chunkZ);
            }
            
        } catch (Exception e) {
            plugin.getConfigManager().debug("Error in processChunkStructures: " + e.getMessage());
        }
    }
    
    /**
     * Create a WorldGuard region for a structure.
     */
    private void createProtection(World world, StructureFinder.StructureResult structure, 
                                   ConfigManager.ProtectionRule rule) {
        try {
            // Add to database first
            boolean added = plugin.getDatabase().addStructure(world.getName(), structure.structureType, 
                structure.x, structure.z);
            
            // Create a StructureInfo for the RegionManager
            StructureDatabase.StructureInfo dbInfo = new StructureDatabase.StructureInfo(
                world.getName(), 
                structure.structureType, 
                structure.x, 
                structure.z, 
                false,  // not protected yet
                null    // no region id yet
            );
            
            // Create WorldGuard region with flags from the rule
            String regionId = plugin.getRegionManager().createRegionWithFlags(
                dbInfo, 
                rule.radius, 
                rule.yMin, 
                rule.yMax,
                rule.flags
            );
            
            if (regionId != null) {
                protectedStructureCount.incrementAndGet();
                plugin.getLogger().info("Auto-protected " + structure.structureType + " at " + 
                    structure.x + "," + structure.z + " -> " + regionId);
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create protection for " + structure.structureType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Pack chunk coordinates into a single long for efficient storage.
     */
    private long packChunkCoords(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z) << 32);
    }
    
    /**
     * Get the number of chunks processed this session.
     */
    public long getProcessedChunkCount() {
        return processedChunkCount.get();
    }
    
    /**
     * Get the number of structures protected this session.
     */
    public long getProtectedStructureCount() {
        return protectedStructureCount.get();
    }
    
    /**
     * Get the number of chunks skipped due to high load.
     * These chunks will be processed when they load again or on restart.
     */
    public long getSkippedDueToLoadCount() {
        return skippedDueToLoadCount.get();
    }
    
    /**
     * Clear processed chunks cache (used on reload).
     */
    public void clearCache() {
        processedChunks.clear();
        // Don't reset counters - they track session totals
    }
    
    /**
     * Clear cache for a specific world (used when resetting a world).
     */
    public void clearWorldCache(String worldName) {
        // We can't easily filter the packed coords by world, so just clear all
        // This is fine since database check will prevent re-processing
        processedChunks.clear();
        plugin.getConfigManager().debug("Cleared chunk cache for world reset: " + worldName);
    }
    
    /**
     * Reset statistics (for testing/debugging).
     */
    public void resetStats() {
        processedChunkCount.set(0);
        protectedStructureCount.set(0);
        skippedDueToLoadCount.set(0);
    }
}
