package dev.customdiscs.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import dev.customdiscs.CustomDiscs;

/** Server settings, stored in {@code config/custom_discs-server.json}. */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("custom_discs-server.json");

	/** Hard cap: the shared jukebox song entry is 2 hours long, so vanilla never stops a disc early. */
	public static final int ABSOLUTE_MAX_DURATION_SECONDS = 7200;

	private static ServerConfig instance = new ServerConfig();

	/** Longest track that may be burned. */
	public int maxDurationSeconds = 600;
	/** Largest compressed file that may be uploaded. */
	public int maxFileSizeMb = 12;
	/** Highest bitrate clients should encode at. Clients use the lower of this and their own setting. */
	public int maxBitrateKbps = 128;
	/** Only operators (permission level 2+) may burn discs. Also checks the "custom_discs.burn" permission node. */
	public boolean opsOnly = false;
	/** Seconds a player must wait between two burns. */
	public int burnCooldownSeconds = 10;
	/**
	 * Chunks (about 30 KB each) a transfer may have in flight before waiting for an acknowledgement. Lower
	 * values are gentler on slow relayed connections such as Essential or e4mc.
	 */
	public int transferWindowChunks = 4;
	/** Delete stored tracks that nobody has burned, played or downloaded for this many days. 0 = never. */
	public int deleteUnusedTracksAfterDays = 0;

	public static ServerConfig get() {
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				ServerConfig loaded = GSON.fromJson(Files.readString(PATH), ServerConfig.class);

				if (loaded != null) {
					instance = loaded;
				}
			}
		} catch (Exception e) {
			CustomDiscs.LOGGER.error("Could not read {}, using defaults", PATH, e);
		}

		instance.maxDurationSeconds = Math.clamp(instance.maxDurationSeconds, 1, ABSOLUTE_MAX_DURATION_SECONDS);
		instance.maxFileSizeMb = Math.clamp(instance.maxFileSizeMb, 1, 256);
		instance.maxBitrateKbps = Math.clamp(instance.maxBitrateKbps, 32, 320);
		instance.transferWindowChunks = Math.clamp(instance.transferWindowChunks, 1, 64);
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(instance));
		} catch (IOException e) {
			CustomDiscs.LOGGER.error("Could not write {}", PATH, e);
		}
	}

	public long maxFileBytes() {
		return maxFileSizeMb * 1024L * 1024L;
	}
}
