package dev.archeanrise.platform;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Fabric implementation of {@link Platform}. Lives in the Fabric-only source (EXCLUDED from the
 * :neoforge build's shared srcDir). Set in {@code ArcheanRiseFabric.onInitialize()} before any
 * shared code runs.
 */
public final class FabricPlatform implements Platform {

	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	@Override
	public Optional<ModInfo> mod(String id) {
		return FabricLoader.getInstance().getModContainer(id).map(FabricModInfo::new);
	}

	private record FabricModInfo(ModContainer container) implements ModInfo {
		@Override
		public String id() {
			return container.getMetadata().getId();
		}

		@Override
		public String version() {
			return container.getMetadata().getVersion().getFriendlyString();
		}

		@Override
		public Collection<String> dependencyModIds() {
			return container.getMetadata().getDependencies().stream()
					.map(d -> d.getModId())
					.collect(Collectors.toList());
		}
	}
}
