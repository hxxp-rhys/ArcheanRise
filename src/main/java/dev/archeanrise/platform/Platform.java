package dev.archeanrise.platform;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Tiny cross-loader service seam. The Archean Rise core (worldgen, audit, config, pregen) is
 * loader-neutral except for a handful of loader-service lookups — config/game dirs and mod
 * metadata. Those go through this interface so the SAME shared source compiles and runs on both
 * Fabric (see {@code FabricPlatform}) and NeoForge (see {@code NeoForgePlatform} in the :neoforge
 * subproject). Each loader's entrypoint calls {@link #set(Platform)} before any shared code runs.
 *
 * <p>Kept deliberately minimal — only the methods actually called by the shared code exist here
 * (config dir; a reports dir; mod id/version/deps). Add a method only when a real call site needs it.
 */
public interface Platform {

	/** Loader config directory (Fabric getConfigDir / NeoForge FMLPaths.CONFIGDIR). */
	Path configDir();

	/** Loader game/run directory (Fabric getGameDir / NeoForge FMLPaths.GAMEDIR). */
	Path gameDir();

	/** Convenience: the mod's report output dir. Not created here — callers create as needed. */
	default Path reportsDir() {
		return gameDir().resolve("archean-rise-reports");
	}

	/** Metadata for a loaded mod by id, empty if not loaded (datapack namespace or absent). */
	Optional<ModInfo> mod(String id);

	/** Loader-neutral view of a mod's metadata (only the fields the audit code reads). */
	interface ModInfo {
		String id();
		String version();
		Collection<String> dependencyModIds();
	}

	// --- static holder (interfaces cannot hold a mutable static field directly) ---

	static Platform get() {
		Platform p = Current.instance;
		if (p == null) {
			throw new IllegalStateException("Platform not initialized — the loader entrypoint must call Platform.set(...) first");
		}
		return p;
	}

	static void set(Platform platform) {
		Current.instance = platform;
	}

	/** Package-private mutable holder for the active {@link Platform}. */
	final class Current {
		private static volatile Platform instance;

		private Current() {}
	}
}
