# TunnelMiner 

A [Meteor Client](https://meteorclient.com/) addon for Minecraft **1.21.10** that automatically digs a straight tunnel from your current position to a target XZ coordinate — with lava avoidance, fill-behind, pickaxe management, and live HUD overlays.

---

## Features

- **Coordinate-based tunneling** — set a target X and Z, the bot does the rest
- **X-first pathfinding** — mines along the X axis first, then the Z axis
- **Lava avoidance** — scans 5 blocks ahead, shifts sideways to detour around lava, then returns to the original line
- **Fill behind** — re-places the same block types behind you as you advance
- **Pickaxe management** — auto-equips the best pickaxe in your inventory; optionally disconnects if you run out
- **Shulker scanning** — can scan shulker box NBT to check for pickaxes (warns you to place the shulker to retrieve them)
- **Disconnect on server leave** — module automatically stops if you disconnect or get kicked
- **HUD: Distance** — shows blocks remaining, progress, and target coordinates
- **HUD: ETA** — shows estimated time to completion based on your current mining speed

---

## Preview

```
[TunnelMiner] Tunnel started → X:500 Z:500 (1000 blocks)
[TunnelMiner] Lava ahead — detouring!
[TunnelMiner] Reached target!
```

HUD display:
```
Blocks left: 743
Progress: 257 / 1000
Target: X500  Z500

ETA: 4m 12s
```

---

## Installation

### Requirements
- Minecraft **1.21.10** (Fabric)
- [Meteor Client](https://meteorclient.com/) for 1.21.10
- Java 21+

### Steps
1. Download the latest `tunnelminer-1.0.0.jar` from [Releases](../../releases)
2. Drop it into your `.minecraft/mods/` folder alongside Meteor Client
3. Launch Minecraft with Fabric
4. In-game: **Meteor** → **TunnelMiner** category → **Tunnel Miner**

---

## Usage

1. Stand at your starting position
2. Open the **Tunnel Miner** module settings
3. Set **target-x** and **target-z**
4. Enable the module
5. The bot will mine X first, then Z, filling behind itself as it goes

### Adding the HUDs
- Open **Meteor** → **HUD**
- Search for `tunnel-distance` and `tunnel-eta`
- Drag them onto your screen

---

## Settings

### General
| Setting | Default | Description |
|---|---|---|
| `target-x` | 0 | Target X coordinate |
| `target-z` | 0 | Target Z coordinate |
| `tunnel-height` | 2 | Height of the tunnel (1–4 blocks) |
| `fill-behind` | on | Re-place mined blocks behind you |
| `lava-avoidance` | on | Detect and detour around lava |
| `lava-detour-distance` | 3 | Blocks to shift sideways during lava detour |

### Shulker Box
| Setting | Default | Description |
|---|---|---|
| `use-shulkers` | off | Scan shulker boxes for pickaxes |
| `min-pickaxes` | 1 | Minimum pickaxes needed — disconnects if not met |

### Timing
| Setting | Default | Description |
|---|---|---|
| `breaks-per-tick` | 1 | Block break attempts per tick (1–5) |
| `places-per-tick` | 1 | Block place attempts per tick (1–5) |

---

## Building from Source

```bash
# Clone the repo
git clone https://github.com/yourname/TunnelMiner.git
cd TunnelMiner

# Build (downloads Gradle automatically)
./gradlew build          # Mac/Linux
gradlew.bat build        # Windows

# Output JAR
build/libs/tunnelminer-1.0.0.jar
```

### Requirements
- Java 21 JDK ([download](https://adoptium.net/))
- Internet connection (Gradle downloads dependencies automatically)

---

## Notes & Limitations

- **Movement** uses direct position teleportation per tick — works great in straight tunnels but won't handle falling or complex geometry
- **Shulker extraction** can only scan item NBT in your hand/inventory. To actually pull items out, place the shulker box and open it manually
- **Anti-cheat** — direct teleportation and instant block interactions may trigger server-side anti-cheat. Use only on servers where this is permitted
- **Fill-behind** only replaces blocks if you have the same block type in your inventory

---

## Compatibility

| Component | Version |
|---|---|
| Minecraft | 1.21.10 |
| Meteor Client | 1.21.10-43+ |
| Fabric Loader | 0.16.9+ |
| Java | 21+ |

---

## License

MIT — do whatever you want with it.
