# Mod Compatibility

Which mods work alongside Archean Rise, and which don't.

> **Archean Rise is a world-generation mod.** It takes over how the Overworld is built. That means it
> can only ever conflict with other mods that also touch world generation — terrain mods, biome mods
> and structure mods. **Everything else is fine.** Performance mods, client/visual mods, QoL mods,
> machinery mods, food mods and so on do not compete with Archean Rise and are not listed here.

---

## How to read this page

Each mod has a tick or a cross for the **three most recent Archean Rise versions**:

| Symbol | Meaning |
|:---:|---|
| ✅ | **Works.** Tested on that version of Archean Rise, and the mod does what it is supposed to do. |
| ❌ | **Does not work.** Tested on that version, and it failed — it crashed, or its content silently never appeared, or Archean Rise damaged it. The Notes say which. |
| ⚠️ | **Works, but not recommended.** Nothing breaks, but there is a catch worth knowing about first. The Notes say what. |
| — | **Not tested on that version.** No claim either way. Look at the **Last tested** column to see when it *was* last checked. |

**A ❌ does not always mean a crash.** The most common failure is quiet: the mod loads, nothing looks
broken, but its biomes or structures simply never generate. That is still a ❌ here — if you install a
biome mod and get none of its biomes, it does not work, however peacefully it fails.

**Read the Last tested column.** A row of dashes with `✅ 0.1.0` next to it means "this passed a long
time ago, on a much older version of Archean Rise, and nobody has re-checked it since." That is not a
promise that it still works today. See [Why so many dashes](#why-so-many-dashes) below.

---

## How mods are tested

A mod is installed together with Archean Rise on a clean dedicated server — on **both** Fabric and
NeoForge where the mod supports both — and a world is generated from scratch. We then check five things:

1. **Does the server start?** A crash, or a registry error caused by the pairing, is an immediate ❌.
2. **Does Archean Rise still control the world?** The startup log must confirm the Archean Rise world
   is active. If another mod has quietly replaced the Overworld, that is a ❌ *for that mod*.
3. **Does the mod's content actually appear?** Its biomes and structures are searched for in a
   generated world. A mod whose content never shows up is a ❌ — even though nothing crashed.
4. **Does the mod damage the world, or does Archean Rise damage the mod?** Terrain around the mod's
   structures is inspected for breakage (holes, floating terrain, structures buried or left hanging).
5. **Are the logs clean?** Any error or warning caused by the pairing is investigated.

The mod must pass **all five** to earn a ✅.

Testing is run by the project's own audit tooling (`/archeanrise-audit`), not by eye.

---

## Worldgen libraries

Several popular biome and structure mods don't do the work themselves — they rely on a shared library.
The library decides whether they work with Archean Rise, so it is worth listing separately.

| Library | 0.3.14 | 0.3.15 | 0.3.16 | Last tested | Notes |
|---|:---:|:---:|:---:|---|---|
| **TerraBlender** | ❌ | ❌ | ✅ | ✅ 0.3.16 | Used by Biomes O' Plenty, Oh The Biomes We've Gone, Nature's Spirit, YUNG's Cave Biomes and Underground Worlds. Until 0.3.16 none of their biomes appeared in an Archean Rise world — Archean Rise simply wasn't on TerraBlender's list of worlds to add biomes to. **Fixed in 0.3.16**; it now is. |
| **Lithostitched** | ❌ | ❌ | ✅ | ✅ 0.3.16 | Used by Terralith, Tectonic, Regions Unexplored, CTOV and ~25 other mods. **Until 0.3.16 this silently switched Archean Rise off.** The world still looked completely normal, but there were no rivers, no structure re-spacing and no ore rebalance — measured: ~65% of the deep ore simply gone. If you played 0.3.13–0.3.15 with any of those mods, **your world was not really an Archean Rise world.** Fixed in 0.3.16. |

---

## Terrain and biome mods

These are the mods most likely to conflict, because they want to do the same job as Archean Rise.

| Mod | 0.3.14 | 0.3.15 | 0.3.16 | Last tested | Notes |
|---|:---:|:---:|:---:|---|---|
| **Expanded Ecosphere** | ❌ | ❌ | ❌ | ❌ 0.1.5 | **The game will not start.** It replaces the Overworld of *any* world type, including Archean Rise's, so the two can never both work. Archean Rise refuses to launch beside it on purpose, with an explanation, rather than let it silently take over your world. |
| **Biomes O' Plenty** | ❌ | ❌ | ✅ | ✅ 0.3.16 | **Works from 0.3.16.** Before that, none of its biomes ever appeared — no crash, they simply never generated. Its biomes now grow on Archean Rise's terrain. |
| **Oh The Biomes We've Gone** | — | — | — | ❌ 0.2.x | Uses TerraBlender, which was fixed in 0.3.16 — so this **should** now work, but it has not been tested yet. Treat as unknown until it is. |
| **Nature's Spirit** | — | — | — | ❌ 0.2.x | As above — TerraBlender-based, expected to work from 0.3.16, not yet tested. |
| **YUNG's Cave Biomes** | — | — | — | ❌ 0.2.x | As above — TerraBlender-based, expected to work from 0.3.16, not yet tested. |
| **Underground Worlds** | — | — | — | ❌ 0.2.x | As above — TerraBlender-based, expected to work from 0.3.16, not yet tested. |
| **Terralith** | — | — | — | ⚠️ 0.2.x | **Its old tick is not trustworthy — re-test pending.** Its biomes do appear, and its terrain correctly loses to Archean Rise's. But it also quietly replaces several of the noise settings Archean Rise itself uses (it roughly doubles the scale of our erosion), and its biomes arrive wearing Archean Rise's surfaces rather than their own, which looks wrong. Playable, but not properly verified. |
| **Regions Unexplored** | — | — | — | ⚠️ 0.2.x | **Its old tick is not trustworthy — re-test pending.** It is one of the mods that used to silently switch Archean Rise off (see Lithostitched above). That is fixed in 0.3.16, but the pairing has not been re-tested since. |
| **Geophilic** | — | — | — | ✅ 0.2.x | Biomes appear in Archean Rise terrain. |
| **Tectonic** | — | — | — | ⚠️ 0.2.x | **Its old tick is not trustworthy — re-test pending.** Its terrain genuinely has no effect (Archean Rise generates the land). But it is not the harmless no-op the old entry claimed: it moves the snow line by ~128 blocks **everywhere**, and drops ocean monuments by 30 blocks, whatever world you are in. |
| **BetterEnd / BetterNether / Incendium / Nullscape** | — | — | — | ✅ 0.2.x | Fine. Archean Rise only changes the Overworld; the Nether and the End are left completely alone. |
| **terrain-diffusion-mc** | — | — | — | ⚠️ 0.2.x | **Not recommended.** It doesn't break your world, but it misbehaves on non-native world types and downloads ~2.5 GB of models on first launch. |

> **What changed in 0.3.16.** Biomes O' Plenty and its relatives add biomes through a library called
> **TerraBlender**, which keeps a list of the worlds it is allowed to add biomes to. Archean Rise was
> never on that list, so their biomes had nowhere to go. Archean Rise now adds itself to it, and they
> generate normally. Nothing about Archean Rise's terrain changed — the biomes are simply painted onto
> the land it already made.
>
> **If you are on 0.3.13–0.3.15 with Terralith, Tectonic, Regions Unexplored or CTOV, please update.**
> Those mods use a second library, **Lithostitched**, which used to make Archean Rise fail to recognise
> its own world. Everything still generated and looked right, but the rivers, the ore balance, the
> structure spacing and the terrain grading were all silently switched off.

---

## Structure mods

| Mod | 0.3.14 | 0.3.15 | 0.3.16 | Last tested | Notes |
|---|:---:|:---:|:---:|---|---|
| **Create: Let The Adventure Begin** | ✅ | ✅ | ✅ | ✅ 0.3.16 | **NeoForge only, and Create must be installed.** On 0.3.13 Archean Rise carved the ground out from under its buried cave ruins and left floating chunks of terrain overhead — fixed in 0.3.14 and re-verified on every release since. On Fabric it crashes the server at boot, because it references Create blocks but Create has no Fabric 1.21.1 build; that is an upstream defect, not an Archean Rise one. |
| **YUNG's Better Dungeons** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Better Mineshafts** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Better Strongholds** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Better Desert Temples** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Better Witch Huts** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Better Jungle Temples** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Better Ocean Monuments** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Better Nether Fortresses** | — | — | — | ✅ 0.1.0 | Nether — untouched by Archean Rise. |
| **YUNG's Better End Island** | — | — | — | ✅ 0.1.0 | End — untouched by Archean Rise. |
| **YUNG's Bridges** | — | — | — | ✅ 0.1.0 | |
| **YUNG's Extras** | — | — | — | ✅ 0.1.0 | |
| **Towns and Towers** | — | — | — | ✅ 0.1.0 | Its village variants that need partner biome mods stay dormant without them — by its own design. |
| **Dungeons and Taverns** | — | — | — | ✅ 0.1.0 | Its `mining_system` is shipped switched off by the mod itself. |
| **When Dungeons Arise** | — | — | — | ✅ 0.1.0 | Its sky structures sit high up, in a band Archean Rise also uses — worth watching. |
| **Repurposed Structures** | — | — | — | ✅ 0.1.0 | |
| **ChoiceTheorem's Overhauled Village** | — | — | — | ✅ 0.1.0 | |
| **Explorify** | — | — | — | ✅ 0.1.0 | |
| **Villages & Pillages** | — | — | — | ✅ 0.1.0 | |
| **Structory** | — | — | — | ✅ 0.1.0 | |
| **Structory: Towers** | — | — | — | ✅ 0.1.0 | Its wizard tower needs magical biomes from another mod — dormant without them, by design. |
| **Philip's Ruins** | — | — | — | ✅ 0.1.0 | |
| **AdoraBuild: Structures** | — | — | — | ✅ 0.1.0 | |
| **Moog's Voyager Structures** | — | — | — | ✅ 0.1.0 | |
| **Moog's End Structures** | — | — | — | ✅ 0.1.0 | End — untouched by Archean Rise. |
| **Better Archeology** | — | — | — | ✅ 0.1.0 | Prints a harmless warning at boot (its own, not ours). |
| **Formations Overworld / Formations Nether** | — | — | — | ✅ 0.1.0 | |
| **The Lost Castle** | — | — | — | ✅ 0.1.0 | |
| **Explorations** | — | — | — | ✅ 0.1.0 | |
| **Additional Structures** | — | — | — | ✅ 0.1.0 | |
| **Luki's Grand Capitals** | — | — | — | ✅ 0.1.0 | |

### Create-based structure mods

Create itself has **no Fabric build for Minecraft 1.21.1** (Fabric support stops at 1.20.1; on 1.21.1
Create is NeoForge-only). So on **Fabric**, every Create-based structure mod is unusable — with or
without Archean Rise. This is an ecosystem fact, not an Archean Rise limitation.

On **NeoForge**, where Create does exist, these mods can run. Only *Create: Let The Adventure Begin*
(in the table above) has actually been tested with Archean Rise so far.

Every mod below is therefore **untested — treat it as unknown**, not as safe:

Create: Structures · Create: Structures Arise · Create: Easy Structures · Create: Rustic Structures ·
Create: Pillagers Arise · Create: Molten Vents · Create: Dynamic Village · Create: New Beginnings ·
Create: Sky Village · Create: Hangars · Create: Structures Overhaul · IDAS (Integrated Dungeons and
Structures) · CTOV Create addon · Integrated Stronghold

Given what was found in *Create: Let The Adventure Begin* — Archean Rise was damaging the terrain
around its buried structures — the rest of this family is a priority for testing, not a safe bet.

---

## Performance and server mods

| Mod | 0.3.14 | 0.3.15 | 0.3.16 | Last tested | Notes |
|---|:---:|:---:|:---:|---|---|
| **C2ME** | — | — | — | ✅ 0.2.3 | Recommended. Roughly doubled world-generation speed in testing. |
| **Lithium** | — | — | — | ✅ 0.2.3 | Recommended. |

Client-side mods — Sodium, Iris, ModernFix, ImmediatelyFast, EntityCulling, shaders, minimaps, UI mods
and so on — **do not interact with world generation at all**. Archean Rise has been run inside a
49-mod Fabulously-Optimized-style client pack with no issues. They are not tracked individually here.

---

## Why so many dashes

Most of the ticks on this page were earned on **Archean Rise 0.1.0 and 0.2.x**, and the world has
changed a great deal since then. Version 0.3.0 rebuilt the terrain from scratch, and later versions
changed how Archean Rise handles *other mods'* structures — it now re-spaces them, reshapes the ground
around them, and in some cases declines to place them at all.

That means the old ticks can no longer be taken for granted, and we are not going to pretend otherwise
by copying them forward into columns they were never tested in.

The floating-island bug in **Create: Let The Adventure Begin** is the proof: it was ticked as
compatible, and it was silently being broken. It took a fresh test on 0.3.13 to find it.

**So: a dash means "we genuinely do not know yet."** Re-testing the list against the current version is
in progress. If a mod matters to you and it shows dashes, test it yourself on a throwaway world first.

---

## Something not on this list?

If a mod isn't listed here, it has not been tested. That is not a verdict — just an absence of one.

If you hit a problem with a specific mod, please report it, and include the mod, its version, your
Archean Rise version, and your loader. That is what turns a dash into a tick or a cross.

---

<sub>**Maintenance:** this page is the project's compatibility record. Whenever compatibility testing is
performed — a pass, a failure, or an inconclusive run — the result **must** be recorded here in the same
commit, and the version columns roll forward on every release. Nothing gets tested and quietly forgotten.
See the `archean-compat-matrix` skill.</sub>
