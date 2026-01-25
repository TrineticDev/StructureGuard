package com.structureguard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Scans for structures by querying chunk/region data directly.
 * Instead of searching for specific structures, we ask "what structures are here?"
 * This is much more efficient - one scan finds ALL structure types.
 */
public class StructureFinder {
    
    private final StructureGuardPlugin plugin;
    
    // Active scan state - volatile for thread visibility in async scans
    private volatile boolean scanInProgress = false;
    
    // Cached iterator methods for LongOpenHashSet (big performance boost)
    private Method cachedIteratorMethod;
    private Method cachedHasNextMethod;
    private Method cachedNextLongMethod;
    private Class<?> cachedLongIteratorClass;
    
    // Cached reflection objects for performance - initialized once, reused for all chunks
    private boolean reflectionCacheInitialized = false;
    private Method getHandleMethod;
    private Method structureManagerMethod;
    private Method getAllStructuresAtMethod;
    private Method registryAccessMethod;
    private Method registryOrThrowMethod;
    private Method getKeyMethod;
    private Constructor<?> blockPosConstructor;
    private Class<?> blockPosClass;
    private Object structureRegistryKey;
    
    // Fabric-specific: alternative path via StructureAccessor or Chunk
    private boolean useFabricPath = false;
    private Method structureAccessorMethod;        // ServerWorld.getStructureAccessor() -> method_27056
    private Method getChunkMethod;                 // For getting chunks
    private Method chunkGetStructureStartsMethod;  // Chunk.getStructureStarts() -> method_12016
    private Method structureStartGetBoundingBoxMethod;  // StructureStart.getBoundingBox()
    private Method structureStartGetStructureMethod;    // StructureStart.getStructure() -> method_16656
    private Class<?> chunkPosClass;
    private Constructor<?> chunkPosConstructor;
    
    // Per-world cache (serverLevel -> structureManager -> registry)
    private Object cachedServerLevel;
    private Object cachedStructureManager;
    private Object cachedStructureAccessor;   // Fabric path
    private Object cachedStructureRegistry;
    private World cachedWorld;
    
    public StructureFinder(StructureGuardPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Initialize reflection cache for fast chunk scanning.
     * Supports both Mojang mappings (Paper/NeoForge) and intermediary mappings (Fabric).
     * 
     * There are two paths:
     * 1. Mojang path: StructureManager.getAllStructuresAt(BlockPos)
     * 2. Fabric path: Chunk.getStructureStarts() via StructureAccessor
     */
    private boolean initReflectionCache(World world) {
        if (reflectionCacheInitialized && cachedWorld == world) {
            return true;
        }
        
        try {
            // Get NMS ServerLevel
            Object craftWorld = world;
            getHandleMethod = craftWorld.getClass().getMethod("getHandle");
            cachedServerLevel = getHandleMethod.invoke(craftWorld);
            
            // Debug: log the server level class name to help identify mapping type
            String serverClassName = cachedServerLevel.getClass().getName();
            plugin.getConfigManager().debug("ServerLevel class: " + serverClassName);
            
            // Cache BlockPos class and constructor - try multiple class names
            // Paper uses Mojang mappings: BlockPos
            // Spigot uses Spigot mappings: BlockPosition
            // Fabric uses intermediary: class_2338
            blockPosClass = findClass(
                "net.minecraft.core.BlockPos",           // Paper/Mojang
                "net.minecraft.core.BlockPosition",      // Spigot
                "net.minecraft.class_2338",              // Fabric intermediary
                "net.minecraft.util.math.BlockPos"       // Yarn
            );
            if (blockPosClass == null) {
                throw new ClassNotFoundException("Could not find BlockPos class");
            }
            plugin.getConfigManager().debug("Found BlockPos class: " + blockPosClass.getName());
            blockPosConstructor = blockPosClass.getConstructor(int.class, int.class, int.class);
            
            // Try Mojang path FIRST (Paper/NeoForge/Spigot - the original working path)
            boolean mojangPathWorks = false;
            plugin.getConfigManager().debug("Trying Mojang path first (Paper/NeoForge/Spigot)...");
            
            try {
                // Spigot uses different method names than Paper - try both
                structureManagerMethod = findMethodByNames(cachedServerLevel.getClass(), 
                    "structureManager",      // Paper (Mojang mapped)
                    "getStructureManager",   // Alternative
                    "method_14178",          // Fabric intermediary
                    "m_7004_",               // Forge SRG
                    "K", "L", "M", "N",      // Common Spigot obfuscated names
                    "a", "b", "c", "d");     // More Spigot names
                
                // If not found by name, search by return type
                if (structureManagerMethod == null) {
                    structureManagerMethod = findMethodByReturnType(cachedServerLevel.getClass(), 
                        "StructureManager", "class_5962", "StructureTemplateManager");
                }
                
                // Last resort: find any no-arg method returning something structure-related
                if (structureManagerMethod == null) {
                    for (Method m : cachedServerLevel.getClass().getMethods()) {
                        if (m.getParameterCount() == 0) {
                            String retName = m.getReturnType().getSimpleName();
                            if (retName.contains("Structure") && retName.contains("Manager")) {
                                structureManagerMethod = m;
                                plugin.getConfigManager().debug("Found structureManager via signature search: " + m.getName());
                                break;
                            }
                        }
                    }
                }
                
                if (structureManagerMethod != null) {
                    cachedStructureManager = structureManagerMethod.invoke(cachedServerLevel);
                    plugin.getConfigManager().debug("Got StructureManager: " + cachedStructureManager.getClass().getName());
                    
                    // Try to find getAllStructuresAt - check multiple name variants
                    getAllStructuresAtMethod = findMethod(cachedStructureManager.getClass(),
                        new String[]{"getAllStructuresAt", "method_38853", "m_220437_", 
                                     "a", "b", "c", "d", "e", "f", "g"},  // Spigot obfuscated names
                        blockPosClass);
                    
                    // If not found, search by signature: takes BlockPos, returns Map
                    if (getAllStructuresAtMethod == null) {
                        getAllStructuresAtMethod = findMethodByReturnTypeAndParam(cachedStructureManager.getClass(), 
                            "Map", blockPosClass);
                    }
                    
                    // Last resort: find any method taking our blockPosClass and returning a Map
                    if (getAllStructuresAtMethod == null) {
                        for (Method m : cachedStructureManager.getClass().getMethods()) {
                            if (m.getParameterCount() == 1 && 
                                Map.class.isAssignableFrom(m.getReturnType())) {
                                Class<?> paramType = m.getParameterTypes()[0];
                                // Check if param is BlockPos-like (has int coords)
                                if (paramType.equals(blockPosClass) || 
                                    paramType.getSimpleName().contains("BlockPos") ||
                                    paramType.getSimpleName().contains("BlockPosition")) {
                                    getAllStructuresAtMethod = m;
                                    plugin.getConfigManager().debug("Found getAllStructuresAt via signature: " + m.getName());
                                    break;
                                }
                            }
                        }
                    }
                    
                    if (getAllStructuresAtMethod != null) {
                        // Test that it actually works by calling it
                        try {
                            Object testPos = blockPosConstructor.newInstance(0, 64, 0);
                            Object testResult = getAllStructuresAtMethod.invoke(cachedStructureManager, testPos);
                            if (testResult != null && testResult instanceof Map) {
                                mojangPathWorks = true;
                                useFabricPath = false;
                                plugin.getConfigManager().debug("Using Mojang path: StructureManager.getAllStructuresAt() - verified working");
                            } else {
                                plugin.getConfigManager().debug("Mojang path: getAllStructuresAt returned null or non-Map");
                            }
                        } catch (Exception e) {
                            plugin.getConfigManager().debug("Mojang path test invocation failed: " + e.getMessage());
                        }
                    } else {
                        plugin.getConfigManager().debug("Could not find getAllStructuresAt method on StructureManager");
                    }
                }
            } catch (Exception e) {
                plugin.getConfigManager().debug("Mojang path failed: " + e.getMessage());
            }
            
            // If Mojang path didn't work, try Spigot's StructureFeatureManager path
            if (!mojangPathWorks) {
                plugin.getConfigManager().debug("Trying Spigot StructureFeatureManager path...");
                try {
                    // On Spigot, look for methods that return something with "Structure" in the name
                    for (Method m : cachedServerLevel.getClass().getMethods()) {
                        if (m.getParameterCount() == 0) {
                            String retName = m.getReturnType().getName();
                            // Check for any structure-related manager
                            if ((retName.contains("Structure") || retName.contains("structure")) &&
                                !retName.contains("Template")) {  // Skip StructureTemplateManager
                                try {
                                    Object potentialManager = m.invoke(cachedServerLevel);
                                    if (potentialManager != null) {
                                        // Look for a method that takes BlockPos and returns Map
                                        for (Method sm : potentialManager.getClass().getMethods()) {
                                            if (sm.getParameterCount() == 1 && 
                                                Map.class.isAssignableFrom(sm.getReturnType())) {
                                                Class<?> paramType = sm.getParameterTypes()[0];
                                                if (paramType.equals(blockPosClass) ||
                                                    paramType.getSimpleName().contains("Pos")) {
                                                    // Test it
                                                    Object testPos = blockPosConstructor.newInstance(0, 64, 0);
                                                    Object testResult = sm.invoke(potentialManager, testPos);
                                                    if (testResult instanceof Map) {
                                                        structureManagerMethod = m;
                                                        cachedStructureManager = potentialManager;
                                                        getAllStructuresAtMethod = sm;
                                                        mojangPathWorks = true;
                                                        useFabricPath = false;
                                                        plugin.getConfigManager().debug("Found Spigot path: " + 
                                                            m.getName() + "() -> " + sm.getName() + "()");
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        if (mojangPathWorks) break;
                                    }
                                } catch (Exception e2) {
                                    // Continue searching
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getConfigManager().debug("Spigot StructureFeatureManager path failed: " + e.getMessage());
                }
            }
            
            // If Mojang path didn't work, try Chunk-based path (Fabric fallback)
            boolean chunkPathWorks = false;
            if (!mojangPathWorks) {
                plugin.getConfigManager().debug("Mojang path not available, trying Chunk-based path (Fabric)...");
                
                // Get method to retrieve chunks - try multiple variants
                getChunkMethod = null;
                String[] chunkMethodNames = {"getChunk", "getChunkAt", "method_8497", "a"};
                for (String methodName : chunkMethodNames) {
                    try {
                        // Try getChunk(int, int) first
                        Method m = cachedServerLevel.getClass().getMethod(methodName, int.class, int.class);
                        // Verify it returns a Chunk-like object
                        String retName = m.getReturnType().getName();
                        if (retName.contains("Chunk") || retName.contains("class_2791") || 
                            retName.contains("LevelChunk") || retName.contains("IChunkAccess")) {
                            getChunkMethod = m;
                            plugin.getConfigManager().debug("Found getChunk via exact match: " + methodName);
                            break;
                        }
                    } catch (NoSuchMethodException e) {
                        // Try next
                    }
                }
                
                // If still not found, search all methods
                if (getChunkMethod == null) {
                    for (Method m : cachedServerLevel.getClass().getMethods()) {
                        if (m.getParameterCount() == 2) {
                            Class<?>[] params = m.getParameterTypes();
                            if (params[0] == int.class && params[1] == int.class) {
                                String retName = m.getReturnType().getName();
                                if (retName.contains("Chunk") || retName.contains("class_2791") ||
                                    retName.contains("LevelChunk")) {
                                    getChunkMethod = m;
                                    plugin.getConfigManager().debug("Found getChunk via search: " + m.getName() + 
                                        " -> " + retName);
                                    break;
                                }
                            }
                        }
                    }
                }
                
                if (getChunkMethod != null) {
                    // Get a test chunk to find structure methods
                    try {
                        Object testChunk = getChunkMethod.invoke(cachedServerLevel, 0, 0);
                        if (testChunk != null) {
                            plugin.getConfigManager().debug("Got test chunk: " + testChunk.getClass().getName());
                            
                            // Find getAllStarts or getStructureStarts
                            chunkGetStructureStartsMethod = findMethodByNames(testChunk.getClass(),
                                "getAllStarts", "getStructureStarts", "method_12016", "getAllReferences", 
                                "h", "g", "f", "e", "d", "c", "b", "a");  // Try obfuscated names
                            
                            if (chunkGetStructureStartsMethod == null) {
                                // Search for any 0-param method returning a Map
                                for (Method m : testChunk.getClass().getMethods()) {
                                    if (m.getParameterCount() == 0 && 
                                        Map.class.isAssignableFrom(m.getReturnType())) {
                                        chunkGetStructureStartsMethod = m;
                                        plugin.getConfigManager().debug("Found potential structure method: " + 
                                            m.getName() + " -> " + m.getReturnType().getSimpleName());
                                        break;
                                    }
                                }
                            }
                            
                            // If still not found, list all methods to diagnose
                            if (chunkGetStructureStartsMethod == null) {
                                plugin.getConfigManager().debug("Could not find structure starts method on Chunk. Available 0-param methods:");
                                for (Method m : testChunk.getClass().getMethods()) {
                                    if (m.getParameterCount() == 0 && !m.getReturnType().equals(void.class)) {
                                        String retName = m.getReturnType().getSimpleName();
                                        if (retName.contains("Map") || retName.contains("Structure") || 
                                            retName.contains("Start") || m.getName().length() <= 2) {
                                            plugin.getConfigManager().debug("  " + m.getName() + "() -> " + retName);
                                        }
                                    }
                                }
                            }
                            
                            if (chunkGetStructureStartsMethod != null) {
                                plugin.getConfigManager().debug("Found Chunk structure method: " + 
                                    chunkGetStructureStartsMethod.getName());
                                
                                // Test it
                                Object structureStarts = chunkGetStructureStartsMethod.invoke(testChunk);
                                if (structureStarts instanceof Map) {
                                    Map<?, ?> startsMap = (Map<?, ?>) structureStarts;
                                    plugin.getConfigManager().debug("Structure starts map has " + startsMap.size() + " entries");
                                    
                                    // Even if empty, the path works - we just can't cache StructureStart methods yet
                                    chunkPathWorks = true;
                                    useFabricPath = true;
                                    
                                    if (!startsMap.isEmpty()) {
                                        Object sampleStart = startsMap.values().iterator().next();
                                        if (sampleStart != null) {
                                            structureStartGetStructureMethod = findMethodByNames(sampleStart.getClass(),
                                                "getStructure", "method_16656", "getFeature", "e", "f", "g");
                                            structureStartGetBoundingBoxMethod = findMethodByNames(sampleStart.getClass(),
                                                "getBoundingBox", "method_14969", "f", "g", "h");
                                            plugin.getConfigManager().debug("Found StructureStart methods");
                                        }
                                    }
                                    
                                    plugin.getConfigManager().debug("Using Chunk-based path: Chunk." + 
                                        chunkGetStructureStartsMethod.getName() + "()");
                                } else {
                                    plugin.getConfigManager().debug("Chunk structure method returned: " + 
                                        (structureStarts == null ? "null" : structureStarts.getClass().getName()));
                                }
                            }
                        }
                    } catch (Exception e) {
                        plugin.getConfigManager().debug("Chunk-based path test failed: " + e.getMessage());
                    }
                }
            }
            
            // Make sure at least one path works
            if (!mojangPathWorks && !chunkPathWorks) {
                plugin.getLogger().warning("Neither Mojang nor Chunk-based path could be initialized.");
                plugin.getLogger().warning("This server version may not be supported. Enable debug mode for details.");
                throw new NoSuchMethodException("Neither Mojang nor Chunk-based path could be initialized");
            }
            
            // Now set up registry access for structure name lookups
            // This is needed for both paths
            // Fabric: DynamicRegistryManager -> class_5455
            // Mojang: RegistryAccess
            // Spigot mappings: IRegistryCustom
            
            // First, let's find the correct registryAccess method more carefully
            registryAccessMethod = null;
            Object registryAccess = null;
            
            // Try exact method names first - including Fabric API and Spigot-mapped names
            String[] registryMethodNames = {
                "registryAccess",                    // Mojang
                "getRegistryManager",                // Yarn
                "fabric_getDynamicRegistryManager",  // Fabric API hook
                "H_",                                // Spigot obfuscated (from dump)
                "method_46437",                      // Intermediary
                "method_8433"                        // Intermediary
            };
            
            for (String methodName : registryMethodNames) {
                try {
                    Method m = cachedServerLevel.getClass().getMethod(methodName);
                    Object result = m.invoke(cachedServerLevel);
                    if (result != null) {
                        // Verify this is actually a registry manager
                        String resultClassName = result.getClass().getName();
                        if (resultClassName.contains("Registry") || resultClassName.contains("class_5455") ||
                            resultClassName.contains("DynamicRegistry") || resultClassName.contains("IRegistryCustom")) {
                            registryAccessMethod = m;
                            registryAccess = result;
                            plugin.getConfigManager().debug("Found registryAccess via method: " + methodName + 
                                " -> " + resultClassName);
                            break;
                        }
                    }
                } catch (NoSuchMethodException e) {
                    // Try next
                } catch (Exception e) {
                    plugin.getConfigManager().debug("Error invoking " + methodName + ": " + e.getMessage());
                }
            }
            
            // If not found, search all methods by return type more carefully
            if (registryAccess == null) {
                for (Method m : cachedServerLevel.getClass().getMethods()) {
                    if (m.getParameterCount() == 0) {
                        String returnTypeName = m.getReturnType().getName();
                        // Must be specifically DynamicRegistryManager, RegistryAccess, or IRegistryCustom
                        if (returnTypeName.contains("DynamicRegistryManager") || 
                            returnTypeName.contains("class_5455") ||
                            returnTypeName.contains("RegistryAccess") ||
                            returnTypeName.contains("IRegistryCustom") ||
                            returnTypeName.contains("class_7225")) {
                            try {
                                Object result = m.invoke(cachedServerLevel);
                                if (result != null) {
                                    registryAccessMethod = m;
                                    registryAccess = result;
                                    plugin.getConfigManager().debug("Found registryAccess via return type scan: " + 
                                        m.getName() + " -> " + returnTypeName);
                                    break;
                                }
                            } catch (Exception e) {
                                // Skip
                            }
                        }
                    }
                }
            }
            
            if (registryAccess == null) {
                plugin.getLogger().warning("Could not find registryAccess method on this server version.");
                throw new NoSuchMethodException("Could not find registryAccess method");
            }
            
            plugin.getConfigManager().debug("RegistryAccess class: " + registryAccess.getClass().getName());
            
            // For 1.17-1.19.2 LEGACY path: Get structure registry directly from RegistryAccess
            // In these versions, we can iterate registries to find the structure one
            boolean triedLegacyRegistryAccess = false;
            
            // RegistryAccess methods logged only if needed for debugging
            
            // Try ALL 0-param methods that return something iterable or registry-related
            for (Method m : registryAccess.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && cachedStructureRegistry == null) {
                    String returnTypeName = m.getReturnType().getName();
                    
                    // Skip obvious non-registry methods
                    if (m.getReturnType().equals(void.class) || 
                        m.getReturnType().equals(String.class) ||
                        m.getReturnType().equals(int.class) ||
                        m.getReturnType().equals(boolean.class)) continue;
                    
                    try {
                        Object result = m.invoke(registryAccess);
                        if (result != null) {
                            // Check if it's directly a registry containing structures
                            String resultStr = result.toString().toLowerCase();
                            if (resultStr.contains("structure") && 
                                result.getClass().getName().contains("Registry") &&
                                !resultStr.contains("structure_piece") &&
                                !resultStr.contains("structure_set")) {
                                cachedStructureRegistry = result;
                                plugin.getConfigManager().debug("Found structure registry directly via: " + m.getName());
                                break;
                            }
                            
                            // Try to iterate if it's a collection
                            Iterable<?> registries = null;
                            if (result instanceof Iterable) {
                                registries = (Iterable<?>) result;
                            } else if (result.getClass().getName().contains("Stream")) {
                                try {
                                    Method toListMethod = result.getClass().getMethod("toList");
                                    registries = (Iterable<?>) toListMethod.invoke(result);
                                } catch (Exception e) {
                                    // Not a stream with toList
                                }
                            }
                            
                            if (registries != null) {
                                for (Object entry : registries) {
                                    String entryStr = entry.toString().toLowerCase();
                                    if (entryStr.contains("structure_feature") || 
                                        (entryStr.contains("structure") && 
                                         !entryStr.contains("structure_piece") &&
                                         !entryStr.contains("structure_set") && 
                                         !entryStr.contains("structure_processor"))) {
                                        
                                        // Extract registry from entry
                                        if (entry.getClass().getName().contains("Registry")) {
                                            cachedStructureRegistry = entry;
                                        } else {
                                            // Try methods that return Registry
                                            for (Method em : entry.getClass().getMethods()) {
                                                if (em.getParameterCount() == 0 && 
                                                    em.getReturnType().getName().contains("Registry")) {
                                                    try {
                                                        cachedStructureRegistry = em.invoke(entry);
                                                        break;
                                                    } catch (Exception e2) {}
                                                }
                                            }
                                            // Try second() for Pair types
                                            if (cachedStructureRegistry == null) {
                                                for (Method em : entry.getClass().getMethods()) {
                                                    if (em.getName().equals("getSecond") || em.getName().equals("second") ||
                                                        em.getName().equals("getValue") || em.getName().equals("b")) {
                                                        try {
                                                            Object val = em.invoke(entry);
                                                            if (val != null && val.getClass().getName().contains("Registry")) {
                                                                cachedStructureRegistry = val;
                                                                break;
                                                            }
                                                        } catch (Exception e2) {}
                                                    }
                                                }
                                            }
                                        }
                                        if (cachedStructureRegistry != null) {
                                            plugin.getConfigManager().debug("Found structure registry via iteration: " + 
                                                m.getName() + " -> " + entryStr.substring(0, Math.min(60, entryStr.length())));
                                            triedLegacyRegistryAccess = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Skip methods that throw
                    }
                }
            }
            
            // Get Registries class and STRUCTURE field (for 1.19.3+)
            // Skip if we already found the registry via legacy RegistryAccess iteration
            if (cachedStructureRegistry == null) {
                // 1.19.3+: net.minecraft.core.registries.Registries
                // 1.17-1.19.2: net.minecraft.core.Registry (static fields)
                // Fabric: RegistryKeys -> class_7923, field_41236
                Class<?> registriesClass = findClass(
                    "net.minecraft.core.registries.Registries",  // 1.19.3+
                    "net.minecraft.registry.RegistryKeys",       // Fabric 1.19.3+
                    "net.minecraft.class_7923",                  // Fabric intermediary
                    "net.minecraft.class_7157"                   // Older Fabric
                );
                
                // Fallback for 1.17-1.19.2: use Registry class directly
                boolean usingLegacyRegistry = false;
                if (registriesClass == null) {
                    registriesClass = findClass(
                        "net.minecraft.core.Registry",           // 1.17-1.19.2 Spigot/Paper
                        "net.minecraft.core.IRegistry",          // Spigot obfuscated
                        "net.minecraft.class_2378"               // Fabric intermediary
                    );
                    if (registriesClass != null) {
                        usingLegacyRegistry = true;
                        plugin.getConfigManager().debug("Using legacy Registry class (1.17-1.19.2): " + registriesClass.getName());
                    }
                }
                
                if (registriesClass == null) {
                    throw new ClassNotFoundException("Could not find Registries class");
            }
            plugin.getConfigManager().debug("Registries class: " + registriesClass.getName());
            
            // Find STRUCTURE field specifically - need to be careful not to get BIOME or other registries
            structureRegistryKey = null;
            
            // Try known field names first - include more Fabric intermediary names
            // 1.19.3+: STRUCTURE
            // 1.18.2-1.19.2: CONFIGURED_STRUCTURE_FEATURE
            // 1.17-1.18.1: STRUCTURE_FEATURE (ResourceKey) or the registry directly
            String[] structureFieldNames = {
                "STRUCTURE",                    // 1.19.3+ Mojang/Paper
                "CONFIGURED_STRUCTURE_FEATURE", // 1.18.2-1.19.2
                "STRUCTURE_FEATURE",            // 1.17-1.18.1 (ResourceKey)
                "ae", "af", "ag", "ah",         // Spigot obfuscated (varies by version)
                "field_41236",                  // Fabric intermediary
                "field_40229",                  // Older Fabric
                "f_256952_",                    // Forge SRG
                "WORLDGEN_STRUCTURE",           // Alternative name
                "k", "l", "m", "n"              // More obfuscated
            };
            for (String fieldName : structureFieldNames) {
                try {
                    java.lang.reflect.Field f = registriesClass.getField(fieldName);
                    Object value = f.get(null);
                    // Verify this is actually the STRUCTURE registry by checking its string representation
                    String valueStr = value.toString().toLowerCase();
                    // Also try to get the registry path via method
                    String registryPath = getRegistryKeyPath(value);
                    if (registryPath != null) {
                        registryPath = registryPath.toLowerCase();
                    }
                    
                    if (valueStr.contains("structure") || valueStr.contains("worldgen/structure") ||
                        (registryPath != null && registryPath.contains("structure"))) {
                        structureRegistryKey = value;
                        plugin.getConfigManager().debug("Found STRUCTURE field via name: " + fieldName + " = " + value);
                        break;
                    }
                } catch (Exception e) {
                    // Try next
                }
            }
            
            // If not found, search all fields for one that represents structures
            if (structureRegistryKey == null) {
                plugin.getLogger().warning("Could not find STRUCTURE field by name. Searching all fields...");
                
                // First, dump a sample of fields to understand the format
                int count = 0;
                for (java.lang.reflect.Field f : registriesClass.getFields()) {
                    if (count < 5) {
                        try {
                            Object value = f.get(null);
                            String path = getRegistryKeyPath(value);
                            plugin.getConfigManager().debug("Sample field: " + f.getName() + " = " + value + 
                                " (path: " + path + ")");
                            count++;
                        } catch (Exception e) {}
                    }
                }
                
                for (java.lang.reflect.Field f : registriesClass.getFields()) {
                    try {
                        Object value = f.get(null);
                        if (value != null) {
                            String valueStr = value.toString().toLowerCase();
                            String registryPath = getRegistryKeyPath(value);
                            if (registryPath != null) {
                                registryPath = registryPath.toLowerCase();
                            }
                            
                            // Look for EXACTLY "worldgen/structure" - NOT structure_piece, structure_set, etc.
                            // The path should end with "/structure" or "worldgen/structure"
                            boolean isStructure = false;
                            
                            if (registryPath != null) {
                                // Exact match: ends with "worldgen/structure" and nothing after
                                isStructure = registryPath.endsWith("worldgen/structure") ||
                                              registryPath.equals("minecraft:worldgen/structure");
                            }
                            
                            if (!isStructure) {
                                // Check toString - look for "/ minecraft:worldgen/structure]" pattern
                                isStructure = valueStr.contains("/ minecraft:worldgen/structure]") ||
                                              valueStr.endsWith("worldgen/structure]");
                            }
                            
                            if (isStructure) {
                                structureRegistryKey = value;
                                plugin.getConfigManager().debug("Found STRUCTURE field via search: " + f.getName() + " = " + value);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        // Skip
                    }
                }
            }
            
            if (structureRegistryKey == null) {
                // For legacy versions (1.17-1.19.2), try to get the registry directly from Registry class
                if (usingLegacyRegistry) {
                    plugin.getConfigManager().debug("Trying to get structure registry directly from Registry class...");
                    
                    // In 1.17, the registry fields are named like "e", "f", etc. (obfuscated)
                    // We need to find one that contains structure-related data
                    // Look for ANY field that IS a registry and might contain structures
                    
                    // First, list all registry-type fields to understand the format
                    List<String> registryFields = new ArrayList<>();
                    for (java.lang.reflect.Field f : registriesClass.getFields()) {
                        try {
                            Object value = f.get(null);
                            if (value != null) {
                                String typeName = value.getClass().getName();
                                if (typeName.contains("Registry") || typeName.contains("IRegistry")) {
                                    String valueStr = value.toString();
                                    registryFields.add(f.getName() + " -> " + valueStr);
                                    
                                    // Check if this registry is for structures
                                    if (valueStr.toLowerCase().contains("structure")) {
                                        cachedStructureRegistry = value;
                                        plugin.getConfigManager().debug("Found legacy structure registry: " + 
                                            f.getName() + " = " + valueStr);
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Skip
                        }
                    }
                    
                    if (cachedStructureRegistry == null && !registryFields.isEmpty()) {
                        plugin.getConfigManager().debug("Registry fields found but none matched 'structure':");
                        for (String rf : registryFields) {
                            plugin.getConfigManager().debug("  " + rf);
                        }
                    }
                }
                
                if (structureRegistryKey == null && cachedStructureRegistry == null) {
                    // For chunk-based path (1.17-1.18), we can work without the registry
                    // We just won't be able to list ALL structure types, only detected ones
                    if (useFabricPath) {
                        plugin.getLogger().warning("Could not find structure registry. /sg listall may be limited on this version.");
                        plugin.getLogger().info("Structure detection will still work via chunk-based path.");
                        // Don't throw - continue without registry
                    } else {
                        plugin.getLogger().warning("Could not find STRUCTURE registry key on this server version.");
                        plugin.getLogger().warning("Enable debug mode for detailed diagnostics.");
                        throw new NoSuchFieldException("Could not find STRUCTURE field");
                    }
                }
            }
            } // End of outer: if (cachedStructureRegistry == null) - Registries class lookup
            
            // Skip all registry-related setup if we're using chunk-based fallback without registry
            if (cachedStructureRegistry == null && useFabricPath) {
                plugin.getConfigManager().debug("Skipping registry setup - using chunk-based detection only");
                // Continue to finalize initialization without registry
            } else if (cachedStructureRegistry == null) {
                // Get ResourceKey class (needed for modern registry access)
                Class<?> resourceKeyClass = findClass(
                    "net.minecraft.resources.ResourceKey",
                    "net.minecraft.class_5321"
                );
                if (resourceKeyClass == null) {
                    throw new ClassNotFoundException("Could not find ResourceKey class");
                }
            
                // Get registry from registryAccess
                // Mojang 1.20-: registryOrThrow(ResourceKey) -> Registry
                // Mojang 1.21+: lookupOrThrow(ResourceKey) -> Registry
                // Fabric: getOptional(RegistryKey) -> Optional<Registry> (method_33310)
                // Spigot: f(ResourceKey) -> IRegistry (obfuscated)
                registryOrThrowMethod = findMethod(registryAccess.getClass(),
                    new String[]{"registryOrThrow", "lookupOrThrow", "method_30530", "m_175515_",
                                 "f", "e", "d", "c", "b", "a", "g", "h"},  // Spigot obfuscated names
                    resourceKeyClass);
            
            // If not found by name, search by signature: takes ResourceKey, returns Registry-like
            if (registryOrThrowMethod == null) {
                for (Method m : registryAccess.getClass().getMethods()) {
                    if (m.getParameterCount() == 1) {
                        Class<?> paramType = m.getParameterTypes()[0];
                        String returnTypeName = m.getReturnType().getSimpleName();
                        // Check if param is ResourceKey-like and return is Registry-like
                        if ((paramType.equals(resourceKeyClass) || 
                             paramType.getSimpleName().contains("ResourceKey") ||
                             paramType.getSimpleName().contains("RegistryKey")) &&
                            (returnTypeName.contains("Registry") || 
                             returnTypeName.equals("IRegistry") ||
                             returnTypeName.contains("class_"))) {
                            // Test it with our structure registry key
                            try {
                                Object testResult = m.invoke(registryAccess, structureRegistryKey);
                                if (testResult != null) {
                                    registryOrThrowMethod = m;
                                    plugin.getConfigManager().debug("Found registry method via signature: " + 
                                        m.getName() + " -> " + returnTypeName);
                                    break;
                                }
                            } catch (Exception e) {
                                // This method throws, continue searching
                            }
                        }
                    }
                }
            }
            
            if (registryOrThrowMethod != null) {
                cachedStructureRegistry = registryOrThrowMethod.invoke(registryAccess, structureRegistryKey);
                plugin.getConfigManager().debug("Got registry via: " + registryOrThrowMethod.getName());
            } else {
                // Try Fabric's getOptional method
                Method getOptionalMethod = findMethod(registryAccess.getClass(),
                    new String[]{"getOptional", "method_33310", "get"},
                    resourceKeyClass);
                
                if (getOptionalMethod != null) {
                    Object optionalRegistry = getOptionalMethod.invoke(registryAccess, structureRegistryKey);
                    // Unwrap Optional
                    if (optionalRegistry != null) {
                        Method isPresentMethod = optionalRegistry.getClass().getMethod("isPresent");
                        Method getMethod = optionalRegistry.getClass().getMethod("get");
                        if ((boolean) isPresentMethod.invoke(optionalRegistry)) {
                            cachedStructureRegistry = getMethod.invoke(optionalRegistry);
                            plugin.getConfigManager().debug("Got structure registry via getOptional");
                        }
                    }
                }
                
                if (cachedStructureRegistry == null) {
                    // For chunk-based path (1.17-1.18), we can work without the registry
                    // We just won't be able to list ALL structure types, only detected ones
                    if (useFabricPath) {
                        plugin.getLogger().warning("Could not find structure registry. /sg listall may not work on this version.");
                        plugin.getLogger().info("Structure detection will still work - structures will be named from chunk data.");
                    } else {
                        plugin.getLogger().warning("Could not find registry access method on this server version.");
                        throw new NoSuchMethodException("Could not find registry access method");
                    }
                }
            }
            } // End of: else if (cachedStructureRegistry == null) - registry lookup block
            
            // Get getKey method from registry (only if we have a registry)
            if (cachedStructureRegistry != null) {
                // Mojang: getKey(Object) -> ResourceLocation
                // Spigot: usually obfuscated single letters
                getKeyMethod = findMethod(cachedStructureRegistry.getClass(),
                    new String[]{"getKey", "method_10221", "m_7981_", 
                                 "b", "c", "d", "e", "f", "g", "a"},  // Spigot obfuscated
                    Object.class);
            
                // If not found, search by signature: takes Object, returns ResourceLocation-like
                if (getKeyMethod == null) {
                    for (Method m : cachedStructureRegistry.getClass().getMethods()) {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == Object.class) {
                            String returnTypeName = m.getReturnType().getSimpleName();
                            // ResourceLocation on Spigot is MinecraftKey
                            if (returnTypeName.contains("ResourceLocation") || 
                                returnTypeName.contains("MinecraftKey") ||
                                returnTypeName.contains("Identifier") ||
                                returnTypeName.contains("class_")) {
                                getKeyMethod = m;
                                plugin.getConfigManager().debug("Found getKey via signature: " + m.getName() + 
                                    " -> " + returnTypeName);
                                break;
                            }
                        }
                    }
                }
            
                if (getKeyMethod == null) {
                    plugin.getLogger().warning("Could not find getKey method on structure registry.");
                    throw new NoSuchMethodException("Could not find getKey method");
                }
                plugin.getConfigManager().debug("Found getKey method: " + getKeyMethod.getName());
            } // End of: if (cachedStructureRegistry != null) - getKey setup
            
            // Pre-cache iterator methods for LongOpenHashSet
            try {
                Class<?> longOpenHashSetClass = Class.forName("it.unimi.dsi.fastutil.longs.LongOpenHashSet");
                cachedIteratorMethod = longOpenHashSetClass.getMethod("iterator");
                cachedLongIteratorClass = Class.forName("it.unimi.dsi.fastutil.longs.LongIterator");
                cachedHasNextMethod = cachedLongIteratorClass.getMethod("hasNext");
                cachedNextLongMethod = cachedLongIteratorClass.getMethod("nextLong");
                plugin.getConfigManager().debug("Iterator methods cached successfully");
            } catch (Exception e) {
                plugin.getConfigManager().debug("Could not cache iterator methods: " + e.getMessage());
            }
            
            cachedWorld = world;
            reflectionCacheInitialized = true;
            
            plugin.getConfigManager().debug("Reflection cache initialized successfully");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize reflection cache: " + e.getMessage());
            reflectionCacheInitialized = false;
            return false;
        }
    }
    
    /**
     * Extract the registry path from a ResourceKey object.
     * Handles both Mojang and Fabric formats.
     */
    private String getRegistryKeyPath(Object registryKey) {
        if (registryKey == null) return null;
        
        try {
            // Try to get location() method which returns the ResourceLocation
            Method locationMethod = findMethodByNames(registryKey.getClass(), 
                "location", "getValue", "method_29177", "method_10220");
            if (locationMethod != null) {
                Object location = locationMethod.invoke(registryKey);
                if (location != null) {
                    return location.toString();
                }
            }
            
            // Try registry() method to get the registry path
            Method registryMethod = findMethodByNames(registryKey.getClass(),
                "registry", "method_41185");
            if (registryMethod != null) {
                Object registry = registryMethod.invoke(registryKey);
                if (registry != null) {
                    return registry.toString();
                }
            }
        } catch (Exception e) {
            // Fall through
        }
        
        return null;
    }
    
    /**
     * Find a method by trying multiple possible names (for different mappings).
     * Also searches by scanning all methods if exact name match fails.
     */
    private Method findMethod(Class<?> clazz, String... names) {
        // First try exact name matches
        for (String name : names) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                // Try next name
            }
        }
        // Also search declared methods (for private/protected methods)
        for (String name : names) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
        }
        // Last resort: search by partial name match for obfuscated methods
        for (Method m : clazz.getMethods()) {
            String methodName = m.getName();
            // Look for methods that might be the obfuscated version
            for (String name : names) {
                if (methodName.equals(name) || 
                    (methodName.startsWith("method_") && m.getParameterCount() == 0 && 
                     m.getReturnType().getName().contains("RegistryAccess"))) {
                    return m;
                }
            }
        }
        return null;
    }
    
    /**
     * Find a method with parameters by trying multiple possible names.
     */
    private Method findMethod(Class<?> clazz, String[] names, Class<?>... paramTypes) {
        for (String name : names) {
            try {
                return clazz.getMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                // Try next name
            }
        }
        // Search all methods for matching parameter types
        for (Method m : clazz.getMethods()) {
            for (String name : names) {
                if (m.getName().equals(name)) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == paramTypes.length) {
                        boolean match = true;
                        for (int i = 0; i < params.length; i++) {
                            if (!params[i].isAssignableFrom(paramTypes[i]) && 
                                !paramTypes[i].isAssignableFrom(params[i])) {
                                match = false;
                                break;
                            }
                        }
                        if (match) return m;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Find a method by exact names only (no pattern matching).
     * Returns 0-arg methods only.
     */
    private Method findMethodByNames(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                // Try next
            }
        }
        // Search all methods - but only return 0-arg methods
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 0) {
                for (String name : names) {
                    if (m.getName().equals(name)) {
                        return m;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Find a method by exact names that takes one parameter of specified type.
     */
    private Method findMethodByNamesWithParam(Class<?> clazz, Class<?> paramType, String... names) {
        for (String name : names) {
            try {
                return clazz.getMethod(name, paramType);
            } catch (NoSuchMethodException e) {
                // Try next
            }
        }
        // Search all methods with matching param type
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 1) {
                Class<?> actualParam = m.getParameterTypes()[0];
                if (actualParam.isAssignableFrom(paramType) || paramType.isAssignableFrom(actualParam) ||
                    actualParam == Object.class) {
                    for (String name : names) {
                        if (m.getName().equals(name)) {
                            return m;
                        }
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Find a method by return type name pattern (for obfuscated code).
     */
    private Method findMethodByReturnType(Class<?> clazz, String... returnTypePatterns) {
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 0) {
                String returnTypeName = m.getReturnType().getName();
                String returnTypeSimple = m.getReturnType().getSimpleName();
                for (String pattern : returnTypePatterns) {
                    if (returnTypeName.contains(pattern) || returnTypeSimple.contains(pattern)) {
                        return m;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Find a method by return type and parameter type.
     */
    private Method findMethodByReturnTypeAndParam(Class<?> clazz, String returnTypePattern, Class<?> paramType) {
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 1) {
                Class<?>[] params = m.getParameterTypes();
                String returnTypeName = m.getReturnType().getName();
                if (returnTypeName.contains(returnTypePattern) && 
                    (params[0].equals(paramType) || params[0].isAssignableFrom(paramType))) {
                    return m;
                }
            }
        }
        return null;
    }
    
    /**
     * Find a field by type pattern.
     */
    private Object findFieldByType(Class<?> clazz, String... typePatterns) {
        for (java.lang.reflect.Field f : clazz.getFields()) {
            String typeName = f.getType().getName();
            String typeSimple = f.getType().getSimpleName();
            for (String typePattern : typePatterns) {
                if (typeName.contains(typePattern) || typeSimple.contains(typePattern)) {
                    try {
                        return f.get(null);
                    } catch (Exception e) {
                        // Continue
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Find a class by trying multiple possible names.
     */
    private Class<?> findClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                // Try next name
            }
        }
        return null;
    }
    
    /**
     * Find a field value by trying multiple possible names.
     */
    private Object findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getField(name).get(null);
            } catch (Exception e) {
                // Try next name
            }
        }
        return null;
    }
    
    /**
     * Check if a scan is currently in progress.
     */
    public boolean isSearchInProgress() {
        return scanInProgress;
    }
    
    /**
     * Cancel any active scan.
     */
    public void cancelSearch() {
        scanInProgress = false;
    }
    
    /**
     * Check if we're using chunk-based detection (legacy servers like 1.17-1.18).
     * This is useful to show appropriate messages to users.
     */
    public boolean isUsingChunkBasedDetection() {
        return useFabricPath && cachedStructureRegistry == null;
    }
    
    /**
     * Get detection path info for diagnostics.
     */
    public String getDetectionPathInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(useFabricPath ? "Chunk-based (Fabric)" : "Mojang (StructureManager)");
        if (getAllStructuresAtMethod != null) {
            sb.append(" | getAllStructuresAt: ").append(getAllStructuresAtMethod.getName());
        }
        if (chunkGetStructureStartsMethod != null) {
            sb.append(" | chunkStructureStarts: ").append(chunkGetStructureStartsMethod.getName());
        }
        if (cachedStructureRegistry != null) {
            sb.append(" | Registry: OK");
        } else {
            sb.append(" | Registry: null");
        }
        return sb.toString();
    }
    
    /**
     * Probe a specific chunk for structures with verbose output.
     * Used for debugging structure detection issues.
     */
    public List<String> probeChunkVerbose(World world, int chunkX, int chunkZ) {
        List<String> output = new ArrayList<>();
        
        output.add("§6Probing chunk " + chunkX + "," + chunkZ + " in " + world.getName());
        output.add("§7Detection path: " + getDetectionPathInfo());
        
        try {
            // Initialize if needed
            if (!reflectionCacheInitialized) {
                output.add("§eInitializing reflection cache...");
                initReflectionCache(world);
            }
            
            // Try Mojang path
            if (getAllStructuresAtMethod != null && cachedStructureManager != null) {
                output.add("§7Trying Mojang path...");
                try {
                    int blockX = chunkX * 16 + 8;
                    int blockZ = chunkZ * 16 + 8;
                    Object blockPos = blockPosConstructor.newInstance(blockX, 64, blockZ);
                    
                    Object structureMapObj = getAllStructuresAtMethod.invoke(cachedStructureManager, blockPos);
                    
                    if (structureMapObj == null) {
                        output.add("§c  getAllStructuresAt returned null");
                    } else if (structureMapObj instanceof Map) {
                        Map<?, ?> structureMap = (Map<?, ?>) structureMapObj;
                        output.add("§a  Found " + structureMap.size() + " structure entries");
                        
                        for (Map.Entry<?, ?> entry : structureMap.entrySet()) {
                            Object structure = entry.getKey();
                            Object chunkRefs = entry.getValue();
                            String name = getStructureNameCached(structure);
                            String refsInfo = chunkRefs != null ? chunkRefs.getClass().getSimpleName() : "null";
                            output.add("§7    " + name + " §8(" + refsInfo + ")");
                        }
                    } else {
                        output.add("§c  Unexpected return type: " + structureMapObj.getClass().getName());
                    }
                } catch (Exception e) {
                    output.add("§c  Mojang path error: " + e.getMessage());
                }
            } else {
                output.add("§7Mojang path not available (getAllStructuresAt=" + 
                    (getAllStructuresAtMethod != null) + ", structureManager=" + (cachedStructureManager != null) + ")");
            }
            
            // Try Chunk path
            if (getChunkMethod != null && chunkGetStructureStartsMethod != null) {
                output.add("§7Trying Chunk path...");
                try {
                    Object chunk = getChunkMethod.invoke(cachedServerLevel, chunkX, chunkZ);
                    if (chunk == null) {
                        output.add("§c  getChunk returned null");
                    } else {
                        Object structureStarts = chunkGetStructureStartsMethod.invoke(chunk);
                        if (structureStarts == null) {
                            output.add("§c  getStructureStarts returned null");
                        } else if (structureStarts instanceof Map) {
                            Map<?, ?> startsMap = (Map<?, ?>) structureStarts;
                            output.add("§a  Found " + startsMap.size() + " structure starts");
                            
                            for (Map.Entry<?, ?> entry : startsMap.entrySet()) {
                                Object structure = entry.getKey();
                                Object start = entry.getValue();
                                String name = getStructureNameCached(structure);
                                String startInfo = start != null ? start.getClass().getSimpleName() : "null";
                                output.add("§7    " + name + " §8(" + startInfo + ")");
                            }
                        } else {
                            output.add("§c  Unexpected type: " + structureStarts.getClass().getName());
                        }
                    }
                } catch (Exception e) {
                    output.add("§c  Chunk path error: " + e.getMessage());
                }
            } else {
                output.add("§7Chunk path not available (getChunk=" + 
                    (getChunkMethod != null) + ", chunkStructureStarts=" + (chunkGetStructureStartsMethod != null) + ")");
            }
            
        } catch (Exception e) {
            output.add("§cProbe error: " + e.getMessage());
        }
        
        return output;
    }
    
    /**
     * Get all registered structure types from the server.
     * Works with vanilla and modded structures (Pixelmon, Terralith, etc.)
     */
    public List<String> getAllStructureTypes() {
        List<String> structures = new ArrayList<>();
        
        try {
            World world = Bukkit.getWorlds().get(0);
            
            // Use cached registry if available
            if (reflectionCacheInitialized && cachedStructureRegistry != null) {
                structures = extractStructureNamesFromRegistry(cachedStructureRegistry);
            } else {
                // Initialize cache or use fresh reflection
                if (initReflectionCache(world) && cachedStructureRegistry != null) {
                    structures = extractStructureNamesFromRegistry(cachedStructureRegistry);
                }
            }
            
            Collections.sort(structures);
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get structure types: " + e.getMessage());
        }
        
        return structures;
    }
    
    /**
     * Extract structure names from a registry, handling both Mojang and Fabric ResourceLocation formats
     */
    private List<String> extractStructureNamesFromRegistry(Object registry) {
        List<String> names = new ArrayList<>();
        
        try {
            Method keySetMethod = findMethodByNames(registry.getClass(), 
                "keySet", "method_10235", "m_6566_");
            if (keySetMethod == null) {
                keySetMethod = findMethodByReturnType(registry.getClass(), "Set");
            }
            
            if (keySetMethod != null) {
                Object keySet = keySetMethod.invoke(registry);
                for (Object key : (Iterable<?>) keySet) {
                    String name = extractResourceLocationName(key);
                    if (name != null && !name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getConfigManager().debug("Error extracting structure names: " + e.getMessage());
        }
        
        return names;
    }
    
    /**
     * Extract the full "namespace:path" name from a ResourceLocation/Identifier object.
     * Handles different formats from Mojang, Fabric, and other mappings.
     */
    private String extractResourceLocationName(Object resourceLocation) {
        if (resourceLocation == null) return null;
        
        try {
            // Try direct methods first - getNamespace() and getPath()
            Method getNamespace = null;
            Method getPath = null;
            
            // Try Mojang names
            try {
                getNamespace = resourceLocation.getClass().getMethod("getNamespace");
                getPath = resourceLocation.getClass().getMethod("getPath");
            } catch (NoSuchMethodException e) {
                // Try Fabric intermediary names
                try {
                    getNamespace = resourceLocation.getClass().getMethod("method_12836"); // Fabric getNamespace
                    getPath = resourceLocation.getClass().getMethod("method_12832"); // Fabric getPath
                } catch (NoSuchMethodException e2) {
                    // Try Spigot's MinecraftKey method names
                    // On Spigot, getNamespace() and getPath() should exist on MinecraftKey
                    // But they might be obfuscated - we need to find the right ones
                    
                    // First, look for explicit namespace/path-like names
                    for (Method m : resourceLocation.getClass().getMethods()) {
                        if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                            String methodName = m.getName().toLowerCase();
                            if (methodName.contains("namespace")) {
                                getNamespace = m;
                            } else if (methodName.contains("path") || methodName.contains("key")) {
                                getPath = m;
                            }
                        }
                    }
                    
                    // If still not found, try to identify by invoking and checking values
                    if (getNamespace == null || getPath == null) {
                        for (Method m : resourceLocation.getClass().getMethods()) {
                            if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                                try {
                                    String value = (String) m.invoke(resourceLocation);
                                    // Namespace is usually "minecraft" or a short word
                                    // Path contains underscores like "ancient_city"
                                    if (value != null) {
                                        if (value.equals("minecraft") || 
                                            (value.length() < 20 && !value.contains("_") && !value.contains("/"))) {
                                            if (getNamespace == null) getNamespace = m;
                                        } else if (value.contains("_") || value.contains("/")) {
                                            if (getPath == null) getPath = m;
                                        }
                                    }
                                } catch (Exception ex) {
                                    // Skip
                                }
                            }
                        }
                    }
                }
            }
            
            if (getNamespace != null && getPath != null) {
                String namespace = (String) getNamespace.invoke(resourceLocation);
                String path = (String) getPath.invoke(resourceLocation);
                return namespace + ":" + path;
            }
            
            // Fallback: use toString() but verify it looks correct
            String str = resourceLocation.toString();
            
            // Check if toString looks like a valid ResourceLocation
            if (str.contains(":") && str.length() > 5) {
                // Validate it has namespace:path format
                String[] parts = str.split(":", 2);
                if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    return str;
                }
            }
            
            // If toString is weird, try to get internal fields
            try {
                java.lang.reflect.Field[] fields = resourceLocation.getClass().getDeclaredFields();
                String namespace = null;
                String path = null;
                
                for (java.lang.reflect.Field f : fields) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        String value = (String) f.get(resourceLocation);
                        if (value != null) {
                            // First string field is usually namespace, second is path
                            if (namespace == null) {
                                namespace = value;
                            } else if (path == null) {
                                path = value;
                                break;
                            }
                        }
                    }
                }
                
                if (namespace != null && path != null) {
                    return namespace + ":" + path;
                }
            } catch (Exception e) {
                // Ignore field access errors
            }
            
            // Last resort: return whatever toString gave us
            plugin.getConfigManager().debug("ResourceLocation toString fallback: " + str + 
                " (class: " + resourceLocation.getClass().getName() + ")");
            return str;
            
        } catch (Exception e) {
            plugin.getConfigManager().debug("Error extracting ResourceLocation: " + e.getMessage());
            return resourceLocation.toString();
        }
    }
    
    /**
     * Scan an area and find ALL structures present.
     * This queries chunk data directly instead of running locate algorithms.
     * Much more efficient than searching for each structure type individually.
     * 
     * @param world The world to scan
     * @param radiusBlocks Radius in blocks from origin (0,0)
     * @param sender CommandSender to receive progress updates
     * @return CompletableFuture with list of found structure positions
     */
    public CompletableFuture<List<StructureResult>> scan(World world, int radiusBlocks, CommandSender sender) {
        CompletableFuture<List<StructureResult>> future = new CompletableFuture<>();
        
        if (scanInProgress) {
            if (sender != null) {
                sender.sendMessage("§cA scan is already in progress. Use /sg cancel to stop it.");
            }
            future.complete(new ArrayList<>());
            return future;
        }
        
        scanInProgress = true;
        
        // Build list of chunks to scan, filtering out already-scanned chunks
        List<int[]> allChunks = buildChunkList(radiusBlocks);
        Set<Long> alreadyScanned = plugin.getDatabase().getScannedChunks(world.getName());
        
        List<int[]> chunksToScan = new ArrayList<>();
        int skippedCount = 0;
        for (int[] chunk : allChunks) {
            long packed = ((long) chunk[0] & 0xFFFFFFFFL) | (((long) chunk[1]) << 32);
            if (!alreadyScanned.contains(packed)) {
                chunksToScan.add(chunk);
            } else {
                skippedCount++;
            }
        }
        
        if (chunksToScan.isEmpty()) {
            scanInProgress = false;
            if (sender != null) {
                sender.sendMessage("§aAll " + allChunks.size() + " chunks in this area have already been scanned!");
                sender.sendMessage("§7Use §e/sg scan reset§7 to clear scan history and rescan.");
            }
            future.complete(new ArrayList<>());
            return future;
        }
        
        int totalChunks = chunksToScan.size();
        int chunksPerBatch = plugin.getConfigManager().getScanChunksPerTick();
        
        if (sender != null) {
            sender.sendMessage("§6Starting structure scan...");
            if (skippedCount > 0) {
                sender.sendMessage("§7Skipping §e" + skippedCount + "§7 already-scanned chunks");
            }
            sender.sendMessage("§7Scanning §e" + totalChunks + "§7 new chunks in §e" + world.getName() + 
                " §7(" + chunksPerBatch + " chunks/batch)");
            sender.sendMessage("§8(Use /sg cancel to stop the scan)");
        }
        
        plugin.getLogger().info("Starting structure scan: " + totalChunks + " new chunks (" + 
            skippedCount + " skipped)");
        
        // Start the scan
        ScanState state = new ScanState(world, chunksToScan, sender, future, totalChunks, 
            System.currentTimeMillis(), radiusBlocks);
        processScanQueue(state);
        
        return future;
    }
    
    /**
     * Result of finding a structure
     */
    public static class StructureResult {
        public final String structureType;
        public final int x;
        public final int z;
        public final int chunkX;
        public final int chunkZ;
        
        public StructureResult(String structureType, int x, int z, int chunkX, int chunkZ) {
            this.structureType = structureType;
            this.x = x;
            this.z = z;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
    
    /**
     * Holds the state of an ongoing scan operation
     */
    private static class ScanState {
        final World world;
        final List<int[]> chunks;
        final CommandSender sender;
        final CompletableFuture<List<StructureResult>> future;
        final List<StructureResult> results = new ArrayList<>();
        final Set<String> foundKeys = new HashSet<>(); // Dedupe: "type:x:z"
        final Map<String, Integer> typeCounts = new HashMap<>();
        final int totalChunks;
        final long startTime;
        final int maxRadius;
        int currentIndex = 0;
        int lastProgressPercent = -1;
        
        ScanState(World world, List<int[]> chunks, CommandSender sender,
                 CompletableFuture<List<StructureResult>> future, int totalChunks, 
                 long startTime, int maxRadius) {
            this.world = world;
            this.chunks = chunks;
            this.sender = sender;
            this.future = future;
            this.totalChunks = totalChunks;
            this.startTime = startTime;
            this.maxRadius = maxRadius;
        }
    }
    
    /**
     * Build list of chunk coordinates to scan
     */
    private List<int[]> buildChunkList(int radiusBlocks) {
        List<int[]> chunks = new ArrayList<>();
        
        int radiusChunks = (radiusBlocks / 16) + 1;
        
        for (int cx = -radiusChunks; cx <= radiusChunks; cx++) {
            for (int cz = -radiusChunks; cz <= radiusChunks; cz++) {
                // Check if chunk center is within radius
                int blockX = cx * 16 + 8;
                int blockZ = cz * 16 + 8;
                double dist = Math.sqrt(blockX * blockX + blockZ * blockZ);
                
                if (dist <= radiusBlocks + 16) {
                    chunks.add(new int[]{cx, cz});
                }
            }
        }
        
        return chunks;
    }
    
    /**
     * Process the scan queue asynchronously for zero TPS impact
     */
    private void processScanQueue(ScanState state) {
        final int chunksPerBatch = plugin.getConfigManager().getScanChunksPerTick();
        
        // Initialize reflection cache on main thread first
        if (!initReflectionCache(state.world)) {
            if (state.sender != null) {
                state.sender.sendMessage("§cFailed to initialize structure scanner.");
            }
            scanInProgress = false;
            state.future.complete(new ArrayList<>());
            return;
        }
        
        // Run the heavy scanning work asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int lastReportedPercent = -1;
                int totalStructuresFound = 0;
                
                // Log which path we're using at the start
                plugin.getConfigManager().debug("Starting async scan using " + (useFabricPath ? "Chunk-based" : "Mojang") + " path");
                plugin.getConfigManager().debug("Total chunks to scan: " + state.chunks.size());
                
                while (state.currentIndex < state.chunks.size() && scanInProgress) {
                    // Process a batch of chunks
                    int batchEnd = Math.min(state.currentIndex + chunksPerBatch, state.chunks.size());
                    
                    for (int i = state.currentIndex; i < batchEnd && scanInProgress; i++) {
                        int[] chunkCoord = state.chunks.get(i);
                        int chunkX = chunkCoord[0];
                        int chunkZ = chunkCoord[1];
                        
                        // Get structures in this chunk (uses cached reflection)
                        // Returns structures at their ORIGIN position for proper deduplication
                        List<StructureResult> chunkStructures = getStructuresInChunkAsync(chunkX, chunkZ);
                        
                        // Debug first few chunks to verify scanning works
                        if (i < 5 || chunkStructures.size() > 0) {
                            plugin.getConfigManager().debug("Chunk " + chunkX + "," + chunkZ + ": found " + 
                                chunkStructures.size() + " structures");
                        }
                        
                        for (StructureResult result : chunkStructures) {
                            // Check if origin is within requested radius
                            double dist = Math.sqrt(result.x * result.x + result.z * result.z);
                            if (dist > state.maxRadius) continue;
                            
                            // Dedupe by structure type and EXACT origin position
                            // Same structure instance will always have the same origin coords
                            String key = result.structureType + ":" + result.x + ":" + result.z;
                            
                            if (!state.foundKeys.contains(key)) {
                                state.foundKeys.add(key);
                                state.results.add(result);
                                state.typeCounts.merge(result.structureType, 1, Integer::sum);
                            }
                        }
                    }
                    
                    state.currentIndex = batchEnd;
                    
                    // Report progress periodically
                    int progressPercent = (state.currentIndex * 100) / state.totalChunks;
                    if (progressPercent != lastReportedPercent && progressPercent % 10 == 0) {
                        lastReportedPercent = progressPercent;
                        
                        long elapsed = System.currentTimeMillis() - state.startTime;
                        long estimatedTotal = (elapsed * state.totalChunks) / Math.max(1, state.currentIndex);
                        long remaining = estimatedTotal - elapsed;
                        
                        final int percent = progressPercent;
                        final int found = state.results.size();
                        final String timeRemaining = formatTime(remaining);
                        
                        // Send message on main thread
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (state.sender != null) {
                                state.sender.sendMessage("§7Scan progress: §e" + percent + "%§7 (" +
                                    found + " structures found, ~" + timeRemaining + " remaining)");
                            }
                        });
                    }
                    
                    // Small delay to prevent hogging CPU (1ms between batches)
                    try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                }
                
                // Check if we were cancelled during the loop
                boolean wasCancelled = !scanInProgress;
                
                // Only write to database if we completed successfully (not cancelled)
                if (!wasCancelled) {
                    int totalToWrite = state.results.size();
                    plugin.getLogger().info("Scan complete, writing " + totalToWrite + " structures to database (batch mode)...");
                    
                    // Convert results to batch format for much faster insert
                    List<Object[]> batchData = new ArrayList<>();
                    for (StructureResult result : state.results) {
                        batchData.add(new Object[]{result.structureType, result.x, result.z});
                    }
                    
                    int inserted = plugin.getDatabase().addStructuresBatch(state.world.getName(), batchData);
                    plugin.getLogger().info("Database write complete: " + inserted + " new structures inserted");
                    
                    // Mark all chunks as scanned so they're skipped next time
                    plugin.getDatabase().markChunksScanned(state.world.getName(), state.chunks);
                    plugin.getLogger().info("Marked " + state.chunks.size() + " chunks as scanned");
                    
                    // Finish on main thread (just the message/cleanup)
                    // Use runTask to ensure it runs on next tick
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        finishScan(state, false);
                    });
                } else {
                    plugin.getLogger().info("Scan was cancelled, skipping database write");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        finishScan(state, true);
                    });
                }
                
            } catch (Exception e) {
                plugin.getLogger().warning("Async scan error: " + e.getMessage());
                e.printStackTrace();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (state.sender != null) {
                        state.sender.sendMessage("§cScan error: " + e.getMessage());
                    }
                    scanInProgress = false;
                    state.future.complete(state.results);
                });
            }
        });
    }
    
    /**
     * Get structures in chunk - async-safe version using pre-cached reflection
     * Only returns structures where this chunk IS the origin chunk (for proper 1:1 mapping)
     * Supports both Mojang (StructureManager) and Fabric (Chunk.getStructureStarts) paths
     */
    private List<StructureResult> getStructuresInChunkAsync(int chunkX, int chunkZ) {
        List<StructureResult> results = new ArrayList<>();
        
        // Debug logging removed to prevent lag - use /sg debug for one-time diagnostics
        
        try {
            if (useFabricPath) {
                // Chunk-based path: use Chunk.getStructureStarts/getAllStarts()
                results = getStructuresViaChunkFabric(chunkX, chunkZ);
            } else {
                // Mojang path: use StructureManager.getAllStructuresAt()
                results = getStructuresViaMojang(chunkX, chunkZ);
                
                // If Mojang path returns nothing but we have the chunk method, try chunk path as fallback
                if (results.isEmpty() && getChunkMethod != null && chunkGetStructureStartsMethod != null) {
                    results = getStructuresViaChunkFabric(chunkX, chunkZ);
                }
            }
        } catch (Exception e) {
            // If one path fails, try the other
            plugin.getConfigManager().debug("Primary path failed for chunk " + chunkX + "," + chunkZ + ": " + e.getMessage());
            try {
                if (useFabricPath && getAllStructuresAtMethod != null) {
                    results = getStructuresViaMojang(chunkX, chunkZ);
                } else if (!useFabricPath && chunkGetStructureStartsMethod != null) {
                    results = getStructuresViaChunkFabric(chunkX, chunkZ);
                }
            } catch (Exception e2) {
                plugin.getConfigManager().debug("Fallback path also failed: " + e2.getMessage());
            }
        }
        
        return results;
    }
    
    /**
     * Mojang path: Get structures via StructureManager.getAllStructuresAt()
     */
    private List<StructureResult> getStructuresViaMojang(int chunkX, int chunkZ) {
        List<StructureResult> results = new ArrayList<>();
        
        try {
            int blockX = chunkX * 16 + 8;
            int blockZ = chunkZ * 16 + 8;
            Object blockPos = blockPosConstructor.newInstance(blockX, 64, blockZ);
            
            Object structureMapObj = getAllStructuresAtMethod.invoke(cachedStructureManager, blockPos);
            
            if (structureMapObj != null && structureMapObj instanceof Map) {
                Map<?, ?> structureMap = (Map<?, ?>) structureMapObj;
                
                for (Map.Entry<?, ?> entry : structureMap.entrySet()) {
                    Object structure = entry.getKey();
                    Object chunkReferences = entry.getValue(); // LongOpenHashSet containing origin chunk
                    
                    String structureName = getStructureNameCached(structure);
                    
                    // Skip ignored structures
                    if (structureName == null || plugin.getConfigManager().isStructureIgnored(structureName)) {
                        continue;
                    }
                    
                    // Decode the origin chunk from LongOpenHashSet using cached reflection
                    int originChunkX = chunkX;
                    int originChunkZ = chunkZ;
                    
                    try {
                        if (chunkReferences != null && cachedIteratorMethod != null) {
                            Object iterator = cachedIteratorMethod.invoke(chunkReferences);
                            
                            if ((boolean) cachedHasNextMethod.invoke(iterator)) {
                                long packedPos = (long) cachedNextLongMethod.invoke(iterator);
                                
                                // Decode packed ChunkPos (x in lower 32 bits, z in upper 32 bits)
                                originChunkX = (int) packedPos;
                                originChunkZ = (int) (packedPos >> 32);
                            }
                        }
                    } catch (Exception e) {
                        // Keep using query chunk as fallback
                    }
                    
                    // ONLY record if we're at the origin chunk - this gives us exactly 1 entry per structure
                    if (originChunkX == chunkX && originChunkZ == chunkZ) {
                        int originX = originChunkX * 16 + 8;
                        int originZ = originChunkZ * 16 + 8;
                        results.add(new StructureResult(structureName, originX, originZ, originChunkX, originChunkZ));
                    }
                }
            }
        } catch (Exception e) {
            // Silent fail
        }
        
        return results;
    }
    
    /**
     * Fabric path: Get structures via Chunk.getStructureStarts()
     * Returns Map<Structure, StructureStart>
     */
    private List<StructureResult> getStructuresViaChunkFabric(int chunkX, int chunkZ) {
        List<StructureResult> results = new ArrayList<>();
        
        try {
            // Get chunk from ServerWorld
            Object chunk = getChunkMethod.invoke(cachedServerLevel, chunkX, chunkZ);
            if (chunk == null) {
                plugin.getConfigManager().debug("getStructuresViaChunkFabric: chunk is null for " + chunkX + "," + chunkZ);
                return results;
            }
            
            // Get structure starts map
            Object structureStartsObj = chunkGetStructureStartsMethod.invoke(chunk);
            if (structureStartsObj == null) {
                plugin.getConfigManager().debug("getStructuresViaChunkFabric: structureStarts is null");
                return results;
            }
            if (!(structureStartsObj instanceof Map)) {
                plugin.getConfigManager().debug("getStructuresViaChunkFabric: structureStarts is not a Map, it's: " + 
                    structureStartsObj.getClass().getName());
                return results;
            }
            
            Map<?, ?> structureStarts = (Map<?, ?>) structureStartsObj;
            plugin.getConfigManager().debug("getStructuresViaChunkFabric: chunk " + chunkX + "," + chunkZ + 
                " has " + structureStarts.size() + " structure entries");
            
            for (Map.Entry<?, ?> entry : structureStarts.entrySet()) {
                Object structure = entry.getKey();
                Object structureStart = entry.getValue();
                
                plugin.getConfigManager().debug("  Entry: key=" + structure.getClass().getSimpleName() + 
                    ", value=" + (structureStart != null ? structureStart.getClass().getSimpleName() : "null"));
                
                if (structureStart == null) continue;
                
                String structureName = getStructureNameCached(structure);
                plugin.getConfigManager().debug("  Structure name: " + structureName);
                
                // Skip ignored structures
                if (structureName == null || plugin.getConfigManager().isStructureIgnored(structureName)) {
                    continue;
                }
                
                // Get the origin position from StructureStart
                // StructureStart has a chunk position stored, or we can get it from bounding box
                int originChunkX = chunkX;
                int originChunkZ = chunkZ;
                
                try {
                    // Try to get ChunkPos from StructureStart
                    Method getChunkPosMethod = findMethodByNames(structureStart.getClass(),
                        "getChunkPos", "method_14963", "getPos");
                    if (getChunkPosMethod != null) {
                        Object chunkPos = getChunkPosMethod.invoke(structureStart);
                        if (chunkPos != null) {
                            Method getXMethod = findMethodByNames(chunkPos.getClass(), "x", "method_8324", "getX");
                            Method getZMethod = findMethodByNames(chunkPos.getClass(), "z", "method_8326", "getZ");
                            
                            if (getXMethod != null && getZMethod != null) {
                                originChunkX = (int) getXMethod.invoke(chunkPos);
                                originChunkZ = (int) getZMethod.invoke(chunkPos);
                            } else {
                                // Try fields
                                try {
                                    java.lang.reflect.Field xField = chunkPos.getClass().getField("x");
                                    java.lang.reflect.Field zField = chunkPos.getClass().getField("z");
                                    originChunkX = xField.getInt(chunkPos);
                                    originChunkZ = zField.getInt(chunkPos);
                                } catch (Exception e2) {
                                    // Use current chunk
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Keep using current chunk coords
                }
                
                // ONLY record if this IS the origin chunk
                if (originChunkX == chunkX && originChunkZ == chunkZ) {
                    int originX = originChunkX * 16 + 8;
                    int originZ = originChunkZ * 16 + 8;
                    results.add(new StructureResult(structureName, originX, originZ, originChunkX, originChunkZ));
                    plugin.getConfigManager().debug("  Added structure result: " + structureName + " at chunk " + originChunkX + "," + originChunkZ);
                } else {
                    plugin.getConfigManager().debug("  Skipping: origin chunk " + originChunkX + "," + originChunkZ + 
                        " != query chunk " + chunkX + "," + chunkZ);
                }
            }
        } catch (Exception e) {
            plugin.getConfigManager().debug("getStructuresViaChunkFabric error: " + e.getMessage());
            e.printStackTrace();
        }
        
        return results;
    }
    
    /**
     * Get structure name using cached registry (fast path)
     */
    private String getStructureNameCached(Object structure) {
        // Try registry lookup first (works on modern versions with full registry access)
        if (getKeyMethod != null && cachedStructureRegistry != null) {
            try {
                Object resourceLocation = getKeyMethod.invoke(cachedStructureRegistry, structure);
                if (resourceLocation != null) {
                    // Use proper extraction to handle both Mojang and Fabric ResourceLocations
                    String name = extractResourceLocationName(resourceLocation);
                    if (name != null && name.contains(":") && name.length() > 5) {
                        return name;
                    }
                }
            } catch (Exception e) {
                // Try alternative approaches
            }
        }
        
        // Try to get the ResourceKey/Holder if available (Fabric uses Holders extensively)
        try {
            // Try to find a method that returns the resource key directly
            Method getTypeMethod = findMethodByNames(structure.getClass(), 
                "getType", "type", "method_41626", "method_10227");
            if (getTypeMethod != null) {
                Object type = getTypeMethod.invoke(structure);
                if (type != null) {
                    String typeStr = extractResourceLocationName(type);
                    if (typeStr != null && typeStr.contains(":") && typeStr.length() > 5) {
                        return typeStr;
                    }
                }
            }
        } catch (Exception e) {
            // Continue to fallback
        }
        
        // Fabric/modern versions may use Holder<Structure> - try to unwrap
        try {
            // Check if this is a Holder and get its key or value
            Method keyMethod = findMethodByNames(structure.getClass(), 
                "key", "method_40327", "getKey", "registryKey");
            if (keyMethod != null) {
                Object key = keyMethod.invoke(structure);
                if (key != null) {
                    // Key is a ResourceKey, try to get its location
                    Method locationMethod = findMethodByNames(key.getClass(), 
                        "location", "method_29177", "getValue", "getLocation");
                    if (locationMethod != null) {
                        Object loc = locationMethod.invoke(key);
                        if (loc != null) {
                            String name = extractResourceLocationName(loc);
                            if (name != null && name.contains(":") && name.length() > 5) {
                                return name;
                            }
                        }
                    }
                }
            }
            
            // Also try value() for Holder types
            Method valueMethod = findMethodByNames(structure.getClass(), 
                "value", "method_40329", "get");
            if (valueMethod != null) {
                Object innerStructure = valueMethod.invoke(structure);
                if (innerStructure != null && innerStructure != structure) {
                    // Recursively try to get name from the inner structure
                    String name = getStructureNameCached(innerStructure);
                    if (name != null && name.contains(":") && name.length() > 5) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            // Continue to fallback
        }
        
        // Try using the structure's own toString if it contains a resource location
        try {
            String structStr = structure.toString();
            // Look for patterns like "minecraft:village" or "namespace:structure_name"
            if (structStr.contains(":")) {
                // Extract resource location pattern
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "([a-z0-9_.-]+:[a-z0-9_/.-]+)").matcher(structStr);
                if (matcher.find()) {
                    String found = matcher.group(1);
                    // Prefer paths with worldgen/structure or just structure names
                    if (found.contains("structure") || !found.contains("/")) {
                        // Remove worldgen/structure/ prefix if present
                        if (found.contains("worldgen/structure/")) {
                            found = found.replace("worldgen/structure/", "");
                        }
                        if (found.length() > 3) {
                            return found;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Continue to fallback
        }
        
        // Fallback: extract from class name
        String className = structure.getClass().getSimpleName();
        plugin.getConfigManager().debug("getStructureNameCached fallback - class: " + className + 
            ", toString: " + structure.toString().substring(0, Math.min(100, structure.toString().length())));
        if (className.endsWith("Structure")) {
            return "minecraft:" + className.substring(0, className.length() - 9).toLowerCase();
        }
        
        // Last resort: use class name directly
        plugin.getConfigManager().debug("Could not extract structure name, using class: " + className);
        return "minecraft:" + className.toLowerCase().replace("$", "_");
    }
    
    /**
     * Finish the scan and report results
     * @param state The scan state
     * @param wasCancelled Whether the scan was cancelled by user
     */
    private void finishScan(ScanState state, boolean wasCancelled) {
        scanInProgress = false;
        
        long elapsed = System.currentTimeMillis() - state.startTime;
        String elapsedTime = formatTime(elapsed);
        
        if (state.sender != null) {
            if (wasCancelled) {
                state.sender.sendMessage("§eScan cancelled. Found §c" + state.results.size() + "§e structures before cancellation.");
            } else if (state.results.isEmpty()) {
                state.sender.sendMessage("§cScan complete. No structures found.");
            } else {
                state.sender.sendMessage("§a✓ Scan complete! Found §e" + state.results.size() + "§a structures in " + elapsedTime);
                
                // Show breakdown by type (limit to top 10)
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(state.typeCounts.entrySet());
                sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
                
                int shown = 0;
                for (Map.Entry<String, Integer> entry : sorted) {
                    if (shown >= 10) {
                        int remaining = sorted.size() - 10;
                        state.sender.sendMessage("§7  ... and " + remaining + " more types");
                        break;
                    }
                    state.sender.sendMessage("§7  - " + entry.getKey() + ": §e" + entry.getValue());
                    shown++;
                }
            }
        }
        
        String status = wasCancelled ? "cancelled" : "complete";
        plugin.getLogger().info("Structure scan " + status + ": " + state.results.size() + " found in " + elapsedTime);
        state.future.complete(state.results);
    }
    
    /**
     * Format milliseconds as human-readable time
     */
    private String formatTime(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + "m " + seconds + "s";
    }
    
    // ============================================================================
    // PUBLIC API FOR CHUNK LOAD LISTENER (On-Demand Protection)
    // ============================================================================
    
    /**
     * Initialize reflection cache for chunk-based structure detection.
     * Should be called once when the listener is registered.
     * @param world The world to use for initialization
     * @return true if initialization succeeded
     */
    public boolean initForChunkListener(World world) {
        if (reflectionCacheInitialized) {
            return true;
        }
        
        try {
            // Run initialization on the main thread if needed
            if (Bukkit.isPrimaryThread()) {
                return initReflectionCache(world);
            } else {
                // Schedule on main thread and wait
                CompletableFuture<Boolean> future = new CompletableFuture<>();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        future.complete(initReflectionCache(world));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to init reflection cache: " + e.getMessage());
                        future.complete(false);
                    }
                });
                return future.get(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize chunk listener: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if reflection cache is initialized and ready for chunk queries.
     * @return true if ready to process chunks
     */
    public boolean isReady() {
        return reflectionCacheInitialized;
    }
    
    /**
     * Get all structure origins in a specific chunk.
     * This is the main entry point for the ChunkLoadListener.
     * Thread-safe - can be called from async context.
     * 
     * @param world The world
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return List of structures that have their origin in this chunk
     */
    public List<StructureResult> getStructuresInChunk(World world, int chunkX, int chunkZ) {
        if (!reflectionCacheInitialized) {
            // Try to initialize
            if (!initForChunkListener(world)) {
                return Collections.emptyList();
            }
        }
        
        // Use the async-safe method
        return getStructuresInChunkAsync(chunkX, chunkZ);
    }
    
    /**
     * Get structures in chunk asynchronously with CompletableFuture.
     * Preferred for non-blocking operations.
     * 
     * @param world The world
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return CompletableFuture that will contain the list of structures
     */
    public CompletableFuture<List<StructureResult>> getStructuresInChunkFuture(World world, int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            return getStructuresInChunk(world, chunkX, chunkZ);
        });
    }
    
    /**
     * Check if a specific structure type exists in a chunk.
     * 
     * @param world The world
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @param structurePattern The structure type pattern (supports wildcards like "minecraft:*" or "terralith:village_*")
     * @return true if a matching structure origin exists in this chunk
     */
    public boolean hasStructureInChunk(World world, int chunkX, int chunkZ, String structurePattern) {
        List<StructureResult> structures = getStructuresInChunk(world, chunkX, chunkZ);
        
        for (StructureResult result : structures) {
            if (matchesStructurePattern(result.structureType, structurePattern)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if a structure type matches a pattern (supports wildcards).
     * @param structureType The full structure type (e.g., "minecraft:village_plains")
     * @param pattern The pattern to match (e.g., "minecraft:village_*" or "*:village_*")
     * @return true if the structure matches the pattern
     */
    public boolean matchesStructurePattern(String structureType, String pattern) {
        // Exact match
        if (pattern.equals(structureType)) {
            return true;
        }
        
        // Wildcard match
        if (pattern.contains("*")) {
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*");
            return structureType.matches(regex);
        }
        
        return false;
    }
}
