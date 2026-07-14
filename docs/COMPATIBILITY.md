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

| Mod | 0.3.15 | 0.3.16 | 0.3.17 | Notes |
|---|:---:|:---:|:---:|---|
| **Biomes O' Plenty** | ❌ | ✅ | — | Works from 0.3.16. Before that its biomes never appeared at all. |
| **Oh The Biomes We've Gone** | — | — | ✅ | Needs CorgiLib + GeckoLib. |
| **Nature's Spirit** | — | — | ✅ | |
| **YUNG's Cave Biomes** | — | — | ✅ | **Needs YUNG's API** — it will not load without it. |
| **Underground Worlds** | — | — | ✅ | |
| **Oh The Trees You'll Grow** | — | — | ✅ | Changes how saplings grow; doesn't touch terrain. |
| **Terralith** | — | — | ✅ | **Install the MOD, not the datapack.** The mod works — its biomes appear on Archean Rise's terrain. The standalone *datapack* version would replace Archean Rise's world entirely. |
| **Geophilic** | — | — | — | Worked on an older version. |
| **Regions Unexplored** | — | — | — | Needs re-testing. It used to switch Archean Rise off (see below); that's fixed, but the pairing hasn't been re-checked. |
| **Tectonic** | — | — | ⚠️ | Its terrain has no effect (Archean Rise makes the land). But it moves the snow line about 128 blocks and lowers ocean monuments, in any world. |
| **BetterEnd / BetterNether / Incendium / Nullscape** | — | — | — | Fine. Archean Rise only changes the Overworld — the Nether and End are untouched. |
| **Expanded Ecosphere** | ❌ | ❌ | ❌ | **The game won't start.** It replaces the Overworld of *any* world type, so the two can never both work. Archean Rise refuses to launch beside it on purpose. |
| **terrain-diffusion-mc** | — | — | ⚠️ | Not recommended — it downloads ~2.5 GB of models on first launch and misbehaves on custom world types. |

> **⚠️ If you played 0.3.13–0.3.15 with Terralith, Tectonic, Regions Unexplored or CTOV — please update.**
> Those mods share a library that made Archean Rise stop recognising its own world. Everything still
> *looked* right, but rivers, ore balance, structure spacing and terrain grading were all quietly switched
> off. Fixed in 0.3.16.

---

## Structure mods

| Mod | 0.3.15 | 0.3.16 | 0.3.17 | Notes |
|---|:---:|:---:|:---:|---|
| **Create: Let The Adventure Begin** | ✅ | ✅ | ✅ | NeoForge only, and Create must be installed. (On Fabric it crashes at boot — Create has no Fabric build for 1.21.1. That's the mod's own problem, not ours.) |

**Tested and working on an early version (0.1.0), not re-checked since:** all eleven YUNG's mods · Towns
and Towers · Dungeons and Taverns · When Dungeons Arise · Repurposed Structures · ChoiceTheorem's
Overhauled Village · Explorify · Villages & Pillages · Structory · Structory: Towers · Philip's Ruins ·
AdoraBuild · Moog's Voyager Structures · Moog's End Structures · Better Archeology · Formations ·
The Lost Castle · Explorations · Additional Structures · Luki's Grand Capitals.

They all passed back then, and nothing suggests they've broken — but the terrain has changed a lot since,
so treat them as *probably fine* rather than guaranteed. If one misbehaves, please report it.

**Create-based structure mods** (Create: Structures, Pillagers Arise, Easy Structures, IDAS, and the rest):
on Fabric they can't run at all, because Create has no Fabric build for 1.21.1. On NeoForge they should
work, but only *Let The Adventure Begin* has actually been tested.

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
