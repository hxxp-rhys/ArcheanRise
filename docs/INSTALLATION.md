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

Everything lives in `config/archean_rise.json`. It's made on first launch and tidied up on every launch —
your changes are kept, and new settings from updates appear on their own.

**Nothing here changes a world you've already made.** The shape of the land is fixed when the world is
created; these settings only affect *new* land and how the server behaves. Most of them are already set
to sensible defaults — the ones you're most likely to touch are at the top of each list.

### Making the world generate faster

The world is big, so building it takes longer than usual. These help.

| Setting | Default | What it does |
|---|---|---|
| `autoPregenEnabled` | `false` | Build a patch of world around spawn the moment the server starts, so the first people to join aren't stuck waiting for terrain. |
| `autoPregenRadiusBlocks` | `32` | How far out that startup patch reaches, in blocks. Turn `autoPregenEnabled` on first. |
| `playerAheadEnabled` | `false` | Quietly build land just beyond what each player can see, so exploring stays smooth instead of stuttering at the edges. |
| `playerAheadChunks` | `8` | How far ahead of each player to build. |
| `pregenPauseWhenPlayersOnline` | `true` | Pause background world-building while anyone is playing, so it never steals performance from live players. Best left on. |
| `regionFileCompression` | `"default"` | If the game freezes for a moment on "Saving world", set this to `"lz4"` — saves get much faster, for slightly larger files. Mostly a singleplayer setting. |
| `pregenMaxInFlight` | `64` | How many pieces of world the pre-builder works on at once. Higher is faster but uses more memory while it runs. |
| `pregenSaveIntervalChunks` | `1024` | How often pre-built land is written to disk. This is what stops one giant "Saving world" freeze at the end. Fine as-is. |
| `pregenMaxRadiusChunks` | `1024` | The largest area the manual pregen command will accept, as a safety cap. |
| `pregenLogIntervalSeconds` | `10` | How often build progress is printed to the server log. |

You can also build the world by hand at any time: `/archeanrise pregen start <radius-in-chunks>` (and
`stop`, `status`). **Installing [C2ME](https://modrinth.com/mod/c2me-fabric) roughly doubles
generation speed** and is strongly recommended for servers.

### Villages and structures

Archean Rise tries to make villages sit properly on its steeper land instead of half-buried or clinging
to a cliff.

| Setting | Default | What it does |
|---|---|---|
| `siteGradingVeto` | `true` | Skip village spots too steep to build a walkable village on. This is **why villages are a bit rarer**. Turn it off for more villages, some of them broken. |
| `siteGradingVetoMaxRelief` | `32` | How much slope a village spot can have before it's skipped. Raise it for more villages on hillier ground. |
| `siteGradingWaterVetoDepth` | `32` | Skip village spots that hang out over water deeper than this. |
| `scaleStructureSpacing` | `true` | Spread all structures about 3× further apart, so they don't cluster like suburbs in a world this size. |
| `siteGradingEnabled` | `true` | The master switch for all of the village-fitting above. Off = plain vanilla placement. |
| `siteGradingCandidateSearch` | `true` | Let a village shuffle a short distance to a nicer nearby spot. |
| `siteGradingGradePad` | `true` | Reshape the ground around village buildings so they sit flush with the land. |
| `siteGradingFoundationFill` | `true` | Fill in small gaps left underneath village buildings. |
| `siteGradingExtraStructures` | `[]` | A list where you can name other mods' structures (e.g. `"somemod:fort"`, or a whole mod as `"somemod"`) to give them the same village-fitting treatment. |

### Other mods' structures

How Archean Rise blends buildings from other mods into its terrain.

| Setting | Default | What it does |
|---|---|---|
| `insetForeignStructures` | `true` | Cut other mods' buildings neatly into hillsides so they don't hang off a slope. |
| `insetForeignGrade` | `true` | Tidy the ground around those buildings — slope the cut face, fill under an overhang, and open a natural-looking mouth where a building tunnels into a hill. |
| `insetForeignBurialGate` | `true` | Leave structures that are *meant* to be underground (crypts, bunkers, cave ruins) alone, instead of digging the hill off them. Best left on. |
| `insetForeignBurialMargin` | `8` | How deep a structure has to be buried before it counts as "underground" and is left alone. |
| `gateForeignInSnow` | `true` | Skip other mods' buildings that would land on snow, where a generic building tends to look out of place. |
| `gateForeignInWater` | `true` | Skip other mods' buildings that would end up floating on open water. |
| `gateForeignInWaterDepth` | `8` | How deep the water has to be before a spot counts as "on water". The default keeps beaches and shallow swamps. |
| `insetForeignForceSurfaceStructures` | `[]` | Force-mark a structure (or a whole mod) as a *surface* build if Archean Rise guesses wrong. Normally empty. |
| `insetForeignForceBuriedStructures` | `[]` | Force-mark a structure (or a whole mod) as *underground* if Archean Rise guesses wrong. Normally empty. |

### The look of the world

| Setting | Default | What it does |
|---|---|---|
| `biomeBorderBlend` | `0` | Makes biome borders wander and interlock instead of running in straight lines. Try `8`–`16`. It can't fade one biome's colours into another — nothing in Minecraft can. |
| `riverFallsEnabled` | `true` | Adds waterfalls where rivers drop down a step. |
| `riverPoolFillEnabled` | `true` | Keeps river pools topped up with water where they'd otherwise sit half-empty. |
| `floatDespeckleEnabled` | `true` | Cleans up stray lumps of rock left floating in the sky. Turn it off only if you *want* floating rock (or you're adding your own sky islands). |
| `floatDespeckleMaxBlocks` | `2048` | The biggest floating lump to sweep away. Anything larger is treated as real terrain and kept. |
| `floatDespeckleMinY` | `63` | Only clean up floating rock above this height (roughly sea level and up). |

### Advanced — leave these alone unless you like to tinker

These are fine-tuning knobs and a still-in-development terrain feature. The defaults are the tested ones.

| Setting | Default | What it does |
|---|---|---|
| `insetForeignGradeReach` | `24` | How far out from a building the ground-tidying reaches. |
| `insetForeignOverhangMin` | `3` | How far a building has to jut out over a drop before support is filled in beneath it. |
| `insetForeignTunnelBase` | `2` | How much a tunnel mouth is widened where a building bores into a hill. |
| `siteGradingCutFill` | `false` | An experimental, more thorough way of levelling the ground for villages. Still in development — off by default. |
| `siteGradingCut` | `true`  | Fine-tuning for the experimental levelling above; only matters when `siteGradingCutFill` is on. |
| `siteGradingApronRampMax` | `32 | Fine-tuning for the experimental levelling above; only matters when `siteGradingCutFill` is on. |
| `siteGradingForeignHaloExtra` | `2` | Fine-tuning for the experimental levelling above; only matters when `siteGradingCutFill` is on. |

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
