# Installation & Settings

For Minecraft **1.21.1**. Every release ships two jars — one for **Fabric**, one for **NeoForge**. Take
the one that matches your loader.

## 1. Install

Drop the jar into `mods/`.

- **On Fabric** you also need **Fabric API**. On NeoForge you don't need anything.
- **On a server:** just the server needs it. Players joining don't need the mod installed.
- **Singleplayer:** you need it, obviously.

## 2. Pick the world type — this is the step everyone forgets

The shape of a world is decided when it is created and **can never be changed afterwards**.

- **Singleplayer:** Create New World → *World* tab → *World Type* → cycle to **Archean Rise**
- **Server:** in `server.properties`, **before the very first start**:
  ```
  level-type=archean_rise:archean_rise
  ```

If you forget, you get an ordinary vanilla world and the mod does nothing. If the server has already
made a world, changing `level-type` won't help — delete the world folder, or set a new `level-name`.

**Adding Archean Rise to an existing world does not work.** You need a new one.

## 3. Check it worked

On startup the log should say:

```
Archean Rise static world active (Y -256 to 768, mountain cap 708, ...)
```

If instead it says *"Overworld is not an Archean Rise preset"*, the world type wasn't selected — go back
to step 2 with a fresh world.

If it ever says **"ARCHEAN RISE IS DISABLED IN THIS WORLD"**, another mod has taken over the world
generator. The log names it. Remove it and make a new world.

## 4. Settings

`config/archean_rise.json` — created on first launch, and refreshed on every launch (your values are
kept; new settings appear automatically).

**Nothing here changes an existing world's terrain.** Terrain is baked in at creation. These settings
affect how *new* land is generated and how the server behaves.

### Performance

| Setting | Default | What it does |
|---|---|---|
| `autoPregenEnabled` | `false` | Generate a chunk of world around spawn at startup, so players aren't waiting on it. Set `autoPregenRadiusBlocks` to how far. |
| `playerAheadEnabled` | `false` | Quietly generate land just beyond each player's view, so exploring feels smooth. |
| `pregenPauseWhenPlayersOnline` | `true` | Pause pregeneration while anyone is playing. Leave this on. |
| `regionFileCompression` | `"default"` | Set to `"lz4"` if the server stutters on "Saving world". Bigger files, much faster saves. |

You can also pregenerate by hand: `/archeanrise pregen start <radiusInChunks>` (and `stop`, `status`).

**This world is about 2.7× the volume of a vanilla one, so generating it costs more.** Installing
[C2ME](https://modrinth.com/mod/c2me-fabric) roughly doubles generation speed and is strongly
recommended for servers.

### Villages and structures

| Setting | Default | What it does |
|---|---|---|
| `siteGradingVeto` | `true` | Skip village sites that are too steep to build a walkable village on. This is **why villages are rarer**. Turn it off for more villages, some of them broken. |
| `scaleStructureSpacing` | `true` | Spread structures ~3× further apart, so they don't cluster like suburbs in a world this big. |
| `insetForeignStructures` | `true` | Reshape the ground around other mods' buildings so they sit into the hill rather than hanging off it. |
| `gateForeignInSnow` / `gateForeignInWater` | `true` | Skip other mods' buildings that would land on snow or deep water, where they look wrong. |

### Looks

| Setting | Default | What it does |
|---|---|---|
| `biomeBorderBlend` | `0` | Makes biome borders wander and interlock instead of running straight. Try `8`–`16`. It cannot fade biomes into each other — nothing in Minecraft can. |
| `riverFallsEnabled` | `true` | Waterfalls where rivers drop. |

## 5. Updating

Archean Rise is in **alpha**, and updates often change how the world is generated.

**Land you have already explored stays exactly as it is.** New land is generated with the new rules — so
at the edge of your explored area there can be a visible seam, or a hard step in the terrain.

If you want the full benefit of an update, **start a new world**. If you'd rather keep your base, just be
ready for the frontier to look a bit odd.

## 6. Recommended alongside

- **[C2ME](https://modrinth.com/mod/c2me-fabric)** — roughly doubles world-generation speed.
- **[Lithium](https://modrinth.com/mod/lithium)** — general server performance.
- **[Distant Horizons](https://modrinth.com/mod/distanthorizons)** — the world is big; you'll want to see
  it.

See **[COMPATIBILITY.md](COMPATIBILITY.md)** for what plays nicely with what.
