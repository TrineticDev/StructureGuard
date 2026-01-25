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

| Command | Description |
|---------|-------------|
| `/sg protect <pattern> [radius] [ymin] [ymax]` | Add protection rule |
| `/sg unprotect <pattern> [--clear]` | Remove rule (--clear removes regions) |
| `/sg enable <pattern>` | Enable disabled rule |
| `/sg disable <pattern>` | Disable rule (keeps in config) |
| `/sg rules` | List all rules |
| `/sg flag <pattern> <flag> <value>` | Set WorldGuard flags |
| `/sg listall` | Show all structure types |
| `/sg find <structure>` | Locate nearest structure |
| `/sg info` | Structure info at your location |
| `/sg status` | System status |
| `/sg reload` | Reload config and sync flags to existing regions |

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

## Permissions

| Permission | Description |
|------------|-------------|
| `structureguard.admin` | All commands |
| `structureguard.find` | Use /sg find |
| `structureguard.teleport` | Clickable teleport links |

## Requirements

- Minecraft 1.20.x - 1.21.x
- WorldGuard 7.0+
- Java 21+

## License

MIT License
