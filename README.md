# StructureGuard

**Automatic WorldGuard protection for ANY structure — vanilla, modded, or datapack.**

## Features

- 🎯 **On-Demand Protection** — Structures protected when chunks load, no scanning required
- 🌍 **Universal Compatibility** — Paper, Spigot, Folia, NeoForge, Fabric hybrid servers
- 🎨 **Pattern Matching** — Protect `minecraft:*`, `cobblemon:*_gym`, or just `*` for everything
- ⚡ **Zero Lag** — Async detection, sync region creation
- 🔧 **Full WorldGuard Integration** — All flags supported, including Extra Flags

## Quick Start

```bash
# Install WorldGuard, drop StructureGuard in /plugins/, restart

# Protect all villages with 64-block radius
/sg protect minecraft:village 64

# Protect ALL structures
/sg protect * 48

# Set flags
/sg flag minecraft:village pvp deny

# Check status
/sg status
```

## Commands

### Discovery
| Command | Description |
|---------|-------------|
| `/sg listall` | Show all structure types in registry |
| `/sg find <structure>` | Locate nearest structure |
| `/sg info` | Structure info at your location |

### Protection Rules
| Command | Description |
|---------|-------------|
| `/sg protect <pattern> [radius] [ymin] [ymax]` | Add protection rule |
| `/sg unprotect <pattern> [--clear]` | Remove rule (--clear removes regions) |
| `/sg enable <pattern>` | Enable disabled rule |
| `/sg disable <pattern>` | Disable rule (keeps in config) |
| `/sg rules` | List all rules |

### Flags & Regions
| Command | Description |
|---------|-------------|
| `/sg flag <pattern> <flag> <value>` | Set WorldGuard flags on rules & regions |
| `/sg addowner <pattern> <player\|g:group>` | Add region owner |
| `/sg addmember <pattern> <player\|g:group>` | Add region member |
| `/sg removeowner <pattern> <player\|g:group>` | Remove region owner |
| `/sg removemember <pattern> <player\|g:group>` | Remove region member |
| `/sg clearregions <pattern> [world]` | Remove WorldGuard regions |
| `/sg resetworld <world>` | Clear all data for a world (for resets) |

### Utility
| Command | Description |
|---------|-------------|
| `/sg list <pattern>` | List protected structures |
| `/sg status` | System status |
| `/sg reload` | Reload config and sync flags to existing regions |
| `/sg debug` | Toggle debug mode |

## Pattern Examples

| Pattern | Matches |
|---------|---------|
| `minecraft:village` | Exactly villages |
| `minecraft:*` | All vanilla structures |
| `cobblemon:*_gym` | All Cobblemon gyms |
| `*` | Everything |

## Configuration

```yaml
# Default protection settings
default-radius: 48
default-y-min: -64
default-y-max: 320

# Worlds where protection is disabled (e.g., resource worlds)
disabled-worlds:
  - resource_world
  - mining_world

# Default flags for new regions
default-flags:
  use: allow
  interact: allow
  creeper-explosion: deny
  tnt: deny
  deny-message: "&cThis structure is protected!"

# Protection rules (managed via /sg protect)
protected-structures:
  minecraft_village:
    enabled: true
    radius: 64
    flags:
      block-break: deny
```

### Config Sync

Edit flags in `config.yml` then run `/sg reload` to apply changes to all existing regions. No need to recreate regions!

### Disabled Worlds

Add world names to `disabled-worlds` to completely skip structure protection in those worlds. Useful for:
- Resource worlds that reset periodically
- Creative/build worlds
- Mining dimensions

## Permissions

| Permission | Description |
|------------|-------------|
| `structureguard.admin` | All commands |
| `structureguard.find` | Use /sg find |
| `structureguard.listall` | Use /sg listall |
| `structureguard.teleport` | Clickable teleport links |

## Requirements

- Minecraft 1.20.x - 1.21.x
- WorldGuard 7.0+
- Java 21+

## License

MIT License
