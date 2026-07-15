# Mod Compatibility

Archean Rise changes how the world is built. So the **only** mods it can clash with are other mods that
also touch world generation — terrain, biome and structure mods.

**Everything else is fine.** Performance mods, shaders, minimaps, machinery, food, QoL — none of them
compete with Archean Rise, and none of them are listed here.

| | |
|:---:|---|
| ✅ | Works. Tested on that version. |
| ❌ | Doesn't work. Tested, and it failed. The note says how. |
| ⚠️ | Works, but there's a catch. Read the note first. |
| — | Not tested on that version. No promise either way. |

---

## Biome and terrain mods

| Mod | 0.3.17 | 0.3.18 | 0.3.19 | Notes |
|---|:---:|:---:|:---:|---|
| **Biomes O' Plenty** | — | — | ✅ | Works from 0.3.16. Before that its biomes never appeared at all. |
| **Oh The Biomes We've Gone** | — | — | ✅ | Needs CorgiLib + GeckoLib. Its biomes appear on Archean Rise terrain. |
| **Nature's Spirit** | — | — | ✅ | Needs TerraBlender. Its biomes appear. |
| **YUNG's Cave Biomes** | — | — | ✅ | **Needs YUNG's API** — it will not load without it. Both its cave biomes appear; Lost Caves only turns up under hot, dry land, so look in deserts. |
| **Underground Worlds** | — | — | ✅ | |
| **Atmospheric** | — | — | ✅ | Needs Blueprint. Its biomes appear. |
| **Environmental** | — | — | ✅ | Needs Blueprint. Its biomes appear. |
| **Oh The Trees You'll Grow** | — | — | ✅ | Changes how saplings grow; doesn't touch terrain. |
| **Vanilla Backport** | — | — | ✅ | Adds backported features; doesn't disturb Archean Rise's terrain. |
| **Terralith** | — | — | — | **Install the MOD, not the datapack.** The mod works — its biomes appear on Archean Rise's terrain. The standalone *datapack* version would replace Archean Rise's world entirely. |
| **Geophilic** | — | — | — | Worked on an older version. |
| **Regions Unexplored** | — | — | — | Needs re-testing. It used to switch Archean Rise off (see below); that's fixed, but the pairing hasn't been re-checked. |
| **Tectonic** | — | — | — | Its terrain has no effect (Archean Rise makes the land). But it moves the snow line about 128 blocks and lowers ocean monuments, in any world. |
| **BetterEnd / BetterNether / Incendium / Nullscape** | — | — | — | Fine. Archean Rise only changes the Overworld — the Nether and End are untouched. |
| **Expanded Ecosphere** | ❌ | — | — | **The game won't start.** It replaces the Overworld of *any* world type, so the two can never both work. Archean Rise refuses to launch beside it on purpose. |
| **terrain-diffusion-mc** | — | — | — | Not recommended — it downloads ~2.5 GB of models on first launch and misbehaves on custom world types. |

> **⚠️ If you played 0.3.13–0.3.15 with Terralith, Tectonic, Regions Unexplored or CTOV — please update.**
> Those mods share a library that made Archean Rise stop recognising its own world. Everything still
> *looked* right, but rivers, ore balance, structure spacing and terrain grading were all quietly switched
> off. Fixed in 0.3.16.

---

## Structure mods

| Mod | 0.3.17 | 0.3.18 | 0.3.19 | Notes |
|---|:---:|:---:|:---:|---|
| **YUNG's API** | — | — | ✅ | Required by every YUNG's mod below. |
| **YUNG's Better Dungeons** | — | — | ✅ | |
| **YUNG's Better Strongholds** | — | — | ✅ | |
| **YUNG's Better Desert Temples** | — | — | ✅ | |
| **YUNG's Better Jungle Temples** | — | — | ✅ | |
| **YUNG's Better Ocean Monuments** | — | — | ✅ | |
| **YUNG's Better Witch Huts** | — | — | ✅ | |
| **YUNG's Bridges** | — | — | ✅ | |
| **YUNG's Extras** | — | — | ✅ | |
| **YUNG's Better Mineshafts** | — | — | ⚠️ | Works, but one of its thirteen mineshaft types needs a one-line setting change. See below. |
| **YUNG's Better Caves** | — | — | ⚠️ | Works — but it makes the underground *very* hollow. See below. |
| **Dungeons and Taverns** (+ all 9 of its add-ons) | — | — | ✅ | Includes the Ancient City, Stronghold, Nether Fortress, Pillager Outpost and Swamp Hut overhauls and the Desert Temple, Jungle Temple, Ocean Monument and Woodland Mansion replacements. One small note below. |
| **Towns and Towers** | — | — | ✅ | One small note below. |
| **When Dungeons Arise** | — | — | ✅ | Its "find Thornborn Towers / fishing hut" advancements don't work — a bug in the mod itself, and it happens in an ordinary world too. |
| **When Dungeons Arise: Seven Seas** | — | — | ✅ | |
| **Explorify** | — | — | ✅ | |
| **Structory** | — | — | ✅ | |
| **Structory: Towers** | — | — | ✅ | |
| **Philip's Ruins** | — | — | ✅ | Its deep structures (Lost Soul City, Ancient Dungeon) build correctly. |
| **Villages & Pillages** | — | — | ✅ | **Needs YUNG's API** — without it the game won't start. |
| **Repurposed Structures** | — | — | ⚠️ | Works, but its structures are more common than Archean Rise intends. See below. |
| **ChoiceTheorem's Overhauled Village** | — | — | ✅ | **Needs Lithostitched** (which works with Archean Rise). With it installed, its villages generate cleanly. |
| **Luki's Ancient Cities · Crazy Chambers · Strongholds · Woodland Mansions · Grand Capitals** | ✅ | ✅ | ✅ | All five. The Ancient City and Trial Chambers are very deep and build correctly. |
| **AdoraBuild** | ✅ | ✅ | ✅ | Built for a slightly newer Minecraft, but loads and works fine. |
| **Better Archeology** | ✅ | ✅ | ✅ | |
| **The Lost Castle** | ✅ | ✅ | ✅ | |
| **Immersive Structures** | ✅ | ✅ | ✅ | |
| **Thun's Structures** | ✅ | ✅ | ✅ | |
| **Unnamed Desert** · **Unnamed Sea** | ✅ | ✅ | ✅ | |
| **Moog's Voyager** | — | — | ⚠️ | Works, with two catches — see below. |
| **Moog's Bountiful** | — | — | ⚠️ | Works, with two catches — see below. |
| **Moog's Soaring** | — | — | ⚠️ | Works, with two catches — see below. |
| **Moog's Temples Reimagined** | — | — | ⚠️ | Works, with two catches — see below. |
| **Moog's Mineshafts Reimagined** | — | — | ⚠️ | Works, with two catches — see below. |
| **Moog's Missing Villages** | — | — | ⚠️ | Works, with two catches — see below. |
| **Moog's Paths** | — | — | ✅ | |
| **Create: Let The Adventure Begin** | ✅ | ✅ | ✅ | NeoForge only, and Create must be installed obviously. |
| **Create: Structures Arise** | — | — | ✅ | Need Create installed. |
| **Create: Structures Overhaul** | — | — | ✅ | Need Create installed. |
| **Create: Wells** | — | — | ✅ | Need Create installed. |
| **Create: Sky Village** | — | — | ✅ | Need Create installed. |
| **Create: New Beginnings** | — | — | ✅ | Need Create installed. |
| **Create: Hangars** | — | — | ✅ | Needs Create: Aeronautics for working hangars (buildings generate regardless). |
| **Dynamic Village** | — | — | ✅ | Needs Create. |
| **Additional Structures** | ✅ | ✅ | ✅ | On 0.3.17 a few of its small decorations could leave a lump of land floating; **fixed in 0.3.18, and 0.3.19 also cleans up natural floating rock.** |
| **Explorations** | ✅ | ✅ | ✅ | On 0.3.17 a few of its small decorations could leave a lump of land floating; **fixed in 0.3.18.** |
| **Formations Overworld** | ❌ | ❌ | ❌ | **Cannot run on a server** — with or without Archean Rise. It needs **SuperMartijn642's Core Lib**; even with that installed it still crashes, because Formations loads a screen-editing GUI that doesn't exist on a server. Formations' own bug — the crash happens before Archean Rise is ever involved. It may be fine in singleplayer. |

All twelve YUNG's mods were tested together on 0.3.17. Every structure they add builds properly in
Archean Rise terrain, and none of them leave floating lumps of land behind.

### YUNG's Better Mineshafts — change one setting

Better Mineshafts only ever digs between **y −55 and y 30**. Archean Rise's world is far deeper than
vanilla's, and its lush caves sit *below* that line — so the lush-cave mineshaft can never appear. The
other twelve types are fine.

In `config/bettermineshafts-*.toml`, change:

```
"Minimum y-coordinate" = -200
```

Nothing else needs touching, and the twelve types that already worked carry on working.

### YUNG's Better Caves — a much emptier underground

Archean Rise already digs large cave systems. Better Caves adds its own on top, which roughly **triples**
the open space underground. It works correctly, and it will not drain your rivers or lakes — but the
world ends up very hollow. If that's not the game you want, leave it out.

### Dungeons and Taverns — the deep structures are safe

All ten Dungeons and Taverns mods were tested together on 0.3.17. The Ancient City, the Stronghold and
the Illager Hideout sit deep underground, which is exactly the situation that used to leave a lump of
land floating in the sky. **They don't.** Every one of them builds cleanly.

Two things worth knowing, neither of them ours to fix:

- Its **quest trader** doesn't work — the wandering-trader map and the quest-trader trade never trigger.
  That's a bug inside Dungeons and Taverns (a broken advancement) and it happens in an ordinary world
  too, with or without Archean Rise.
- Its **Nether** structures are unaffected either way. Archean Rise only changes the Overworld.

### Towns and Towers — two notes

- It moves **vanilla pillager outposts to plains only** and gives every other biome its own outpost
  design instead. That's how the mod is meant to work.
- Its "**exclusive**" villages (Classic, Rustic, Iberian and so on) only appear in biomes from *other*
  mods — Terralith, Biomes O' Plenty or Regions Unexplored. Without one of those installed you'll never
  see them. Again, by design.

### ✅ Additional Structures / Explorations — floating land, fixed in 0.3.18

On **0.3.17 and earlier**, these two could leave a lump of land hanging in the sky on steep mountainsides.
**That was our bug, not theirs**, and it is fixed in 0.3.18 — no config change needed, and nothing to do on
your side but update.

What went wrong: Archean Rise reshapes the ground around other mods' buildings so they sit into the hill
properly. Our mountains arch and overhang, and the tool that did the reshaping would cut clean through an
arch — taking out the rock that was holding the rest of it up. Both mods add tiny decorations (a bush, a
pile of logs, a well) as full structures, so they landed in that situation more often than most.

0.3.18 simply won't cut into rock that isn't standing on solid ground. If you played 0.3.17 with these mods,
land you have **already explored keeps whatever it has** — only newly generated land is built the right way.

### ✅ Floating rock in the mountains — cleaned up in 0.3.19

Separately from the mod issue above, Archean Rise's own mountains — which arch and overhang — could
occasionally leave a chunk of natural rock floating in the sky, **even with no other mods installed at all**.
0.3.19 sweeps these up as the world generates. It only ever removes rock that is genuinely detached and
floating; solid ground, overhangs and arches that are actually attached are left exactly as they were.

This is on by default. If you *want* the floating rock (or you're adding your own sky islands), set
`floatDespeckleEnabled` to `false` in `config/archean_rise.json`. As always, only newly generated land
changes; explored land keeps what it has.

### Repurposed Structures — its structures are too common

Archean Rise spreads structures about **3× further apart**, because the world is so much bigger. It can't do
that to Repurposed Structures: that mod places its structures with its own custom system, and Archean Rise
deliberately won't rewrite another mod's placement logic in case it breaks it.

The result is that RS villages, outposts and temples turn up roughly **twice as often** as everything else.
Nothing is broken — there's just more of it than intended.

If you'd rather it matched, RS lets you fix this yourself, in
`config/cristellib/repurposed_structures/structure_placement_config.json5`. **Multiply `spacing` and
`separation` by 3 — but only on the Overworld entries**, and mind the two exceptions:

- **Leave anything ending in `_nether` or `_end` alone.** Archean Rise only changes the Overworld, so
  spreading those out would just make them rarer for no reason.
- **Leave the four `mineshafts_*` entries at `spacing: 1`.** They're meant to be everywhere, and Archean
  Rise doesn't spread out vanilla mineshafts either.

That's 17 entries to change. So `villages_overworld` goes from spacing 50 / separation 25 to **150 / 75**.

### Moog's structure mods — install the right library, and two catches

All six Moog's structure mods (Voyager, Bountiful, Soaring, Temples Reimagined, Mineshafts Reimagined,
Missing Villages) share one small library, **Moog's Structure Lib**.

Two things to know:

- **Their structures come out more often than Archean Rise intends** — like Repurposed Structures above,
  they place with their own system that Archean Rise leaves alone. Lower the spacing in the Moog's config
  if you want them rarer.
- **One Voyager decoration (a small oak pond) can leave a small lump of land floating** on steep ground.
  That one's on our side, and a fix is on the way.

**Create-based structure mods:** on Fabric they can't run at all, because Create has no Fabric build for
1.21.1. On NeoForge, *Let The Adventure Begin* and the Create: Structures family (Structures Arise,
Structures Overhaul, Wells, Sky Village, New Beginnings, Hangars) all work with Create installed.

---

## Performance and client mods

**C2ME** and **Lithium** are recommended — C2ME roughly doubled world-generation speed in testing.

Sodium, Iris, shaders, minimaps, HUD mods and the like don't touch world generation at all. Archean Rise
has been run inside a 49-mod optimised client pack with no issues.

---

## How we test

The mod is installed alongside Archean Rise on a clean server, a world is generated, and we check that:
the server starts, Archean Rise still controls the world, the mod's content actually *appears*, nothing is
damaged, and the logs are clean. It has to pass all five.

**A mod that loads quietly but whose biomes never generate still counts as broken.** That's the most common
way these things fail.

---

## Not listed?

Then it hasn't been tested — which isn't a verdict, just a blank. Try it on a throwaway world first.

If something goes wrong, please tell us, and include the mod, its version, your Archean Rise version and
your loader. That's what turns a blank into an answer.

<sub>Compatibility results are recorded here whenever testing happens — a pass, a failure, or an
inconclusive run. A tick is only ever written against a version it was actually tested on.</sub>
