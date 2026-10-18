package dev.customdiscs.client;

import dev.customdiscs.net.ModPayloads;

/** Limits the current server sent on join. Defaults match the server defaults until that packet arrives. */
public final class ServerLimits {
	private static ModPayloads.ServerSettings settings = defaults();

	private ServerLimits() {
	}

	private static ModPayloads.ServerSettings defaults() {
		return new ModPayloads.ServerSettings(600, 12 * 1024 * 1024, 128, true);
	}

	public static ModPayloads.ServerSettings get() {
		return settings;
	}

	public static void set(ModPayloads.ServerSettings value) {
		settings = value;
	}

	public static void reset() {
		settings = defaults();
	}

	public static int bitrateKbps() {
		return Math.min(ClientConfig.get().bitrateKbps, settings.maxBitrateKbps());
	}
}
