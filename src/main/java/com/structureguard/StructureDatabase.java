package com.structureguard;

import java.sql.*;
import java.util.*;

/**
 * SQLite database for storing discovered structure locations.
 * Each structure gets one entry - no duplicates.
 */
public class StructureDatabase {
    
    private final StructureGuardPlugin plugin;
    private Connection connection;
    
    public StructureDatabase(StructureGuardPlugin plugin) {
        this.plugin = plugin;
        initialize();
    }
    
    private void initialize() {
        try {
            String dbPath = plugin.getDataFolder().getAbsolutePath() + "/structures.db";
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS structures (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "world TEXT NOT NULL," +
                    "structure_type TEXT NOT NULL," +
                    "x INTEGER NOT NULL," +
                    "z INTEGER NOT NULL," +
                    "has_region INTEGER DEFAULT 0," +
                    "region_id TEXT," +
                    "UNIQUE(world, structure_type, x, z))"
                );
                
                // Track which chunks have been scanned (for resume-aware scanning)
                stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS scanned_chunks (" +
                    "world TEXT NOT NULL," +
                    "chunk_x INTEGER NOT NULL," +
                    "chunk_z INTEGER NOT NULL," +
                    "scanned_at INTEGER DEFAULT (strftime('%s','now'))," +
                    "PRIMARY KEY(world, chunk_x, chunk_z))"
                );
                
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_type ON structures(structure_type)"
                );
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_world ON structures(world)"
                );
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_region ON structures(region_id)"
                );
            }
            
            plugin.getLogger().info("Structure database initialized");
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
        }
    }
    
    /**
     * Add a structure to the database. Ignores duplicates.
     */
    public boolean addStructure(String world, String structureType, int x, int z) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR IGNORE INTO structures (world, structure_type, x, z) VALUES (?, ?, ?, ?)"
            );
            stmt.setString(1, world);
            stmt.setString(2, structureType);
            stmt.setInt(3, x);
            stmt.setInt(4, z);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to add structure: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Batch add multiple structures for better performance.
     * Uses a transaction for atomicity and speed.
     * @return Number of structures actually inserted (ignores duplicates)
     */
    public int addStructuresBatch(String world, List<Object[]> structures) {
        if (structures == null || structures.isEmpty()) return 0;
        
        int inserted = 0;
        try {
            connection.setAutoCommit(false);
            PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR IGNORE INTO structures (world, structure_type, x, z) VALUES (?, ?, ?, ?)"
            );
            
            for (Object[] s : structures) {
                stmt.setString(1, world);
                stmt.setString(2, (String) s[0]); // structureType
                stmt.setInt(3, (Integer) s[1]);   // x
                stmt.setInt(4, (Integer) s[2]);   // z
                stmt.addBatch();
            }
            
            int[] results = stmt.executeBatch();
            for (int r : results) {
                if (r > 0) inserted++;
            }
            
            connection.commit();
            connection.setAutoCommit(true);
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed batch insert: " + e.getMessage());
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException e2) {
                // Ignore rollback errors
            }
        }
        return inserted;
    }
    
    // ==================== SCANNED CHUNKS TRACKING ====================
    
    /**
     * Check if a chunk has already been scanned.
     */
    public boolean isChunkScanned(String world, int chunkX, int chunkZ) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT 1 FROM scanned_chunks WHERE world = ? AND chunk_x = ? AND chunk_z = ?"
            );
            stmt.setString(1, world);
            stmt.setInt(2, chunkX);
            stmt.setInt(3, chunkZ);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Get all scanned chunks for a world as a Set for fast lookup.
     * Returns packed long values (chunkX | (chunkZ << 32))
     */
    public Set<Long> getScannedChunks(String world) {
        Set<Long> scanned = new HashSet<>();
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT chunk_x, chunk_z FROM scanned_chunks WHERE world = ?"
            );
            stmt.setString(1, world);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                int x = rs.getInt("chunk_x");
                int z = rs.getInt("chunk_z");
                // Pack into long for fast HashSet lookup
                long packed = ((long) x & 0xFFFFFFFFL) | (((long) z) << 32);
                scanned.add(packed);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get scanned chunks: " + e.getMessage());
        }
        return scanned;
    }
    
    /**
     * Mark chunks as scanned in batch (for performance).
     */
    public void markChunksScanned(String world, List<int[]> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        
        try {
            connection.setAutoCommit(false);
            PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR IGNORE INTO scanned_chunks (world, chunk_x, chunk_z) VALUES (?, ?, ?)"
            );
            
            for (int[] chunk : chunks) {
                stmt.setString(1, world);
                stmt.setInt(2, chunk[0]);
                stmt.setInt(3, chunk[1]);
                stmt.addBatch();
            }
            
            stmt.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to mark chunks scanned: " + e.getMessage());
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException e2) {
                // Ignore
            }
        }
    }
    
    /**
     * Clear all scanned chunk records for a world (for rescan).
     */
    public int clearScannedChunks(String world) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM scanned_chunks WHERE world = ?"
            );
            stmt.setString(1, world);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clear scanned chunks: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get count of scanned chunks for a world.
     */
    public int getScannedChunkCount(String world) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM scanned_chunks WHERE world = ?"
            );
            stmt.setString(1, world);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            // Ignore
        }
        return 0;
    }
    
    // ==================== STRUCTURE MANAGEMENT ====================
    
    /**
     * Mark a structure as having a WorldGuard region.
     */
    public void setRegionId(String world, String structureType, int x, int z, String regionId) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "UPDATE structures SET has_region = 1, region_id = ? " +
                "WHERE world = ? AND structure_type = ? AND x = ? AND z = ?"
            );
            stmt.setString(1, regionId);
            stmt.setString(2, world);
            stmt.setString(3, structureType);
            stmt.setInt(4, x);
            stmt.setInt(5, z);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update region: " + e.getMessage());
        }
    }
    
    /**
     * Clear region association for structures matching pattern.
     */
    public int clearRegions(String pattern) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "UPDATE structures SET has_region = 0, region_id = NULL WHERE structure_type LIKE ?"
            );
            stmt.setString(1, "%" + pattern + "%");
            return stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clear regions: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get all structures matching a pattern (partial match supported).
     */
    public List<StructureInfo> getStructures(String pattern) {
        List<StructureInfo> results = new ArrayList<>();
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM structures WHERE structure_type LIKE ? ORDER BY structure_type, x, z"
            );
            stmt.setString(1, "%" + pattern + "%");
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                results.add(new StructureInfo(
                    rs.getString("world"),
                    rs.getString("structure_type"),
                    rs.getInt("x"),
                    rs.getInt("z"),
                    rs.getInt("has_region") == 1,
                    rs.getString("region_id")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to query structures: " + e.getMessage());
        }
        return results;
    }
    
    /**
     * Get structures without WorldGuard regions matching a pattern.
     */
    public List<StructureInfo> getUnprotectedStructures(String pattern) {
        List<StructureInfo> results = new ArrayList<>();
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM structures WHERE structure_type LIKE ? AND has_region = 0 ORDER BY structure_type, x, z"
            );
            stmt.setString(1, "%" + pattern + "%");
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                results.add(new StructureInfo(
                    rs.getString("world"),
                    rs.getString("structure_type"),
                    rs.getInt("x"),
                    rs.getInt("z"),
                    false,
                    null
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to query unprotected structures: " + e.getMessage());
        }
        return results;
    }
    
    /**
     * Get all protected structures (those with WorldGuard regions).
     */
    public List<StructureInfo> getProtectedStructures(String pattern) {
        List<StructureInfo> results = new ArrayList<>();
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM structures WHERE structure_type LIKE ? AND has_region = 1 ORDER BY structure_type, x, z"
            );
            stmt.setString(1, "%" + pattern + "%");
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                results.add(new StructureInfo(
                    rs.getString("world"),
                    rs.getString("structure_type"),
                    rs.getInt("x"),
                    rs.getInt("z"),
                    true,
                    rs.getString("region_id")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to query protected structures: " + e.getMessage());
        }
        return results;
    }
    
    /**
     * Get nearest structure to a location.
     */
    public StructureInfo getNearestStructure(String world, int x, int z, int maxRadius) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT *, ((x - ?) * (x - ?) + (z - ?) * (z - ?)) as dist_sq " +
                "FROM structures WHERE world = ? " +
                "ORDER BY dist_sq LIMIT 1"
            );
            stmt.setInt(1, x);
            stmt.setInt(2, x);
            stmt.setInt(3, z);
            stmt.setInt(4, z);
            stmt.setString(5, world);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                double dist = Math.sqrt(rs.getDouble("dist_sq"));
                if (dist <= maxRadius) {
                    return new StructureInfo(
                        rs.getString("world"),
                        rs.getString("structure_type"),
                        rs.getInt("x"),
                        rs.getInt("z"),
                        rs.getInt("has_region") == 1,
                        rs.getString("region_id")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to find nearest structure: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Get all unique structure types in the database.
     */
    public Set<String> getStructureTypes() {
        Set<String> types = new TreeSet<>();
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT DISTINCT structure_type FROM structures ORDER BY structure_type");
            while (rs.next()) {
                types.add(rs.getString("structure_type"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get structure types: " + e.getMessage());
        }
        return types;
    }
    
    /**
     * Get all structures of a specific type in a world.
     * Returns list of [x, z] coordinate pairs.
     */
    public List<int[]> getStructuresOfType(String world, String structureType) {
        List<int[]> results = new ArrayList<>();
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT x, z FROM structures WHERE world = ? AND structure_type = ?"
            );
            stmt.setString(1, world);
            stmt.setString(2, structureType);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                results.add(new int[]{rs.getInt("x"), rs.getInt("z")});
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get structures of type: " + e.getMessage());
        }
        return results;
    }
    
    /**
     * Get count of structures by type.
     */
    public int getCount(String pattern) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM structures WHERE structure_type LIKE ?"
            );
            stmt.setString(1, "%" + pattern + "%");
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to count structures: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Get total count of all structures in database.
     */
    public int getTotalCount() {
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM structures");
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to count total structures: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Get count of protected structures (with regions).
     */
    public int getProtectedCount() {
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM structures WHERE has_region = 1");
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to count protected structures: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Check if a structure at specific coordinates is already in the database.
     */
    public boolean hasStructure(String world, String structureType, int x, int z) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT 1 FROM structures WHERE world = ? AND structure_type = ? AND x = ? AND z = ?"
            );
            stmt.setString(1, world);
            stmt.setString(2, structureType);
            stmt.setInt(3, x);
            stmt.setInt(4, z);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Check if a structure at specific coordinates is protected.
     */
    public boolean isStructureProtected(String world, String structureType, int x, int z) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT has_region FROM structures WHERE world = ? AND structure_type = ? AND x = ? AND z = ?"
            );
            stmt.setString(1, world);
            stmt.setString(2, structureType);
            stmt.setInt(3, x);
            stmt.setInt(4, z);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("has_region") == 1;
            }
        } catch (SQLException e) {
            // Ignore
        }
        return false;
    }
    
    /**
     * Clear all structures from database.
     */
    public void reset() {
        try {
            Statement stmt = connection.createStatement();
            stmt.executeUpdate("DELETE FROM structures");
            plugin.getLogger().info("Database reset");
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to reset database: " + e.getMessage());
        }
    }
    
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // Ignore
        }
    }
    
    /**
     * Structure information holder.
     */
    public static class StructureInfo {
        public final String world;
        public final String type;
        public final int x;
        public final int z;
        public final boolean hasRegion;
        public final String regionId;
        
        public StructureInfo(String world, String type, int x, int z, boolean hasRegion, String regionId) {
            this.world = world;
            this.type = type;
            this.x = x;
            this.z = z;
            this.hasRegion = hasRegion;
            this.regionId = regionId;
        }
        
        /**
         * Generate a consistent region ID for this structure.
         */
        public String generateRegionId() {
            String safeName = type.replace(":", "_").replace("/", "_");
            return "sg_" + safeName + "_" + x + "_" + z;
        }
    }
}
