package com.structureguard;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens for chunk loads and automatically protects structures
 * that match the configured protection rules.
 * 
 * PERFORMANCE OPTIMIZED:
 * - In-memory cache of scanned chunks (loaded from DB on startup)
 * - No synchronous DB queries on main thread
 * - Rate-limited concurrent tasks (max 10)
 */
public class ChunkLoadListener implements Listener {
    
    private final StructureGuardPlugin plugin;
    
    // In-memory cache of scanned chunks per world - loaded from DB on startup
    // Key: worldName, Value: Set of packed chunk coordinates
    private final Map<String, Set<Long>> scannedChunksCache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;
    
    // Rate limiting: max concurrent async tasks to prevent thread explosion
    private static final int MAX_CONCURRENT_TASKS = 10;
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    
    // Queue for chunks waiting to be processed (when at max concurrent)
    private final ConcurrentLinkedQueue<ChunkTask> pendingChunks = new ConcurrentLinkedQueue<>();
    
    // Pending DB writes - batched for efficiency
    private final ConcurrentLinkedQueue<ChunkWriteTask> pendingDbWrites = new ConcurrentLinkedQueue<>();
    private static final int DB_BATCH_SIZE = 50;
    
    // Statistics
    private final AtomicLong processedChunkCount = new AtomicLong(0);
    private final AtomicLong protectedStructureCount = new AtomicLong(0);
    
    // Simple holder for chunk task data
    private static class ChunkTask {
        final World world;
        final int chunkX, chunkZ;
        final String worldName;
        final long chunkKey;
        
        ChunkTask(World world, int chunkX, int chunkZ, String worldName, long chunkKey) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.worldName = worldName;
            this.chunkKey = chunkKey;
        }
    }
    
    private static class ChunkWriteTask {
        final String worldName;
        final int chunkX, chunkZ;
        ChunkWriteTask(String worldName, int chunkX, int chunkZ) {
            this.worldName = worldName;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
    
    public ChunkLoadListener(StructureGuardPlugin plugin) {
        this.plugin = plugin;
        // Load scanned chunks cache on startup
        loadCacheSync();
    }
    
    /**
     * Load scanned chunks from database into memory cache.
     * Done synchronously during plugin enable to ensure cache is ready.
     */
    private void loadCacheSync() {
        for (World world : plugin.getServer().getWorlds()) {
            String worldName = world.getName();
            Set<Long> chunks = plugin.getDatabase().getScannedChunks(worldName);
            Set<Long> cacheSet = ConcurrentHashMap.newKeySet();
            cacheSet.addAll(chunks);
            scannedChunksCache.put(worldName, cacheSet);
            plugin.getLogger().info("Loaded " + chunks.size() + " scanned chunks for " + worldName);
            
            // Also initialize the StructureFinder for this world
            plugin.getStructureFinder().initForChunkListener(world);
        }
        cacheLoaded = true;
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
        String worldName = world.getName();
        
        // Skip disabled worlds (e.g., resource worlds)
        if (plugin.getConfigManager().isWorldDisabled(worldName)) {
            return;
        }
        
        // Fast in-memory check - NO database query on main thread!
        long chunkKey = packChunkCoords(chunk.getX(), chunk.getZ());
        if (isChunkScannedCached(worldName, chunkKey)) {
            return;
        }
        
        final int chunkX = chunk.getX();
        final int chunkZ = chunk.getZ();
        
        ChunkTask task = new ChunkTask(world, chunkX, chunkZ, worldName, chunkKey);
        
        // Rate limiting: if under limit, process immediately; otherwise queue
        if (activeTaskCount.get() < MAX_CONCURRENT_TASKS) {
            startAsyncTask(task);
        } else {
            // Queue for later - will be picked up when a task completes
            pendingChunks.offer(task);
        }
    }
    
    /**
     * Check if chunk is scanned using in-memory cache (O(1) lookup, no DB query).
     */
    private boolean isChunkScannedCached(String worldName, long chunkKey) {
        Set<Long> worldCache = scannedChunksCache.get(worldName);
        if (worldCache == null) {
            // World not in cache yet - create empty set
            worldCache = ConcurrentHashMap.newKeySet();
            scannedChunksCache.put(worldName, worldCache);
            return false;
        }
        return worldCache.contains(chunkKey);
    }
    
    /**
     * Mark chunk as scanned in memory cache and queue for DB write.
     */
    private void markChunkScannedCached(String worldName, int chunkX, int chunkZ, long chunkKey) {
        // Add to memory cache immediately
        Set<Long> worldCache = scannedChunksCache.computeIfAbsent(worldName, k -> ConcurrentHashMap.newKeySet());
        worldCache.add(chunkKey);
        
        // Queue for batched DB write
        pendingDbWrites.offer(new ChunkWriteTask(worldName, chunkX, chunkZ));
        
        // Flush to DB if batch size reached
        if (pendingDbWrites.size() >= DB_BATCH_SIZE) {
            flushDbWrites();
        }
    }
    
    /**
     * Flush pending DB writes asynchronously.
     */
    private void flushDbWrites() {
        // Group by world
        Map<String, List<int[]>> byWorld = new HashMap<>();
        ChunkWriteTask task;
        int count = 0;
        while ((task = pendingDbWrites.poll()) != null && count < DB_BATCH_SIZE * 2) {
            byWorld.computeIfAbsent(task.worldName, k -> new ArrayList<>())
                   .add(new int[]{task.chunkX, task.chunkZ});
            count++;
        }
        
        if (!byWorld.isEmpty()) {
            // Write to DB asynchronously
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                for (Map.Entry<String, List<int[]>> entry : byWorld.entrySet()) {
                    plugin.getDatabase().markChunksScanned(entry.getKey(), entry.getValue());
                }
            });
        }
    }
    
    /**
     * Start an async task using Bukkit's scheduler (maintains proper context for NMS).
     */
    private void startAsyncTask(ChunkTask task) {
        activeTaskCount.incrementAndGet();
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                processChunkStructures(task.world, task.chunkX, task.chunkZ);
                processedChunkCount.incrementAndGet();
                
                // Mark as scanned in memory cache + queue for batched DB write
                markChunkScannedCached(task.worldName, task.chunkX, task.chunkZ, task.chunkKey);
            } catch (Exception e) {
                plugin.getConfigManager().debug("Error processing chunk " + task.chunkX + "," + task.chunkZ + ": " + e.getMessage());
            } finally {
                activeTaskCount.decrementAndGet();
                // Process next queued chunk if any
                processNextQueued();
            }
        });
    }
    
    /**
     * Process the next queued chunk if there's capacity.
     */
    private void processNextQueued() {
        if (activeTaskCount.get() < MAX_CONCURRENT_TASKS) {
            ChunkTask next = pendingChunks.poll();
            if (next != null) {
                startAsyncTask(next);
            }
        }
    }
    
    /**
     * Process structures in a chunk and create regions if needed.
     */
    private void processChunkStructures(World world, int chunkX, int chunkZ) {
        try {
            // Get structure starts from chunk using the StructureFinder
            List<StructureFinder.StructureResult> structures = 
                plugin.getStructureFinder().getStructuresInChunk(world, chunkX, chunkZ);
            
            plugin.getConfigManager().debug("processChunkStructures: chunk " + chunkX + "," + chunkZ + 
                " found " + structures.size() + " structures");
            
            if (structures.isEmpty()) {
                return;
            }
            
            // Only log when we actually find structures worth mentioning
            int protectable = 0;
            
            for (StructureFinder.StructureResult structure : structures) {
                plugin.getConfigManager().debug("  Checking structure: " + structure.structureType);
                
                // Check if this structure type should be protected
                ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(structure.structureType);
                if (rule == null || !rule.enabled) {
                    plugin.getConfigManager().debug("    No matching rule or not enabled (rule=" + 
                        (rule != null ? rule.pattern : "null") + ")");
                    continue;
                }
                
                plugin.getConfigManager().debug("    Found matching rule: " + rule.pattern);
                
                // Check if already protected in database
                if (plugin.getDatabase().isStructureProtected(world.getName(), structure.structureType, 
                        structure.x, structure.z)) {
                    plugin.getConfigManager().debug("    Already protected in database");
                    continue;
                }
                
                protectable++;
                plugin.getConfigManager().debug("    Will protect this structure!");
                
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
     * Get pending queue size (chunks waiting to be processed).
     */
    public long getPendingCount() {
        return pendingChunks.size();
    }
    
    /**
     * Get current active task count.
     */
    public int getActiveTaskCount() {
        return activeTaskCount.get();
    }
    
    /**
     * Get cached chunk count for a world.
     */
    public int getCachedChunkCount(String worldName) {
        Set<Long> cache = scannedChunksCache.get(worldName);
        return cache != null ? cache.size() : 0;
    }
    
    /**
     * Clear all caches (used on reload).
     */
    public void clearCache() {
        // Flush any pending DB writes first
        flushDbWrites();
        // Clear all world caches
        scannedChunksCache.clear();
        // Reload cache from DB
        loadCacheSync();
        plugin.getConfigManager().debug("Cleared and reloaded all chunk caches");
    }
    
    /**
     * Clear cache for a specific world (used when resetting a world).
     */
    public void clearWorldCache(String worldName) {
        Set<Long> cache = scannedChunksCache.get(worldName);
        if (cache != null) {
            cache.clear();
        }
        // Flush any pending DB writes
        flushDbWrites();
        plugin.getConfigManager().debug("Cleared chunk cache for world reset: " + worldName);
    }
    
    /**
     * Flush remaining DB writes on shutdown.
     */
    public void shutdown() {
        // Flush all pending writes grouped by world
        Map<String, List<int[]>> byWorld = new HashMap<>();
        ChunkWriteTask task;
        while ((task = pendingDbWrites.poll()) != null) {
            byWorld.computeIfAbsent(task.worldName, k -> new ArrayList<>())
                   .add(new int[]{task.chunkX, task.chunkZ});
        }
        
        for (Map.Entry<String, List<int[]>> entry : byWorld.entrySet()) {
            plugin.getDatabase().markChunksScanned(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * Reset statistics (for testing/debugging).
     */
    public void resetStats() {
        processedChunkCount.set(0);
        protectedStructureCount.set(0);
    }
}
