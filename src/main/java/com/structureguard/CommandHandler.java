package com.structureguard;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all StructureGuard commands.
 * 
 * Command Structure:
 * - Discovery: find, listall, info
 * - Rules: enable, disable, rules (manage config-based auto-protection)
 * - Flags: flag (set flags on rules AND existing regions)
 * - Regions: clearregions, addowner/member, removeowner/member
 * - Utility: status, reload
 */
public class CommandHandler implements CommandExecutor, TabCompleter {
    
    private final StructureGuardPlugin plugin;
    
    public CommandHandler(StructureGuardPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        
        String sub = args[0].toLowerCase();
        
        switch (sub) {
            // Discovery
            case "find":
                return cmdFind(sender, args);
            case "listall":
                return cmdListAll(sender, args);
            case "info":
                return cmdInfo(sender, args);
            
            // Rule management (config-based auto-protection)
            case "protect":
                return cmdProtect(sender, args);
            case "unprotect":
                return cmdUnprotect(sender, args);
            case "enable":
                return cmdEnable(sender, args);
            case "disable":
                return cmdDisable(sender, args);
            case "rules":
                return cmdRules(sender, args);
            
            // Flags (works on both rules and existing regions)
            case "flag":
                return cmdFlag(sender, args);
            
            // Region management
            case "clearregions":
                return cmdClearRegions(sender, args);
            case "addowner":
                return cmdAddOwner(sender, args);
            case "removeowner":
                return cmdRemoveOwner(sender, args);
            case "addmember":
                return cmdAddMember(sender, args);
            case "removemember":
                return cmdRemoveMember(sender, args);
            
            // Utility
            case "list":
                return cmdList(sender, args);
            case "status":
                return cmdStatus(sender, args);
            case "reload":
                return cmdReload(sender, args);
            case "debug":
                return cmdDebug(sender, args);
                
            default:
                sender.sendMessage("§cUnknown command. Use /sg for help.");
                return true;
        }
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6§l=== StructureGuard ===");
        sender.sendMessage("§7Automatic structure protection via ChunkLoad events.");
        sender.sendMessage("");
        sender.sendMessage("§e§lDiscovery:");
        sender.sendMessage("§e/sg listall §7- List all structure types in registry");
        sender.sendMessage("§e/sg find <structure> §7- Locate nearest structure");
        sender.sendMessage("§e/sg info §7- Show structure info at your location");
        sender.sendMessage("");
        sender.sendMessage("§e§lProtection Rules §7(auto-protect new chunks):");
        sender.sendMessage("§e/sg protect <pattern> [radius] [ymin] [ymax] §7- Add & enable protection");
        sender.sendMessage("§e/sg unprotect <pattern> [--clear] §7- Remove from config (--clear removes regions)");
        sender.sendMessage("§e/sg enable <pattern> §7- Enable existing rule");
        sender.sendMessage("§e/sg disable <pattern> §7- Disable rule (keeps in config)");
        sender.sendMessage("§e/sg rules §7- Show all protection rules");
        sender.sendMessage("");
        sender.sendMessage("§e§lFlags & Regions:");
        sender.sendMessage("§e/sg flag <pattern> <flag> <value> §7- Set flags on rules & regions");
        sender.sendMessage("§e/sg addowner <pattern> <player|g:group> §7- Add region owner");
        sender.sendMessage("§e/sg addmember <pattern> <player|g:group> §7- Add region member");
        sender.sendMessage("§e/sg clearregions <pattern> §7- Remove WorldGuard regions");
        sender.sendMessage("");
        sender.sendMessage("§e§lUtility:");
        sender.sendMessage("§e/sg list <pattern> §7- List protected structures");
        sender.sendMessage("§e/sg status §7- Show system status");
        sender.sendMessage("§e/sg reload §7- Reload configuration");
        sender.sendMessage("§e/sg debug §7- Toggle debug mode");
    }
    
    // ========================================================================
    // DISCOVERY COMMANDS
    // ========================================================================
    
    private boolean cmdFind(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.find")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sg find <structure-name>");
            sender.sendMessage("§7Example: /sg find minecraft:village");
            sender.sendMessage("§7Use /sg listall to see available structures.");
            return true;
        }
        
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        
        Player player = (Player) sender;
        String structureType = args[1].toLowerCase();
        
        // For /sg find, we need an exact structure ID for NMS locate
        // If no namespace, check database first with wildcard, then try minecraft:
        String searchPattern = structureType;
        if (!structureType.contains(":")) {
            // Check if there's a match in database with any namespace
            searchPattern = "*:*" + structureType + "*";
        }
        
        player.sendMessage("§7Searching for " + structureType + "...");
        
        // First check database for known structures
        Location found = findNearestFromDatabase(player, structureType);
        
        // If not in database, use NMS locate
        if (found == null) {
            found = plugin.getStructureFinder().findNearest(
                player.getWorld(), 
                player.getLocation(), 
                structureType, 
                10000
            );
            
            // If found, add to database for future lookups
            if (found != null) {
                plugin.getDatabase().addStructure(
                    player.getWorld().getName(),
                    structureType,
                    found.getBlockX(),
                    found.getBlockZ()
                );
            }
        }
        
        if (found != null) {
            double dist = player.getLocation().distance(found);
            
            player.sendMessage("§a✓ Found " + structureType);
            player.sendMessage("§7Location: §f" + found.getBlockX() + ", " + found.getBlockZ());
            player.sendMessage("§7Distance: §f" + String.format("%.0f", dist) + " blocks");
            
            if (player.hasPermission("structureguard.teleport")) {
                TextComponent tp = new TextComponent("§e[Click to teleport]");
                tp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, 
                    "/minecraft:tp @s " + found.getBlockX() + " " + (found.getBlockY() + 5) + " " + found.getBlockZ()));
                tp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                    new ComponentBuilder("Click to teleport").create()));
                player.spigot().sendMessage(tp);
            }
        } else {
            player.sendMessage("§cNo " + structureType + " found within 10000 blocks.");
            player.sendMessage("§7Tip: Use /sg listall to see available structure types.");
        }
        
        return true;
    }
    
    private Location findNearestFromDatabase(Player player, String structureType) {
        List<int[]> structures = plugin.getDatabase().getStructuresOfType(
            player.getWorld().getName(), structureType);
        
        if (structures.isEmpty()) {
            return null;
        }
        
        Location playerLoc = player.getLocation();
        int playerX = playerLoc.getBlockX();
        int playerZ = playerLoc.getBlockZ();
        
        int[] nearest = null;
        double nearestDist = Double.MAX_VALUE;
        
        for (int[] coords : structures) {
            double dist = Math.sqrt(
                Math.pow(coords[0] - playerX, 2) + 
                Math.pow(coords[1] - playerZ, 2)
            );
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = coords;
            }
        }
        
        if (nearest != null && nearestDist <= 10000) {
            return new Location(player.getWorld(), nearest[0], 64, nearest[1]);
        }
        
        return null;
    }
    
    private boolean cmdListAll(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.listall")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        sender.sendMessage("§7Loading structure registry...");
        
        List<String> types = plugin.getStructureFinder().getAllStructureTypes();
        
        if (types.isEmpty()) {
            // Check if we're using chunk-based detection (legacy servers like 1.17)
            if (plugin.getStructureFinder().isUsingChunkBasedDetection()) {
                sender.sendMessage("§6This server version uses chunk-based structure detection.");
                sender.sendMessage("§7The §e/sg listall§7 command is not available on legacy servers (1.17-1.18)");
                sender.sendMessage("§7because the structure registry is not accessible.");
                sender.sendMessage("");
                sender.sendMessage("§aStructure detection still works!§7 Use:");
                sender.sendMessage("§e  /sg info§7 - Check structures at your location");
                sender.sendMessage("§e  /sg protect§7 - Protect a structure you're standing in");
                sender.sendMessage("");
                sender.sendMessage("§7Common structure types: §fminecraft:village§7, §fminecraft:fortress§7,");
                sender.sendMessage("§fminecraft:monument§7, §fminecraft:mansion§7, §fminecraft:stronghold");
            } else {
                sender.sendMessage("§cNo structure types found in registry.");
                sender.sendMessage("§7This may indicate a reflection issue. Check console for errors.");
                sender.sendMessage("§7Try: /sg debug to enable debug logging, then /sg reload");
            }
            return true;
        }
        
        // Pagination
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                page = 1;
            }
        }
        
        int perPage = 15;
        int totalPages = (int) Math.ceil(types.size() / (double) perPage);
        page = Math.max(1, Math.min(page, totalPages));
        
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, types.size());
        
        sender.sendMessage("§6§l=== Structure Types [" + page + "/" + totalPages + "] ===");
        
        for (int i = start; i < end; i++) {
            String type = types.get(i);
            String ns = type.contains(":") ? type.substring(0, type.indexOf(":")) : "minecraft";
            String color = ns.equals("minecraft") ? "§e" : "§d";
            
            // Check if protected by a rule
            ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(type);
            String status = (rule != null && rule.enabled) ? " §a✓" : "";
            
            if (sender instanceof Player) {
                TextComponent line = new TextComponent(color + type + status);
                line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/sg enable " + type));
                line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                    new ComponentBuilder("§7Click to enable protection\n§e/sg enable " + type).create()));
                ((Player) sender).spigot().sendMessage(line);
            } else {
                sender.sendMessage(color + type + status);
            }
        }
        
        sender.sendMessage("§7Total: §f" + types.size() + " §7| §a✓§7 = auto-protected");
        
        if (totalPages > 1 && sender instanceof Player) {
            TextComponent nav = new TextComponent("");
            if (page > 1) {
                TextComponent prev = new TextComponent("§a[« Prev] ");
                prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sg listall " + (page - 1)));
                nav.addExtra(prev);
            }
            nav.addExtra(new TextComponent("§7Page " + page + "/" + totalPages + " "));
            if (page < totalPages) {
                TextComponent next = new TextComponent("§a[Next »]");
                next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sg listall " + (page + 1)));
                nav.addExtra(next);
            }
            ((Player) sender).spigot().sendMessage(nav);
        }
        
        return true;
    }
    
    private boolean cmdInfo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        
        Player player = (Player) sender;
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        
        player.sendMessage("§6§l=== Structure Info ===");
        player.sendMessage("§7Location: §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        player.sendMessage("§7Chunk: §f" + chunkX + ", " + chunkZ);
        
        // Debug: Check if reflection is still initialized
        plugin.getConfigManager().debug("cmdInfo: checking structures near chunk " + chunkX + "," + chunkZ);
        
        // Check structures in current AND neighboring chunks (3x3 area)
        List<StructureFinder.StructureResult> allStructures = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<StructureFinder.StructureResult> structures = 
                    plugin.getStructureFinder().getStructuresInChunk(player.getWorld(), chunkX + dx, chunkZ + dz);
                allStructures.addAll(structures);
            }
        }
        
        plugin.getConfigManager().debug("cmdInfo: found " + allStructures.size() + " structures in 3x3 chunk area");
        
        if (!allStructures.isEmpty()) {
            player.sendMessage("§7Structures in chunk:");
            for (StructureFinder.StructureResult s : allStructures) {
                plugin.getConfigManager().debug("cmdInfo: structure " + s.structureType + " at " + s.x + "," + s.z);
                boolean isProtected = plugin.getDatabase().isStructureProtected(
                    player.getWorld().getName(), s.structureType, s.x, s.z);
                String status = isProtected ? " §a(protected)" : " §7(unprotected)";
                
                // Check if there's a matching rule
                ConfigManager.ProtectionRule rule = findMatchingRule(s.structureType);
                if (rule != null && !isProtected) {
                    status = " §7(unprotected)";
                    // Offer to protect it now
                    player.sendMessage("  §e" + s.structureType + status);
                    
                    // Create clickable protection message
                    TextComponent protectLink = new TextComponent("    §a[Click to Protect]");
                    protectLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, 
                        "/sg protect " + s.structureType));
                    protectLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                        new ComponentBuilder("§aProtect this structure").create()));
                    player.spigot().sendMessage(protectLink);
                } else {
                    player.sendMessage("  §e" + s.structureType + status);
                }
            }
        } else {
            player.sendMessage("§7No structure origins in this chunk.");
        }
        
        // Check nearest in database
        StructureDatabase.StructureInfo nearest = plugin.getDatabase().getNearestStructure(
            player.getWorld().getName(), loc.getBlockX(), loc.getBlockZ(), 96);
        
        if (nearest != null) {
            double dist = Math.sqrt(Math.pow(nearest.x - loc.getX(), 2) + Math.pow(nearest.z - loc.getZ(), 2));
            player.sendMessage("§7Nearest protected: §e" + nearest.type + " §7(" + String.format("%.0f", dist) + " blocks)");
            if (nearest.hasRegion) {
                player.sendMessage("§7Region: §f" + nearest.regionId);
            }
        }
        
        return true;
    }
    
    // ========================================================================
    // RULE MANAGEMENT (Config-based auto-protection)
    // ========================================================================
    
    /**
     * Add a structure pattern to config AND enable it.
     * This is the primary way to start protecting a structure type.
     */
    private boolean cmdProtect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sg protect <pattern> [radius] [ymin] [ymax]");
            sender.sendMessage("§7Examples:");
            sender.sendMessage("§7  /sg protect village §8(matches *:*village* in any namespace)");
            sender.sendMessage("§7  /sg protect minecraft:village §8(exact match)");
            sender.sendMessage("§7  /sg protect cobblemon:*_gym 64");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        
        // If no namespace provided, make it a wildcard pattern that matches any namespace
        // e.g., "brock" becomes "*:*brock*" to match "cobbleverse:brock"
        if (!pattern.contains(":") && !pattern.equals("*")) {
            pattern = "*:*" + pattern + "*";
            sender.sendMessage("§7Using wildcard pattern: §e" + pattern);
        }
        
        int radius = plugin.getConfigManager().getDefaultRadius();
        int yMin = plugin.getConfigManager().getDefaultYMin();
        int yMax = plugin.getConfigManager().getDefaultYMax();
        
        if (args.length >= 3) {
            try { radius = Integer.parseInt(args[2]); } 
            catch (NumberFormatException e) { sender.sendMessage("§cInvalid radius."); return true; }
        }
        if (args.length >= 4) {
            try { yMin = Integer.parseInt(args[3]); } 
            catch (NumberFormatException e) { sender.sendMessage("§cInvalid y-min."); return true; }
        }
        if (args.length >= 5) {
            try { yMax = Integer.parseInt(args[4]); } 
            catch (NumberFormatException e) { sender.sendMessage("§cInvalid y-max."); return true; }
        }
        
        // Create new rule (or update existing)
        ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(pattern);
        boolean isNew = (rule == null);
        
        if (rule == null) {
            rule = new ConfigManager.ProtectionRule();
            rule.pattern = pattern;
            rule.flags = new HashMap<>(plugin.getConfigManager().getDefaultFlags());
        }
        
        rule.enabled = true;
        rule.radius = radius;
        rule.yMin = yMin;
        rule.yMax = yMax;
        
        plugin.getConfigManager().addProtectionRule(rule);
        
        if (isNew) {
            sender.sendMessage("§a✓ Added protection rule: §e" + pattern);
        } else {
            sender.sendMessage("§a✓ Updated protection rule: §e" + pattern);
        }
        sender.sendMessage("§7Radius: " + radius + " | Y: " + yMin + " to " + yMax);
        
        // Retroactively protect structures already in database
        final String finalPattern = pattern;
        final ConfigManager.ProtectionRule finalRule = rule;
        int created = protectExistingStructures(finalPattern, finalRule);
        
        // If player is standing near a matching structure, protect it immediately
        if (sender instanceof Player && plugin.getRegionManager() != null 
                && plugin.getRegionManager().isWorldGuardAvailable()) {
            Player player = (Player) sender;
            int chunkX = player.getLocation().getBlockX() >> 4;
            int chunkZ = player.getLocation().getBlockZ() >> 4;
            
            // Scan nearby chunks for matching structures
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    List<StructureFinder.StructureResult> nearby = 
                        plugin.getStructureFinder().getStructuresInChunk(player.getWorld(), chunkX + dx, chunkZ + dz);
                    
                    for (StructureFinder.StructureResult s : nearby) {
                        if (matchesPattern(s.structureType, finalPattern)) {
                            // Add to database (if not exists)
                            plugin.getDatabase().addStructure(
                                player.getWorld().getName(), s.structureType, s.x, s.z);
                            
                            // Create StructureInfo and region
                            StructureDatabase.StructureInfo info = new StructureDatabase.StructureInfo(
                                player.getWorld().getName(), s.structureType, s.x, s.z, false, null);
                            
                            String regionId = plugin.getRegionManager().createRegionWithFlags(
                                info, finalRule.radius, finalRule.yMin, finalRule.yMax, finalRule.flags);
                            
                            if (regionId != null) {
                                plugin.getDatabase().setRegionId(
                                    player.getWorld().getName(), s.structureType, s.x, s.z, regionId);
                                created++;
                                sender.sendMessage("§a✓ Protected nearby: §e" + s.structureType + " §7at " + s.x + ", " + s.z);
                            }
                        }
                    }
                }
            }
        }
        
        if (created > 0) {
            sender.sendMessage("§a✓ Created §e" + created + "§a region(s) total.");
        } else {
            sender.sendMessage("§7Structures will be auto-protected when chunks load.");
        }
        
        return true;
    }
    
    /**
     * Create WorldGuard regions for structures already in the database.
     * Called when a protection rule is added/enabled.
     */
    private int protectExistingStructures(String pattern, ConfigManager.ProtectionRule rule) {
        int created = 0;
        
        // Get all structures matching this pattern from the database
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getUnprotectedStructures(pattern);
        
        for (StructureDatabase.StructureInfo info : structures) {
            // Check if this structure matches the pattern
            if (matchesPattern(info.type, pattern)) {
                String regionId = plugin.getRegionManager().createRegionWithFlags(
                    info, rule.radius, rule.yMin, rule.yMax, rule.flags);
                if (regionId != null) {
                    created++;
                }
            }
        }
        
        return created;
    }
    
    /**
     * Check if a structure type matches a pattern.
     */
    private boolean matchesPattern(String structureType, String pattern) {
        if (pattern.equals("*")) return true;
        if (pattern.contains("*")) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            return structureType.matches(regex);
        }
        return pattern.equals(structureType);
    }
    
    /**
     * Find a protection rule that matches a structure type.
     */
    private ConfigManager.ProtectionRule findMatchingRule(String structureType) {
        for (Map.Entry<String, ConfigManager.ProtectionRule> entry : 
                plugin.getConfigManager().getProtectionRules().entrySet()) {
            if (matchesPattern(structureType, entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    /**
     * Remove a structure pattern from config entirely.
     * Optionally clears existing WorldGuard regions with --clear flag.
     */
    private boolean cmdUnprotect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sg unprotect <pattern> [--clear]");
            sender.sendMessage("§7Removes the rule from config.");
            sender.sendMessage("§7Add §e--clear§7 to also remove existing WorldGuard regions.");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        boolean clearRegions = false;
        
        // Check for --clear flag
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--clear") || args[i].equalsIgnoreCase("-c")) {
                clearRegions = true;
            }
        }
        
        // Use wildcard pattern if no namespace provided
        if (!pattern.contains(":") && !pattern.equals("*")) {
            pattern = "*:*" + pattern + "*";
        }
        
        boolean removed = plugin.getConfigManager().removeProtectionRule(pattern);
        
        if (!removed) {
            sender.sendMessage("§cNo rule found for: " + pattern);
            sender.sendMessage("§7Use /sg rules to see all rules.");
            return true;
        }
        
        sender.sendMessage("§a✓ Removed protection rule: §e" + pattern);
        
        if (clearRegions) {
            int regionCount = plugin.getRegionManager().clearRegions(pattern);
            sender.sendMessage("§7Cleared §e" + regionCount + "§7 WorldGuard regions.");
        } else {
            sender.sendMessage("§7Existing regions were NOT removed.");
            sender.sendMessage("§7Use §e/sg clearregions " + pattern + "§7 to remove them.");
        }
        
        return true;
    }
    
    private boolean cmdEnable(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sg enable <pattern> [radius] [ymin] [ymax]");
            sender.sendMessage("§7Examples:");
            sender.sendMessage("§7  /sg enable minecraft:village");
            sender.sendMessage("§7  /sg enable minecraft:* 48 -64 320");
            sender.sendMessage("§7  /sg enable cobblemon:*_gym 64 -64 320");
            sender.sendMessage("§7  /sg enable * §8(protect ALL structures)");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        int radius = plugin.getConfigManager().getDefaultRadius();
        int yMin = plugin.getConfigManager().getDefaultYMin();
        int yMax = plugin.getConfigManager().getDefaultYMax();
        
        if (args.length >= 3) {
            try { radius = Integer.parseInt(args[2]); } 
            catch (NumberFormatException e) { sender.sendMessage("§cInvalid radius."); return true; }
        }
        if (args.length >= 4) {
            try { yMin = Integer.parseInt(args[3]); } 
            catch (NumberFormatException e) { sender.sendMessage("§cInvalid y-min."); return true; }
        }
        if (args.length >= 5) {
            try { yMax = Integer.parseInt(args[4]); } 
            catch (NumberFormatException e) { sender.sendMessage("§cInvalid y-max."); return true; }
        }
        
        // Get or create rule
        ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(pattern);
        if (rule == null) {
            rule = new ConfigManager.ProtectionRule();
            rule.pattern = pattern;
            rule.flags = new HashMap<>(plugin.getConfigManager().getDefaultFlags());
        }
        
        rule.enabled = true;
        rule.radius = radius;
        rule.yMin = yMin;
        rule.yMax = yMax;
        
        plugin.getConfigManager().addProtectionRule(rule);
        
        sender.sendMessage("§a✓ Enabled protection rule: §e" + pattern);
        sender.sendMessage("§7Radius: " + radius + " | Y: " + yMin + " to " + yMax);
        sender.sendMessage("§7New chunks with matching structures will be auto-protected.");
        sender.sendMessage("§7Use §e/sg flag " + pattern + " <flag> <value>§7 to set flags.");
        
        return true;
    }
    
    private boolean cmdDisable(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sg disable <pattern>");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        
        ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(pattern);
        if (rule == null) {
            sender.sendMessage("§cNo rule found for: " + pattern);
            sender.sendMessage("§7Use /sg rules to see all rules.");
            return true;
        }
        
        rule.enabled = false;
        plugin.getConfigManager().addProtectionRule(rule);
        
        sender.sendMessage("§a✓ Disabled protection rule: §e" + pattern);
        sender.sendMessage("§7Existing regions are not affected.");
        sender.sendMessage("§7Use §e/sg clearregions " + pattern + "§7 to remove them.");
        sender.sendMessage("§7Use §e/sg enable " + pattern + "§7 to re-enable.");
        
        return true;
    }
    
    private boolean cmdRules(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        Map<String, ConfigManager.ProtectionRule> rules = plugin.getConfigManager().getProtectionRules();
        
        sender.sendMessage("§6§l=== Protection Rules ===");
        
        if (rules.isEmpty()) {
            sender.sendMessage("§7No rules configured.");
            sender.sendMessage("§7Use §e/sg enable <pattern>§7 to add one.");
            return true;
        }
        
        for (ConfigManager.ProtectionRule rule : rules.values()) {
            String status = rule.enabled ? "§a●" : "§c○";
            sender.sendMessage(status + " §e" + rule.pattern);
            sender.sendMessage("  §7Radius: §f" + rule.radius + " §7| Y: §f" + rule.yMin + "§7 to §f" + rule.yMax);
            
            if (!rule.flags.isEmpty()) {
                StringBuilder flagStr = new StringBuilder();
                int count = 0;
                for (Map.Entry<String, String> flag : rule.flags.entrySet()) {
                    if (count++ > 0) flagStr.append(", ");
                    if (count > 3) { flagStr.append("..."); break; }
                    flagStr.append(flag.getKey()).append("=").append(flag.getValue());
                }
                sender.sendMessage("  §7Flags: §f" + flagStr);
            }
        }
        
        sender.sendMessage("§7Legend: §a● §7enabled §c○ §7disabled");
        
        return true;
    }
    
    // ========================================================================
    // FLAGS (Works on both rules AND existing regions)
    // ========================================================================
    
    private boolean cmdFlag(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /sg flag <pattern> <flag> <value>");
            sender.sendMessage("§7This sets flags on:");
            sender.sendMessage("§7  1. The protection RULE (for future regions)");
            sender.sendMessage("§7  2. All existing REGIONS matching the pattern");
            sender.sendMessage("§7Examples:");
            sender.sendMessage("§7  /sg flag minecraft:village pvp deny");
            sender.sendMessage("§7  /sg flag minecraft:* block-break deny");
            sender.sendMessage("§7  /sg flag gym greeting \"Welcome to the Gym!\"");
            sender.sendMessage("§7Values: allow, deny, none, or text for string flags");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        String flagName = args[2].toLowerCase();
        String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        
        // Remove quotes if present
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        
        int updatedRules = 0;
        int updatedRegions = 0;
        
        // 1. Update protection rule(s)
        ConfigManager.ProtectionRule rule = plugin.getConfigManager().getProtectionRule(pattern);
        if (rule != null) {
            if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("remove")) {
                rule.flags.remove(flagName);
            } else {
                rule.flags.put(flagName, value);
            }
            plugin.getConfigManager().addProtectionRule(rule);
            updatedRules = 1;
        }
        
        // 2. Update existing WorldGuard regions
        if (plugin.getRegionManager().isWorldGuardAvailable()) {
            updatedRegions = plugin.getRegionManager().setFlag(pattern, flagName, value);
        }
        
        if (updatedRules > 0 || updatedRegions > 0) {
            sender.sendMessage("§a✓ Set §e" + flagName + " = " + value);
            if (updatedRules > 0) {
                sender.sendMessage("§7Updated rule: §e" + pattern);
            }
            if (updatedRegions > 0) {
                sender.sendMessage("§7Updated §e" + updatedRegions + "§7 existing regions");
            }
        } else {
            sender.sendMessage("§cNo matching rule or regions found for: " + pattern);
        }
        
        return true;
    }
    
    // ========================================================================
    // REGION MANAGEMENT
    // ========================================================================
    
    private boolean cmdClearRegions(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sg clearregions <pattern> [world]");
            sender.sendMessage("§7Examples:");
            sender.sendMessage("§7  /sg clearregions minecraft:village");
            sender.sendMessage("§7  /sg clearregions * §8(remove ALL sg_ regions)");
            return true;
        }
        
        String pattern = args[1];
        String worldName = args.length >= 3 ? args[2] : null;
        int removed;
        
        if (pattern.equals("*")) {
            if (worldName != null) {
                removed = plugin.getRegionManager().clearAllStructureGuardRegionsInWorld(worldName);
                sender.sendMessage("§a✓ Removed " + removed + " regions from " + worldName);
            } else {
                removed = plugin.getRegionManager().clearAllStructureGuardRegions();
                plugin.getDatabase().reset();
                sender.sendMessage("§a✓ Removed " + removed + " regions from all worlds");
                sender.sendMessage("§7Database has been reset.");
            }
        } else {
            if (worldName != null) {
                removed = plugin.getRegionManager().clearRegionsInWorld(pattern.toLowerCase(), worldName);
            } else {
                removed = plugin.getRegionManager().clearRegions(pattern.toLowerCase());
            }
            sender.sendMessage("§a✓ Removed " + removed + " regions matching '" + pattern + "'");
        }
        
        return true;
    }
    
    private boolean cmdAddOwner(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /sg addowner <pattern> <player|g:group>");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        String target = args[2];
        
        int updated = plugin.getRegionManager().addOwner(pattern, target);
        
        if (updated > 0) {
            sender.sendMessage("§a✓ Added owner '" + target + "' to " + updated + " regions.");
        } else {
            sender.sendMessage("§cNo regions matching '" + pattern + "' found.");
        }
        
        return true;
    }
    
    private boolean cmdRemoveOwner(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /sg removeowner <pattern> <player|g:group>");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        String target = args[2];
        
        int updated = plugin.getRegionManager().removeOwner(pattern, target);
        
        if (updated > 0) {
            sender.sendMessage("§a✓ Removed owner '" + target + "' from " + updated + " regions.");
        } else {
            sender.sendMessage("§cNo matching regions or owner not present.");
        }
        
        return true;
    }
    
    private boolean cmdAddMember(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /sg addmember <pattern> <player|g:group>");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        String target = args[2];
        
        int updated = plugin.getRegionManager().addMember(pattern, target);
        
        if (updated > 0) {
            sender.sendMessage("§a✓ Added member '" + target + "' to " + updated + " regions.");
        } else {
            sender.sendMessage("§cNo regions matching '" + pattern + "' found.");
        }
        
        return true;
    }
    
    private boolean cmdRemoveMember(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /sg removemember <pattern> <player|g:group>");
            return true;
        }
        
        String pattern = args[1].toLowerCase();
        String target = args[2];
        
        int updated = plugin.getRegionManager().removeMember(pattern, target);
        
        if (updated > 0) {
            sender.sendMessage("§a✓ Removed member '" + target + "' from " + updated + " regions.");
        } else {
            sender.sendMessage("§cNo matching regions or member not present.");
        }
        
        return true;
    }
    
    // ========================================================================
    // UTILITY COMMANDS
    // ========================================================================
    
    private boolean cmdList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        String pattern = args.length >= 2 ? args[1].toLowerCase() : "*";
        int page = 1;
        if (args.length >= 3) {
            try { page = Integer.parseInt(args[2]); } catch (NumberFormatException e) { page = 1; }
        }
        
        List<StructureDatabase.StructureInfo> structures = plugin.getDatabase().getStructures(pattern);
        
        if (structures.isEmpty()) {
            sender.sendMessage("§7No structures matching '" + pattern + "' in database.");
            sender.sendMessage("§7Structures are added when chunks load with enabled protection rules.");
            return true;
        }
        
        int perPage = 10;
        int totalPages = (structures.size() + perPage - 1) / perPage;
        page = Math.max(1, Math.min(page, totalPages));
        
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, structures.size());
        
        sender.sendMessage("§6§l=== Protected Structures [" + page + "/" + totalPages + "] ===");
        
        for (int i = start; i < end; i++) {
            StructureDatabase.StructureInfo info = structures.get(i);
            String status = info.hasRegion ? "§a✓" : "§7○";
            
            if (sender instanceof Player) {
                TextComponent line = new TextComponent(status + " §f" + info.type + " §7at §e" + info.x + ", " + info.z);
                line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/minecraft:tp @s " + info.x + " 100 " + info.z));
                line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("§eClick to teleport\n§7" + info.world).create()));
                ((Player) sender).spigot().sendMessage(line);
            } else {
                sender.sendMessage(status + " §f" + info.type + " §7at §e" + info.x + ", " + info.z);
            }
        }
        
        sender.sendMessage("§7Total: " + structures.size() + " | §a✓§7 = has region");
        
        return true;
    }
    
    private boolean cmdStatus(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        sender.sendMessage("§6§l=== StructureGuard Status ===");
        
        // WorldGuard
        boolean wgAvailable = plugin.getRegionManager().isWorldGuardAvailable();
        sender.sendMessage("§7WorldGuard: " + (wgAvailable ? "§aAvailable" : "§cNot found"));
        
        // Protection rules
        Map<String, ConfigManager.ProtectionRule> rules = plugin.getConfigManager().getProtectionRules();
        int enabledRules = (int) rules.values().stream().filter(r -> r.enabled).count();
        sender.sendMessage("§7Protection Rules: §f" + enabledRules + "§7 enabled / §f" + rules.size() + "§7 total");
        
        // Database
        int total = plugin.getDatabase().getTotalCount();
        int protectedCount = plugin.getDatabase().getProtectedCount();
        sender.sendMessage("§7Database: §f" + total + "§7 structures (§a" + protectedCount + "§7 protected)");
        
        // Chunk listener
        ChunkLoadListener listener = plugin.getChunkLoadListener();
        if (listener != null) {
            sender.sendMessage("§7On-Demand: §aActive §7(" + listener.getProcessedChunkCount() + " chunks processed)");
        } else {
            sender.sendMessage("§7On-Demand: §cInactive");
        }
        
        // Debug mode
        sender.sendMessage("§7Debug: " + (plugin.getConfigManager().isDebugMode() ? "§aON" : "§7off"));
        
        // Structure registry test
        List<String> types = plugin.getStructureFinder().getAllStructureTypes();
        sender.sendMessage("§7Registry: §f" + types.size() + "§7 structure types");
        
        return true;
    }
    
    private boolean cmdReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        plugin.reload();
        
        // Clear chunk listener cache so it reprocesses
        ChunkLoadListener listener = plugin.getChunkLoadListener();
        if (listener != null) {
            listener.clearCache();
        }
        
        sender.sendMessage("§a✓ Configuration reloaded.");
        
        return true;
    }
    
    private boolean cmdDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("structureguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        
        boolean current = plugin.getConfigManager().isDebugMode();
        plugin.getConfigManager().setDebugMode(!current);
        
        sender.sendMessage("§7Debug mode: " + (!current ? "§aON" : "§cOFF"));
        if (!current) {
            sender.sendMessage("§7Check console for detailed structure detection logs.");
        }
        
        return true;
    }
    
    // ========================================================================
    // TAB COMPLETION
    // ========================================================================
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.addAll(Arrays.asList(
                "find", "listall", "info",
                "protect", "unprotect", "enable", "disable", "rules",
                "flag", "clearregions", "addowner", "removeowner", "addmember", "removemember",
                "list", "status", "reload", "debug"
            ));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            
            switch (sub) {
                case "find":
                    completions.addAll(plugin.getStructureFinder().getAllStructureTypes());
                    break;
                case "protect":
                case "enable":
                    completions.addAll(plugin.getStructureFinder().getAllStructureTypes());
                    // Add wildcard patterns for namespaces that actually exist
                    completions.add("*");
                    Set<String> namespaces = new HashSet<>();
                    for (String type : plugin.getStructureFinder().getAllStructureTypes()) {
                        if (type.contains(":")) {
                            namespaces.add(type.substring(0, type.indexOf(":")));
                        }
                    }
                    for (String ns : namespaces) {
                        completions.add(ns + ":*");
                    }
                    break;
                case "unprotect":
                case "disable":
                    completions.addAll(plugin.getConfigManager().getProtectionRules().keySet());
                    break;
                case "flag":
                case "clearregions":
                case "addowner":
                case "removeowner":
                case "addmember":
                case "removemember":
                case "list":
                    completions.add("*");
                    completions.addAll(plugin.getConfigManager().getProtectionRules().keySet());
                    completions.addAll(plugin.getDatabase().getStructureTypes());
                    break;
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            
            switch (sub) {
                case "protect":
                case "enable":
                    // Show descriptive hint instead of just numbers
                    completions.add("<radius>");
                    break;
                case "unprotect":
                    completions.add("--clear");
                    break;
                case "flag":
                    // Get all available WorldGuard flags dynamically (includes Extra Flags if installed)
                    completions.addAll(plugin.getRegionManager().getAvailableFlags());
                    break;
                case "clearregions":
                    completions.add("*");
                    for (World w : Bukkit.getWorlds()) {
                        completions.add(w.getName());
                    }
                    break;
                case "addowner":
                case "removeowner":
                case "addmember":
                case "removemember":
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        completions.add(p.getName());
                    }
                    completions.add("g:");
                    break;
            }
        } else if (args.length == 4) {
            String sub = args[0].toLowerCase();
            
            if (sub.equals("protect") || sub.equals("enable")) {
                completions.add("<ymin>");
            } else if (sub.equals("flag")) {
                completions.addAll(Arrays.asList("allow", "deny", "none"));
            }
        } else if (args.length == 5) {
            if (args[0].equalsIgnoreCase("protect") || args[0].equalsIgnoreCase("enable")) {
                completions.add("<ymax>");
            }
        }
        
        String current = args[args.length - 1].toLowerCase();
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(current))
            .distinct()
            .collect(Collectors.toList());
    }
}
