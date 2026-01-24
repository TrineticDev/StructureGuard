package com.structureguard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages plugin configuration including protected structure rules.
 */
public class ConfigManager {
    
    private final StructureGuardPlugin plugin;
    private boolean debugMode;
    private int defaultRadius;
    private int defaultYMin;
    private int defaultYMax;
    private boolean processExistingChunks;
    private Map<String, String> defaultFlags;
    
    // Protection rules - pattern -> rule
    private final Map<String, ProtectionRule> protectionRules = new HashMap<>();
    
    public ConfigManager(StructureGuardPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    private void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        
        debugMode = config.getBoolean("debug", false);
        defaultRadius = config.getInt("default-radius", 48);
        defaultYMin = config.getInt("default-y-min", -64);
        defaultYMax = config.getInt("default-y-max", 320);
        processExistingChunks = config.getBoolean("process-existing-chunks", true);
        
        // Load default flags
        defaultFlags = new HashMap<>();
        if (config.isConfigurationSection("default-flags")) {
            for (String key : config.getConfigurationSection("default-flags").getKeys(false)) {
                defaultFlags.put(key, config.getString("default-flags." + key));
            }
        }
        // Note: WorldGuard already denies block-break/place by default in regions
        // so we don't need to set them unless the user wants to override
        
        // Load protected structures
        protectionRules.clear();
        if (config.isConfigurationSection("protected-structures")) {
            ConfigurationSection structures = config.getConfigurationSection("protected-structures");
            for (String patternKey : structures.getKeys(false)) {
                ConfigurationSection ruleSection = structures.getConfigurationSection(patternKey);
                if (ruleSection != null) {
                    // Convert config key back to pattern
                    // Only the FIRST underscore is the namespace separator (becomes :)
                    // Other underscores are part of the structure name
                    String pattern = configKeyToPattern(patternKey);
                    boolean enabled = ruleSection.getBoolean("enabled", true);
                    int radius = ruleSection.getInt("radius", defaultRadius);
                    int yMin = ruleSection.getInt("y-min", defaultYMin);
                    int yMax = ruleSection.getInt("y-max", defaultYMax);
                    int priority = ruleSection.getInt("priority", 10);
                    
                    Map<String, String> flags = new HashMap<>(defaultFlags);
                    if (ruleSection.isConfigurationSection("flags")) {
                        for (String flagKey : ruleSection.getConfigurationSection("flags").getKeys(false)) {
                            flags.put(flagKey, ruleSection.getString("flags." + flagKey));
                        }
                    }
                    
                    ProtectionRule rule = new ProtectionRule();
                    rule.pattern = pattern;
                    rule.enabled = enabled;
                    rule.radius = radius;
                    rule.yMin = yMin;
                    rule.yMax = yMax;
                    rule.priority = priority;
                    rule.flags = flags;
                    
                    protectionRules.put(pattern, rule);
                    debug("Loaded protection rule: " + pattern + " (enabled=" + enabled + 
                          ", radius=" + radius + ", priority=" + priority + ")");
                }
            }
        }
        
        plugin.getLogger().info("Loaded " + protectionRules.size() + " protection rules");
    }
    
    /**
     * Get protection rule for a structure type.
     * Supports wildcard matching (e.g., "minecraft:*" matches "minecraft:village")
     */
    public ProtectionRule getProtectionRule(String structureType) {
        if (structureType == null) return null;
        
        // Exact match first
        if (protectionRules.containsKey(structureType)) {
            return protectionRules.get(structureType);
        }
        
        // Wildcard match - find most specific matching rule
        ProtectionRule bestMatch = null;
        int bestPriority = Integer.MIN_VALUE;
        
        for (Map.Entry<String, ProtectionRule> entry : protectionRules.entrySet()) {
            String pattern = entry.getKey();
            ProtectionRule rule = entry.getValue();
            
            if (matchesPattern(structureType, pattern)) {
                if (rule.priority > bestPriority || bestMatch == null) {
                    bestMatch = rule;
                    bestPriority = rule.priority;
                }
            }
        }
        
        return bestMatch;
    }
    
    /**
     * Check if a structure type matches a pattern (supports * wildcards).
     */
    private boolean matchesPattern(String structureType, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.contains("*")) {
            // Convert wildcard pattern to regex
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*");
            return structureType.matches(regex);
        }
        return pattern.equals(structureType);
    }
    
    /**
     * Add or update a protection rule and save to config.
     */
    public void addProtectionRule(ProtectionRule rule) {
        protectionRules.put(rule.pattern, rule);
        
        // Save to config
        FileConfiguration config = plugin.getConfig();
        // Only the FIRST colon is the namespace separator (becomes _)
        // Other underscores/colons stay as-is
        String path = "protected-structures." + patternToConfigKey(rule.pattern);
        config.set(path + ".enabled", rule.enabled);
        config.set(path + ".radius", rule.radius);
        config.set(path + ".y-min", rule.yMin);
        config.set(path + ".y-max", rule.yMax);
        config.set(path + ".priority", rule.priority);
        
        // Save flags
        for (Map.Entry<String, String> flag : rule.flags.entrySet()) {
            config.set(path + ".flags." + flag.getKey(), flag.getValue());
        }
        plugin.saveConfig();
    }
    
    /**
     * Remove a protection rule.
     */
    public boolean removeProtectionRule(String pattern) {
        if (protectionRules.remove(pattern) != null) {
            // Remove from config
            FileConfiguration config = plugin.getConfig();
            config.set("protected-structures." + patternToConfigKey(pattern), null);
            plugin.saveConfig();
            return true;
        }
        return false;
    }
    
    /**
     * Get all protection rules.
     */
    public Map<String, ProtectionRule> getProtectionRules() {
        return new HashMap<>(protectionRules);
    }
    
    /**
     * Convert a pattern (minecraft:end_city) to a config key (minecraft--end_city)
     * Uses -- as separator because YAML interprets . as nested path and : is invalid.
     */
    private String patternToConfigKey(String pattern) {
        return pattern.replace(":", "--");
    }
    
    /**
     * Convert a config key (minecraft--end_city) back to a pattern (minecraft:end_city)
     */
    private String configKeyToPattern(String configKey) {
        // New format: -- separator
        if (configKey.contains("--")) {
            return configKey.replace("--", ":");
        }
        // Old format: _ separator (backwards compatibility, but only first underscore)
        int underscoreIndex = configKey.indexOf('_');
        if (underscoreIndex > 0 && !configKey.contains(":")) {
            return configKey.substring(0, underscoreIndex) + ":" + configKey.substring(underscoreIndex + 1);
        }
        return configKey;
    }
    
    /**
     * Check if there are any enabled protection rules.
     */
    public boolean hasEnabledProtectionRules() {
        for (ProtectionRule rule : protectionRules.values()) {
            if (rule.enabled) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a structure type is protected by any enabled rule.
     */
    public boolean isStructureProtected(String structureType) {
        ProtectionRule rule = getProtectionRule(structureType);
        return rule != null && rule.enabled;
    }
    
    public void debug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }
    
    public boolean isDebugMode() {
        return debugMode;
    }
    
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
        plugin.getConfig().set("debug", debug);
        plugin.saveConfig();
    }
    
    public int getDefaultRadius() {
        return defaultRadius;
    }
    
    public int getDefaultYMin() {
        return defaultYMin;
    }
    
    public int getDefaultYMax() {
        return defaultYMax;
    }
    
    public boolean shouldProcessExistingChunks() {
        return processExistingChunks;
    }
    
    public Map<String, String> getDefaultFlags() {
        return new HashMap<>(defaultFlags);
    }
    
    /**
     * Get chunks to process per batch for manual scanning.
     * On-demand protection doesn't need this, but manual /sg protect does.
     * @return chunks per batch (default 512)
     */
    public int getScanChunksPerTick() {
        return plugin.getConfig().getInt("scan-chunks-per-tick", 512);
    }
    
    /**
     * Check if a structure should be ignored (for backwards compatibility).
     * In on-demand mode, this is replaced by protection rules - 
     * structures without a matching rule are effectively ignored.
     * @param structureName the structure name
     * @return true if the structure should be ignored
     */
    public boolean isStructureIgnored(String structureName) {
        // In on-demand mode, we don't ignore structures during detection
        // Instead, we only protect those with matching rules
        // This method returns false to allow all structures to be detected
        // but they won't be protected unless there's a matching rule
        return false;
    }
    
    /**
     * Protection rule definition.
     */
    public static class ProtectionRule {
        public String pattern;
        public boolean enabled = true;
        public int radius = 48;
        public int yMin = -64;
        public int yMax = 320;
        public int priority = 10;
        public Map<String, String> flags = new HashMap<>();
        
        public ProtectionRule() {
            // Note: WorldGuard already denies block-break/place by default
            // Flags here are only for overrides or extra protections
        }
    }
}
