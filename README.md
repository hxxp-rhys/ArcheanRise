![banner](https://cdn.modrinth.com/data/cached_images/05983e23efc84a1ec7520dd716f06fe638f80cae.jpeg)

# Archean Rise

A terrain-generation mod for **Minecraft 1.21.1** that makes the world much bigger and much more
dramatic. Fabric and NeoForge.

> **Alpha.** It works, and it's fun to explore — but it's still changing. Expect the terrain to be
> different in the next release, and don't build your forever-base in it just yet.

## What you get

- **A world from Y −256 to 768.** Mountains up to 708 — real mountains you have to climb, not hills.
- **Big, coherent geography.** Continents, mountain chains that follow tectonic plates, and land that
  feels like it was shaped by something rather than sprinkled.
- **Rivers** that run downhill from the highlands to the sea.
- **Everything else stays Minecraft.** Same blocks, same mobs, same sea level, same structures — just on
  a landscape worth exploring.

Biome mods work too: **Biomes O' Plenty**, **Oh The Biomes We've Gone**, **Nature's Spirit**, **YUNG's
Cave Biomes**, **Underground Worlds** and **Terralith** all generate their biomes on Archean Rise's
terrain.

![A scenic mountain view.](https://cdn.modrinth.com/data/cached_images/c8060ee857ae7a0bc63b22b47d79a72ce6ec677f.jpeg)

## Install

1. Drop the jar for your loader into `mods/` (on Fabric, you also need **Fabric API**).
2. **Pick the world type when you create the world** — this is the step everyone forgets:
   - **Singleplayer:** Create New World → *World* → *World Type* → **Archean Rise**
   - **Server:** `level-type=archean_rise:archean_rise` in `server.properties`, **before the first start**

You can't add Archean Rise to a world that already exists — the world's shape is locked in when it's
created. You need a new one.

**Recommended:** [C2ME](https://modrinth.com/mod/c2me-fabric) (this world is big; C2ME roughly doubles
generation speed) and [Distant Horizons](https://modrinth.com/mod/distanthorizons) (you'll want to see
the mountains).

## Read these

- **[Things to know before you play](limitations/)** — short, honest notes. Deep rock is mostly
  cave-free, diamond is at the very bottom, villages are rarer.
- **[Mod compatibility](docs/COMPATIBILITY.md)** — what works, what doesn't.
- **[Installation & config](docs/INSTALLATION.md)** — every setting, in plain English.

## What's not here yet

**Deep caves.** All that extra depth below Y −48 is mostly solid rock right now. A proper cave system is
the next big piece of work.

---

## For developers

```
gradlew build      # emits both loader jars into .build/
```

- `src/main/java/dev/archeanrise/` — the mod
- `src/main/resources/data/archean_rise/` — the worldgen data (generated; see `tools/`)
- `neoforge/` — the NeoForge build; compiles the same `src/`
- `tools/generate-worldgen.mjs` — regenerates the worldgen data

## Licence

**All Rights Reserved** — Copyright (c) 2026 Archean Rise Team. See [LICENSE](LICENSE).

You may play with it, on your own worlds and your own servers, and make videos of it. You may **not**
redistribute it, modify it, or put it in a modpack without asking first.

Portions of the worldgen data are derived from Minecraft's vanilla worldgen data, © Mojang Studios; no
ownership of those portions is claimed. Archean Rise is an unofficial modification and is not approved by
or associated with Mojang Studios or Microsoft.
