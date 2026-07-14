# Villages and structures on big terrain

Archean Rise's land is steeper and more dramatic than vanilla's. That's great to look at and hard to
build a village on. Here's what the mod does about it, and what you'll notice.

## Villages are rarer

A village needs ground you can actually walk across. On terrain this steep, a lot of sites simply
aren't buildable — so instead of dropping a village onto a cliff face with houses hanging in mid-air,
**Archean Rise skips those sites entirely**.

The result: **fewer villages than vanilla**, and if you spawn somewhere mountainous, possibly none
nearby at all. That's the trade. Villages you do find are on ground that works.

If you'd rather have more villages and accept some broken ones, in `config/archean_rise.json`:

```
"siteGradingVeto": false
```

## Structures are spread further apart

Everything is bigger here, so structures are spaced about **3× further apart** than vanilla — otherwise
villages would sit on top of each other like suburbs. This applies to other mods' structures too.

## Some structures get moved or skipped

By default, Archean Rise will:

- **Reshape the ground** around other mods' buildings so they sit into the hillside instead of hanging
  off it or poking through it.
- **Skip** structures that would land on deep water or on snow-covered ground, where they'd look wrong.

Both can be turned off in the config (`insetForeignStructures`, `gateForeignInWater`,
`gateForeignInSnow`) if you'd rather have everything, warts and all.

## Known rough edges

- Structures from other mods that sit at a **fixed height** (some "sky" structures around Y 130–200)
  can end up low against Archean Rise's much taller mountains.
- A few mods' buildings still land awkwardly on very steep ground. If you find one, please report it —
  that's exactly the kind of thing we want to know about.
