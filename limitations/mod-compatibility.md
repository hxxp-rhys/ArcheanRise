# Playing with other mods

**Full list: [docs/COMPATIBILITY.md](../docs/COMPATIBILITY.md).** This page is just the headlines.

Archean Rise only builds the world. It can only clash with mods that also build the world — terrain,
biome and structure mods. Performance mods, shaders, minimaps, machinery, QoL: all fine, none listed.

## ⚠️ On 0.3.13–0.3.15 with Terralith, Tectonic, Regions Unexplored or CTOV? Update.

Those mods share a library that made Archean Rise **stop recognising its own world**. Nothing crashed and
the terrain still looked right — but rivers, ore balance, structure spacing and terrain grading were all
quietly switched off. **Fixed in 0.3.16.** Existing chunks keep what they already have; only new land is
correct.

## Biome mods work now (0.3.16+)

Biomes O' Plenty, Oh The Biomes We've Gone, Nature's Spirit, YUNG's Cave Biomes and Underground Worlds
all generate their biomes on Archean Rise's terrain. Before 0.3.16 their biomes never appeared at all —
no crash, they just weren't there.

Terralith works too — but **install it as a mod, not as a datapack**. The datapack version replaces
Archean Rise's world entirely.

## Two things that don't work

- **Expanded Ecosphere** — the game won't start. It takes over the Overworld of *any* world type, so the
  two can never coexist. Archean Rise refuses to launch beside it, on purpose, rather than let it quietly
  replace your world.
- **Create-based structure mods on Fabric** — Create itself has no Fabric build for Minecraft 1.21.1, so
  none of them can run there. On NeoForge they're fine.

## If a mod misbehaves

Tell us, and include the mod, its version, your Archean Rise version and your loader. Most "incompatible"
mods turn out to be one small fix away.
