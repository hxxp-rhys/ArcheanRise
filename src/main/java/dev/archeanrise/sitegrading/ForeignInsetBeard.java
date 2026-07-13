package dev.archeanrise.sitegrading;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.ArrayList;
import java.util.List;

/**
 * Cut-to-inset earthwork for ADD-ON (foreign) surface structures (issue 2). SiteGrading grades only
 * vanilla-type village jigsaws; every OTHER surface structure (YUNG's, Towns &amp; Towers, Structory,
 * When Dungeons Arise, …) gets only vanilla {@code terrain_adaptation}, and {@code BEARD_THIN} conforms
 * terrain to the ground PLANE only — it cannot clear a box on a steep slope, so pieces hang off
 * mountainsides / poke through. This upgrades an eligible foreign {@code BEARD_THIN} structure to a
 * {@code BEARD_BOX}-style carve at the NOISE stage (vanilla's own BEARD_BOX zeroes the vertical kernel
 * distance across the whole box, so it clears the box; BEARD_THIN does not): it removes terrain
 * throughout the piece box and up over the roof, plus a &gt;= {@link #PAD_FULL}-block beveled skirt ABOVE
 * the roof only (a shelf edge — never a base-level moat, which would trench a ring around each building), so the
 * structure is INSET into the hill with a clean interior — <b>no terrain inside the buildings</b>,
 * guaranteed because terrain never forms there — while the wrapped vanilla beard still supplies the
 * fill-below-plane support so the structure does not float.
 *
 * <p><b>Self-limiting:</b> on flat ground the box interior is already air above the surface, so the
 * carve removes nothing; it only clears terrain that actually intrudes (the slope/mountain case).
 *
 * <p>Composes ADDITIVELY over the wrapped beardifier — same pattern as {@link GradePad}, and disjoint
 * from it (GradePad handles gradable village pieces; this handles {@code !isGradable} pieces). Foreign
 * structures with {@code BURY} / {@code ENCAPSULATE} / {@code NONE} / {@code BEARD_BOX} adaptation are
 * left alone (they handle themselves or want burial), as are gradable structures.
 * Deterministic: a pure function of the seed-placed piece boxes.
 *
 * <p><b>SURFACE structures only ({@link BuriedStructures}, v0.3.14).</b> This is EARTHWORK FOR BUILDINGS:
 * it cuts the hill off a structure so it insets cleanly. Pointed at a structure its author deliberately
 * BURIED, it excavates the ground the structure was meant to be hidden in. Before 0.3.14 the only guard
 * was the absolute {@code box.maxY() < SEA_LEVEL} test, which is meaningless in a -256..768 world — and
 * {@code create_ltab:cave_ruins}, a cave ruin sunk 40 blocks under a y=231 mountain, was excavated as if
 * it were a hillside cottage, leaving a detached island overhead. A start is now skipped entirely when
 * {@link BuriedStructures#isBuriedStart} finds its start piece buried at least
 * {@code insetForeignBurialMargin} blocks below the surface at the column vanilla projected it from.
 * That is a pure function of the structure's own declared {@code start_height}, so a genuine surface
 * structure cannot trip it however steep the terrain — see the proof in {@link BuriedStructures}.
 */
public final class ForeignInsetBeard extends Beardifier {

	/** Density removed inside the box to force air (comfortably past vanilla's 0.8 beard scale). */
	private static final double CARVE = 4.0;
	/** Full-strength beveled skirt width beyond the box edge, ABOVE the roof only — the &gt;=2-block shelf edge. */
	private static final int PAD_FULL = 2;
	/** Horizontal skirt falloff: full to PAD_FULL, smoothstep to zero by here. */
	private static final int PAD_TAPER = 5;
	/** Vertical taper above the box roof, so the top is exposed as a shelf rather than left capped. */
	private static final int ROOF_TAPER = 8;

	private record InsetBox(BoundingBox box, int groundPlane) {}

	private final Beardifier delegate;
	private final List<InsetBox> boxes;

	private ForeignInsetBeard(Beardifier delegate, List<InsetBox> boxes) {
		// Empty piece/junction iterators: all vanilla beard math is delegated; this only ADDS the carve.
		super(new it.unimi.dsi.fastutil.objects.ObjectArrayList<Rigid>().iterator(),
				new it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>().iterator());
		this.delegate = delegate;
		this.boxes = boxes;
	}

	/** Wrap {@code delegate}, adding an inset carve for every eligible foreign surface piece near this chunk. */
	public static Beardifier wrap(Beardifier delegate, StructureManager structureManager, ChunkPos chunkPos,
			ChunkGenerator generator, RandomState randomState, LevelHeightAccessor heightAccessor) {
		dev.archeanrise.config.ArcheanRiseConfig config = ArcheanRise.config;
		if (!SiteGrading.enabled() || (config != null && !config.insetForeignStructures)) {
			return delegate;
		}
		List<InsetBox> boxes = new ArrayList<>();
		for (StructureStart start : structureManager.startsForStructure(chunkPos,
				s -> isEligible(s, structureManager.registryAccess()))) {
			// BURIAL GATE (0.3.14) — a structure its author deliberately sank (a cave ruin, a crypt) is not a
			// surface build. Carving the hill off it is what severed the terrain above create_ltab:cave_ruins
			// and left a floating island. Hand it back to vanilla's own adaptation, untouched. Whole-START
			// scoped (never per-piece) so beard and grade keep treating the identical piece set.
			if (BuriedStructures.isBuriedStart(start, generator, randomState, heightAccessor,
					structureManager.registryAccess())) {
				continue;
			}
			for (StructurePiece piece : start.getPieces()) {
				if (!(piece instanceof PoolElementStructurePiece pool)
						|| pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
					continue;
				}
				BoundingBox box = piece.getBoundingBox();
				if (box.maxY() < SiteGrading.SEA_LEVEL) {
					// Cheap prefilter, kept for its own sake: a piece whose roof is under sea level is never a
					// surface inset. It only ever WIDENS the skip set, so it costs no probes and it guarantees
					// sub-sea pieces stay byte-identical to 0.3.13. The authoritative "is this underground?"
					// test is now the surface-relative burial gate above — see BuriedStructures.
					continue;
				}
				if (box.maxX() + PAD_TAPER < chunkPos.getMinBlockX()
						|| box.minX() - PAD_TAPER > chunkPos.getMaxBlockX()
						|| box.maxZ() + PAD_TAPER < chunkPos.getMinBlockZ()
						|| box.minZ() - PAD_TAPER > chunkPos.getMaxBlockZ()) {
					continue; // piece + skirt does not reach this chunk
				}
				boxes.add(new InsetBox(box, box.minY() + pool.getGroundLevelDelta()));
			}
		}
		return boxes.isEmpty() ? delegate : new ForeignInsetBeard(delegate, boxes);
	}

	/** Foreign (non-gradable) BEARD_THIN structures only — the hang-off-the-slope / poke-through case.
	 *  Package-visible so {@link ForeignInsetGrade} grades exactly the structures this beard insets. */
	static boolean isEligible(Structure structure, RegistryAccess registryAccess) {
		return structure.terrainAdaptation() == TerrainAdjustment.BEARD_THIN
				&& !SiteGrading.isGradable(structure, registryAccess);
	}

	@Override
	public double compute(FunctionContext context) {
		double base = delegate.compute(context);
		int x = context.blockX();
		int y = context.blockY();
		int z = context.blockZ();
		double carve = 0.0;
		for (InsetBox b : boxes) {
			if (y < b.groundPlane()) {
				continue; // at/below the floor plane — leave support to the vanilla beard
			}
			BoundingBox box = b.box();
			int vertAboveRoof = y - box.maxY();
			if (vertAboveRoof >= ROOF_TAPER) {
				continue; // above the roof taper — natural terrain resumes
			}
			int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
			int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
			double w;
			if (dx == 0 && dz == 0) {
				// The piece's own column: clear the WHOLE box interior (the buried part) up over the roof.
				w = 1.0;
			} else {
				// SKIRT — only cut ABOVE the roof (a beveled shelf edge). Cutting at/below the roof OUTSIDE
				// the footprint would trench a ring around every building even on flat ground (the v0.2.19
				// "traced cutout" artifact around modded villages). So the skirt is a top bevel, not a moat.
				if (vertAboveRoof < 0) {
					continue;
				}
				double d = Math.sqrt(dx * (double) dx + dz * (double) dz);
				if (d >= PAD_TAPER) {
					continue;
				}
				w = d <= PAD_FULL ? 1.0 : smoothstep((PAD_TAPER - d) / (double) (PAD_TAPER - PAD_FULL));
			}
			double vt = vertAboveRoof <= 0 ? 1.0 : (1.0 - vertAboveRoof / (double) ROOF_TAPER);
			carve = Math.min(carve, -CARVE * w * vt); // strongest (most negative) box wins
		}
		return base + Mth.clamp(carve, -CARVE, 0.0);
	}

	@Override
	public double minValue() {
		return delegate.minValue() - CARVE; // widened so range analysis cannot cull the carve
	}

	@Override
	public double maxValue() {
		return delegate.maxValue();
	}

	private static double smoothstep(double t) {
		t = Mth.clamp(t, 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}
}
