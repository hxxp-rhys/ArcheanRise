package dev.archeanrise.sitegrading;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 3 primary: GradePad density terms — a composite beardifier that ADDS bounded
 * ground-conforming density around allowlisted structure pieces, on top of (never instead
 * of) vanilla's Beardifier. Runs at the noise stage, so caves, aquifers, ores, and surface
 * rules all process the reshaped terrain naturally.
 *
 * Kernel (blueprint + quality-verifier D2 plateau fix):
 *   w(d) = smoothstep(1 - d/12)  lateral, hard 0 at 12 (reference-envelope safe)
 *   v(p) = carve: -min(p/8, 1) * carveScale  for p > 0 (capped; 0 if plane near sea level)
 *          fill : 1 for p in [-8, 0]; taper to 0 by p = -24 (vertical decay, D2-compat)
 *   contribution = 0.9 * w * v, summed over pieces, clamped to [-0.9, 0.9]
 *
 * Piece list is built from an INDEPENDENT startsForStructure query (never touching the
 * wrapped Beardifier's iterators — determinism verifier D2) with the same allowlist
 * predicate as placement, so a structure never receives partial treatment.
 */
public final class GradePad extends net.minecraft.world.level.levelgen.Beardifier {
	private record Pad(BoundingBox box, int groundPlane, boolean fillOnly) {}

	private final net.minecraft.world.level.levelgen.Beardifier vanilla;
	private final List<Pad> pads;

	private GradePad(net.minecraft.world.level.levelgen.Beardifier vanilla, List<Pad> pads) {
		// empty piece/junction iterators: this subclass delegates all beard math to the
		// wrapped vanilla instance and only ADDS the pad terms
		super(new it.unimi.dsi.fastutil.objects.ObjectArrayList<Rigid>().iterator(),
				new it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>().iterator());
		this.vanilla = vanilla;
		this.pads = pads;
	}

	public static net.minecraft.world.level.levelgen.Beardifier wrapAsBeardifier(
			net.minecraft.world.level.levelgen.Beardifier vanilla,
			StructureManager structureManager, ChunkPos chunkPos) {
		dev.archeanrise.config.ArcheanRiseConfig config = dev.archeanrise.ArcheanRise.config;
		if (!SiteGrading.enabled() || (config != null && !config.siteGradingGradePad)) {
			return vanilla;
		}
		List<Pad> pads = new ArrayList<>();
		for (StructureStart start : structureManager.startsForStructure(chunkPos,
				s -> SiteGrading.isGradable(s, structureManager.registryAccess()))) {
			for (StructurePiece piece : start.getPieces()) {
				if (!(piece instanceof PoolElementStructurePiece pool)
						|| pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
					continue;
				}
				BoundingBox box = piece.getBoundingBox();
				// skip pieces far from this chunk (pad reach is 12)
				if (box.maxX() + SiteGrading.PAD_RADIUS < chunkPos.getMinBlockX()
						|| box.minX() - SiteGrading.PAD_RADIUS > chunkPos.getMaxBlockX()
						|| box.maxZ() + SiteGrading.PAD_RADIUS < chunkPos.getMinBlockZ()
						|| box.minZ() - SiteGrading.PAD_RADIUS > chunkPos.getMaxBlockZ()) {
					continue;
				}
				int plane = box.minY() + pool.getGroundLevelDelta();
				pads.add(new Pad(box, plane, plane < 65)); // fill-only near/below the water table
			}
		}
		return pads.isEmpty() ? vanilla : new GradePad(vanilla, pads);
	}


	@Override
	public double compute(FunctionContext context) {
		double base = vanilla.compute(context);
		double pad = 0;
		int x = context.blockX();
		int y = context.blockY();
		int z = context.blockZ();
		for (Pad p : pads) {
			double dx = Math.max(0, Math.max(p.box().minX() - x, x - p.box().maxX()));
			double dz = Math.max(0, Math.max(p.box().minZ() - z, z - p.box().maxZ()));
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d >= SiteGrading.PAD_RADIUS) {
				continue;
			}
			double w = smoothstep(1.0 - d / SiteGrading.PAD_RADIUS);
			int rel = y - p.groundPlane();
			double v;
			if (rel > 0) {
				v = p.fillOnly() ? 0.0 : -Math.min(rel / 8.0, 1.0);
			} else if (rel == 0) {
				// Do NOT fill the floor plane itself. The piece floor sits AT the plane (y = box.minY +
				// groundLevelDelta = the vanilla WORLD_SURFACE_WG height the piece projected onto), so the
				// natural top-solid must stop at plane-1 (the block the floor rests on). Filling y==plane
				// flipped it solid and raised top-solid by +1 over the whole footprint, burying descending
				// stairs / door approaches by one block (the v0.2.15 "villages 1 block too low" bug; the
				// rel>0 carve pins the over-raise to exactly +1). See DECISIONS.md 2026-07-08.
				v = 0.0;
			} else if (rel >= -8) {
				v = 1.0; // full-strength fill plateau from plane-1 downward (dip support; verifier D2)
			} else {
				v = Math.max(0.0, 1.0 - (-rel - 8) / 16.0); // taper to 0 by 24 below
			}
			pad += 0.9 * w * v;
		}
		return base + Mth.clamp(pad, -0.9, 0.9);
	}

	@Override
	public double minValue() {
		return vanilla.minValue() - 0.9; // widened so range analysis can't cull the pad
	}

	@Override
	public double maxValue() {
		return vanilla.maxValue() + 0.9;
	}

	private static double smoothstep(double t) {
		t = Mth.clamp(t, 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}
}
