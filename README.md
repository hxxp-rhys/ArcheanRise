# Archean Rise

A server-first terrain-generation mod for **Minecraft 1.21.1** (Fabric **and** NeoForge — every
release ships both jars from one build).

## Goals (in priority order)

1. **Easy dedicated-server deployment** — drop the jar in `mods/`, set `level-type=archean_rise:archean_rise` in `server.properties`, start.
2. **Structure-mod compatibility** — vanilla structure placement machinery is preserved; see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).
3. **Biome add-on compatibility** — see [limitations/mod-compatibility.md](limitations/mod-compatibility.md).
4. **Extended world range** — Y **−256 to +768** (height 1024), mountain ceiling 708, deepest seafloor −128.
5. **Multi-scale cave systems** — small to massive, depth-spanning, mostly realistic, with rare explorable features (real + fantasy styling).
6. **Diverse surface terrain** with natural biome transitions.
7. **Safe, documented config** — `config/archean_rise.json` (runtime settings only; terrain shape is seed-baked and validated so users can't break goals 1–6).


## Status — v0.3.0 static world

- [x] Buildable dual-loader project (Fabric loader 0.19.3 + Fabric API, NeoForge subproject; Loom 1.17 / MDG, Mojang mappings, Java 21) — one `gradlew build` emits both jars into `.build\`
- [x] **The static world** `archean_rise:archean_rise` (v0.3.0 — the tier system was removed): Y −256..768, mountain ceiling 708, deepest seafloor −128, sea level 63; relief 3.32×, landform wavelength 6.64×, biome size 12× on the real-scale four-curve model (the 18×-land two-field blend was removed 2026-07-10 — it teleported temperature bands near coasts); caves/ores/overhang detail stay vanilla-scale (floating-block invariants). Geometry is baked at world creation — see [limitations/world-height.md](limitations/world-height.md). Pre-0.3.0 tier/legacy worlds need mod ≤0.2.26.
- [x] Runtime config with validation + inline docs
- [x] Minimal built-in pregenerator: `/archeanrise pregen start <radiusChunks> | stop | status`, rate-limited, pauses for players
- [ ] Custom terrain: taller mountain router, cave overhaul, biome-transition work (next milestone)

## Dev quick start

```powershell
.\gradlew build          # distributable jar lands in .build\
.\gradlew runServer      # dedicated-server dev run (accept eula.txt on first run)
.\gradlew runClient
```

Select the preset in singleplayer: **World Type → Archean Rise**. On a server: `level-type=archean_rise:archean_rise` **before first start** (world geometry is baked at creation and cannot be changed later).

**Installing/deploying the mod → [docs/INSTALLATION.md](docs/INSTALLATION.md)** (world-type selection, config semantics, startup verification, compat quick facts — kept current by documentation contract).

## Repository layout

- `src/main/java/dev/archeanrise/` — the mod: `config/`, `noise/`, `pregen/`, `rivers/`, `sitegrading/`, `worldgen/`, `mixin/`
- `src/main/resources/data/archean_rise/` — worldgen data (dimension type, noise settings, density functions, world preset)
- `neoforge/` — the NeoForge subproject; it compiles the shared `src/` (one `gradlew build` emits both loader jars)
- `tools/` — the worldgen-data generator (`generate-worldgen.mjs`, `pipeline-core.mjs`, `preview/rivers-r1.mjs`), which regenerates the JSON under `src/main/resources/data/`
- `docs/` — [INSTALLATION.md](docs/INSTALLATION.md) (install, config, verification) and [COMPATIBILITY.md](docs/COMPATIBILITY.md) (mod-compatibility verdicts)
- `limitations/` — user-facing feature limitations (world height, mod conflicts, biome borders, structure grading)

Portions of the worldgen data under `src/main/resources/data/archean_rise/worldgen/` are derived from
Minecraft's vanilla worldgen data, © Mojang Studios. Archean Rise is not affiliated with, endorsed by, or
associated with Mojang Studios or Microsoft.

## License

**All Rights Reserved** — Copyright (c) 2026 Archean Rise Team. See [LICENSE](LICENSE).

You may download and use the official releases in your own single-player worlds and on servers you
operate, and you may make videos/streams featuring the mod. You may **not** redistribute, mirror,
modify, fork, or include it in a modpack without prior written permission — ask via the project page.

The worldgen data under `src/main/resources/data/archean_rise/worldgen/` is derived from Minecraft's
vanilla worldgen data, © Mojang Studios; no ownership of those portions is claimed. Archean Rise is an
unofficial modification and is not approved by or associated with Mojang Studios or Microsoft.
