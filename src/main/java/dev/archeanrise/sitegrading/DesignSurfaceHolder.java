package dev.archeanrise.sitegrading;

/**
 * Thread-confined hand-off of the active DesignSurface from M1 (JigsawStructure site planning)
 * to M2 (heightmap-projection clamps inside JigsawPlacement/Placer).
 *
 * Determinism contract (verifier D1): 1.21.1 defers the Placer BFS into the GenerationStub's
 * pieces-builder Consumer, which runs AFTER findGenerationPoint returns. M1 therefore arms
 * this holder TWICE — around the original addPieces call (covers the start-piece projection)
 * and inside the decorated stub consumer (covers tryPlacingChildren) — always with
 * try/finally clear, so pooled worker threads can never observe a stale surface. M2 MUST
 * pass through vanilla behavior whenever the holder is empty (shared code path: /place
 * jigsaw, other dimensions, other mods delegating to vanilla jigsaw plumbing).
 */
public final class DesignSurfaceHolder {
	private static final ThreadLocal<DesignSurface> ACTIVE = new ThreadLocal<>();

	private DesignSurfaceHolder() {}

	public static void set(DesignSurface surface) {
		ACTIVE.set(surface);
	}

	public static DesignSurface get() {
		return ACTIVE.get();
	}

	public static void clear() {
		ACTIVE.remove();
	}
}
