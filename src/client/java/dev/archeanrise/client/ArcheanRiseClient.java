package dev.archeanrise.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Archean Rise is server-first; the client entrypoint exists only for future client-side
 * conveniences (e.g. world-creation screen polish). Keep gameplay logic out of here.
 */
public class ArcheanRiseClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
	}
}
