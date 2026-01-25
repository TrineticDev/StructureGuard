package com.structureguard;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens for chunk loads and automatically protects structures
 * that match the configured protection rules.
 * 
 * This is the core of the on-demand protection system - no pre-scanning needed.
 * Structures are protected as their origin chunks are loaded/generated.
 * 
 * Uses a bounded thread pool and chunk queue to prevent thread explosion
 * when many chunks load simultaneously (e.g., mass player login).
 */
public class ChunkLoadListener implements Listener {
    
    private final StructureGuardPlugin plugin;
    
    // Track processed chunks to avoid duplicate processing on reload
    private final Set<Long> processedChunks = Collections.synchronizedSet(new HashSet<>());
    
    // Statistics
    private final AtomicLong processedChunkCount = new AtomicLong(0);
    private final AtomicLong protectedStructureCount = new AtomicLong(0);
    
    // Single-thread executor for queue processing (prevents thread explosion)
    private final ExecutorService chunkExecutor;
    
    // Queue for pending chunks - bounded to prevent memory issues
    private static final int MAX_QUEUE_SIZE = 500;
    private final BlockingQueue<ChunkTask> chunkQueue;
    
    // Batch buffer for marking chunks as scanned (reduces DB writes)
    // Maps worldName -> list of chunk coords
    private final Map<String, List<int[]>> scannedChunkBuffers = new ConcurrentHashMap<>();
    private static final int BATCH_FLUSH_SIZE = 50;
    private volatile long lastFlushTime = System.currentTimeMillis();
    private static final long BATCH_FLUSH_INTERVAL_MS = 5000; // Flush every 5 seconds at minimum
    
    // Simple record to hold pending chunk info
    private static class ChunkTask {
        final World world;
        final int chunkX;
        final int chunkZ;
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
    
    public ChunkLoadListener(StructureGuardPlugin plugin) {
        this.plugin = plugin;
        
        // Use a bounded queue - if full, new chunks are dropped (they'll be processed next load)
        this.chunkQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        
        // Create a single-thread executor for the queue processor
        // This is separate from the chunk queue - it just runs our processor loop
        this.chunkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StructureGuard-Worker");
            t.setDaemon(true);
            return t;
        });
        
        // Start the worker that processes the queue
        startQueueProcessor();
        
        // Start the periodic batch flush task
        startBatchFlushTask();
    }
    
    /**
     * Start a single worker that processes chunks from the queue.
     * This ensures controlled, sequential processing.
     */
    private void startQueueProcessor() {
        chunkExecutor.submit(() -> {
            plugin.getLogger().info("Chunk processor started");
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // Take a chunk from the queue (blocks if empty)
                        ChunkTask task = chunkQueue.poll(1, TimeUnit.SECONDS);
                        if (task == null) continue;
                        
                        processChunkTask(task);
                        
                        // Small delay between chunks to prevent CPU spikes
                        Thread.sleep(10);
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        plugin.getLogger().warning("Queue processor error: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            } finally {
                plugin.getLogger().info("Chunk processor stopped");
            }
        });
    }
    
    /**
     * Start a periodic task to flush the scanned chunk buffer to the database.
     */
    private void startBatchFlushTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            flushAllBuffers(false);
        }, 100L, 100L); // Every 5 seconds (100 ticks)
    }
    
    /**
     * Flush all world buffers to the database.
     */
    private void flushAllBuffers(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastFlushTime) < BATCH_FLUSH_INTERVAL_MS) {
            return;
        }
        
        lastFlushTime = now;
        for (String worldName : scannedChunkBuffers.keySet()) {
            flushWorldBuffer(worldName);
        }
    }
    
    /**
     * Process a single chunk task.
     * Database check is done async, but structure detection must be on main thread.
     */
    private void processChunkTask(ChunkTask task) {
        try {
            // Check database for chunks scanned in previous sessions (can be done async)
            if (plugin.getDatabase().isChunkScanned(task.worldName, task.chunkX, task.chunkZ)) {
                processedChunks.add(task.chunkKey); // Add to memory cache
                return;
            }
            
            // Schedule structure detection on main thread (required for NMS access)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    processChunkStructuresMainThread(task);
                } catch (Exception e) {
                    plugin.getConfigManager().debug("Error processing chunk on main thread: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getConfigManager().debug("Error processing chunk " + task.chunkX + "," + task.chunkZ + ": " + e.getMessage());
        }
    }
    
    /**
     * Process chunk structures on main thread (required for NMS/reflection access).
     */
    private void processChunkStructuresMainThread(ChunkTask task) {
        processChunkStructures(task.world, task.chunkX, task.chunkZ);
        processedChunks.add(task.chunkKey);
        processedChunkCount.incrementAndGet();
        
        // Add to buffer for batch DB write (thread-safe)
        List<int[]> buffer = scannedChunkBuffers.computeIfAbsent(task.worldName, 
            k -> Collections.synchronizedList(new ArrayList<>()));
        buffer.add(new int[]{task.chunkX, task.chunkZ});
        
        // Flush if buffer is full for this world
        if (buffer.size() >= BATCH_FLUSH_SIZE) {
            // Schedule DB write async to not block main thread
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                flushWorldBuffer(task.worldName);
            });
        }
    }
    
    /**
     * Flush the scanned chunk buffer for a specific world to the database.
     */
    private void flushWorldBuffer(String worldName) {
        List<int[]> buffer = scannedChunkBuffers.get(worldName);
        if (buffer == null || buffer.isEmpty()) return;
        
        List<int[]> toFlush;
        synchronized (buffer) {
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        }
        
        if (!toFlush.isEmpty()) {
            plugin.getDatabase().markChunksScanned(worldName, toFlush);
        }
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
        
        // Fast path: check in-memory cache for scanned chunks
        // Don't hit the database in the event handler - defer to the queue processor
        
        // Queue the chunk for async processing (non-blocking)
        final int chunkX = chunk.getX();
        final int chunkZ = chunk.getZ();
        final String worldName = world.getName();
        
        ChunkTask task = new ChunkTask(world, chunkX, chunkZ, worldName, chunkKey);
        
        // Offer to queue - returns false if full (chunk will be picked up next time it loads)
        if (!chunkQueue.offer(task)) {
            plugin.getConfigManager().debug("Chunk queue full, skipping chunk " + chunkX + "," + chunkZ);
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
    }
    
    /**
     * Shutdown the chunk processor gracefully.
     * Flushes pending data and stops worker threads.
     */
    public void shutdown() {
        plugin.getConfigManager().debug("Shutting down chunk processor...");
        
        // Flush any pending scanned chunks to database
        flushAllBuffers(true);
        
        // Shutdown the executor
        chunkExecutor.shutdownNow();
        try {
            if (!chunkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Chunk processor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Clear the queue and buffers
        chunkQueue.clear();
        scannedChunkBuffers.clear();
        
        plugin.getConfigManager().debug("Chunk processor shutdown complete");
    }
    
    /**
     * Get the current queue size (for monitoring).
     */
    public int getQueueSize() {
        return chunkQueue.size();
    }
}
