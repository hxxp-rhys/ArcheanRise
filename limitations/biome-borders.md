# Biome borders are sharp

Where two biomes meet, the grass colour, the plants and the mobs all change in one step rather than
fading into each other. It can look like a hard line across the ground.

**This is how Minecraft works, not something Archean Rise broke.** Minecraft picks exactly one biome per
4-block cell — there's no blending anywhere in the game. Vanilla has the same hard edges; they're just
more noticeable here, because Archean Rise's biomes are big, so you see long, clean borders.

The ground itself is always smooth — there's never a cliff at a biome border, only a change of scenery.

## Can it be softened?

A little. In `config/archean_rise.json`:

```
"biomeBorderBlend": 0     // 0 = off. Try 8–16.
```

This makes borders **wander and interlock** instead of running straight, which reads as much more
natural. It does **not** fade one biome into the other — nothing can, in Minecraft.

It's off by default because it needs tuning to taste. Turning it on only affects **newly generated**
chunks, so the boundary of the land you've already explored may look a bit seamed.

For the grass and water *colours* specifically, raising **Biome Blend** in your own video settings
(Options → Video) smooths the colour transition — that's a client-side setting and it helps a lot.
