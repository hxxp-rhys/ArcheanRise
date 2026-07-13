package dev.archeanrise.compat;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.platform.Platform;

/**
 * Runtime detection of the performance mods Archean Rise RECOMMENDS but never bundles (adopt, never
 * absorb — their licenses forbid it: Moonrise GPL-viral, Lithium/Noisium/Starlight LGPL, C2ME-OCL ARR,
 * C2ME core MIT-but-unwise; see {@code docs/DECISIONS.md} 2026-07-07). This is the coexistence
 * foundation (BC-DIST / N6a): Archean Rise composes with the user's own C2ME/Lithium, and when a future
 * AR lever would overlap a perf mod's path (e.g. a direct-palette write vs Noisium), it DEFERS to the
 * installed mod via the flags here. Today AR ships no overlapping path, so this detects + logs + nudges;
 * the flags are the seam future levers gate on.
 */
public final class ModCompat {

	private static boolean initialized;

	/** Detected once at init (after {@link Platform#set}). Future overlapping levers read these to defer. */
	public static boolean c2me;
	public static boolean c2meOpenCl;
	public static boolean lithium;
	public static boolean noisium;
	public static boolean starlight;

	private ModCompat() {}

	/** Detect the recommended perf stack + log it. Call once from {@code commonInit} (Platform is set by then). */
	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		c2me = present("c2me");
		c2meOpenCl = present("c2me_opts_accel_opencl");
		lithium = present("lithium");
		noisium = present("noisium");
		starlight = present("starlight");

		StringBuilder found = new StringBuilder();
		append(found, "C2ME", c2me);
		append(found, "Lithium", lithium);
		append(found, "Noisium", noisium);
		append(found, "Starlight", starlight);
		if (found.length() > 0) {
			ArcheanRise.LOGGER.info("Performance mods detected — Archean Rise composes with them: {}", found);
		}
		if (!c2me) {
			ArcheanRise.LOGGER.info("Tip: for much faster pre-generation, install C2ME (+ Lithium) — they "
					+ "multiply pregen throughput and are coexistence-certified with Archean Rise. A curated "
					+ "modpack is provided under mrpack/.");
		}
	}

	private static boolean present(String id) {
		try {
			return Platform.get().mod(id).isPresent();
		} catch (Throwable t) {
			return false;
		}
	}

	private static void append(StringBuilder sb, String name, boolean on) {
		if (on) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(name);
		}
	}
}
