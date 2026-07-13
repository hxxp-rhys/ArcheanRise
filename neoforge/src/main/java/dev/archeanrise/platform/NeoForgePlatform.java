package dev.archeanrise.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * NeoForge implementation of {@link Platform}. Lives ONLY in the :neoforge source. Installed by
 * {@code ArcheanRiseNeoForge}'s constructor before any shared code runs.
 */
public final class NeoForgePlatform implements Platform {

	@Override
	public Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public Path gameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public Optional<ModInfo> mod(String id) {
		return ModList.get().getModContainerById(id).map(c -> new NeoModInfo(c.getModInfo()));
	}

	private record NeoModInfo(IModInfo info) implements ModInfo {
		@Override
		public String id() {
			return info.getModId();
		}

		@Override
		public String version() {
			return info.getVersion().toString();
		}

		@Override
		public Collection<String> dependencyModIds() {
			return info.getDependencies().stream()
					.map(IModInfo.ModVersion::getModId)
					.collect(Collectors.toList());
		}
	}
}
