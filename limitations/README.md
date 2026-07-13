# limitations/

User-facing documentation of what Archean Rise **cannot** do, on which hardware/platforms
features degrade, and why. One file per feature area. Keep entries honest and current —
a documented limitation is a behavior we promise, not an apology.

- [world-height.md](world-height.md) — the static world geometry (Y −256..768, mountain cap
  708, seafloor −128) is baked at world creation (one-way door), pre-0.3.0 worlds unsupported,
  what deliberately does not scale, ore-band movement, height performance cost.
- [mod-compatibility.md](mod-compatibility.md) — known conflicts (overworld-replacer terrain
  mods; Create ecosystem is NeoForge-only on MC 1.21.1; live-tested verdicts).
- [biome-borders.md](biome-borders.md) — why biome borders are abrupt, what the
  `biomeBorderBlend` knob can and cannot soften, and the levers that were rejected.
- [structure-grading.md](structure-grading.md) — SiteGrading v2: villages are rarer on rough
  terrain and absent on un-buildable terrain by design; the default-off CUT+FILL earthwork.
