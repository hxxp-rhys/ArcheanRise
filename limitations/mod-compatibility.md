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

## The YUNG's mods work (0.3.17)

All twelve of them, tested together: Better Dungeons, Strongholds, Mineshafts, Desert Temples, Jungle
Temples, Ocean Monuments, Witch Huts, Bridges, Extras, Better Caves, Cave Biomes and the API. Their
structures all build properly in Archean Rise's terrain.

Two things to know:

- **Better Mineshafts** only digs between y −55 and y 30. Archean Rise's lush caves sit deeper than that,
  so you'd never find a lush-cave mineshaft. One line in `config/bettermineshafts-*.toml` fixes it:
  `"Minimum y-coordinate" = -200`. The other twelve mineshaft types are fine either way.
- **Better Caves** roughly **triples** the open space underground — Archean Rise already digs big caves,
  and this adds more on top. It works, and it won't drain your rivers. Just know the world will be very
  hollow.

## Dungeons and Taverns + Towns and Towers work (0.3.17)

All ten Dungeons and Taverns mods and Towns and Towers, tested together. Every structure builds properly —
including the Ancient City, the Stronghold and the Illager Hideout, which sit deep underground and are
exactly the case that used to leave land floating in the sky. They don't.

Two things that aren't ours to fix:

- **Dungeons and Taverns' quest trader doesn't work.** The wandering-trader map and the quest-trader trade
  never trigger. It's a broken advancement inside the mod, and it happens in an ordinary world too.
- **Towns and Towers' "exclusive" villages need another biome mod** (Terralith, Biomes O' Plenty or
  Regions Unexplored). Without one, they never appear. It also moves vanilla pillager outposts to plains
  only, giving other biomes its own designs. Both are by design.

## More structure mods that work (0.3.17)

When Dungeons Arise (and Seven Seas) · Explorify · Structory · Structory: Towers · Philip's Ruins ·
Villages & Pillages · Repurposed Structures. All tested together — every structure builds properly,
including Philip's deep Lost Soul City and Structory's underground ruins.

Three things to know:

- **Villages & Pillages needs YUNG's API.** Without it the game won't start at all.
- **Repurposed Structures' structures are about twice as common as they should be.** Archean Rise spreads
  structures 3× further apart, but it can't do that to Repurposed Structures without risking that mod's own
  placement system, so it leaves it alone. Nothing breaks — there's just more of it. You can even it out in
  `config/cristellib/repurposed_structures/structure_placement_config.json5` by tripling `spacing` and
  `separation` on the **Overworld** entries only — leave the `_nether` and `_end` ones alone (Archean Rise
  doesn't change those dimensions), and leave the four `mineshafts_*` ones at `spacing: 1`. See
  [COMPATIBILITY.md](../docs/COMPATIBILITY.md) for the details.
- **When Dungeons Arise's "find Thornborn Towers" advancements don't work** — a bug in the mod, and it
  happens in an ordinary world too.

## Two things that don't work

- **Expanded Ecosphere** — the game won't start. It takes over the Overworld of *any* world type, so the
  two can never coexist. Archean Rise refuses to launch beside it, on purpose, rather than let it quietly
  replace your world.
- **Create-based structure mods on Fabric** — Create itself has no Fabric build for Minecraft 1.21.1, so
  none of them can run there. On NeoForge they're fine.

## If a mod misbehaves

Tell us, and include the mod, its version, your Archean Rise version and your loader. Most "incompatible"
mods turn out to be one small fix away.
