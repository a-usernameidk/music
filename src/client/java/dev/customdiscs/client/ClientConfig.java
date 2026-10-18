package dev.customdiscs.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import dev.customdiscs.CustomDiscs;

/** Client settings, stored in {@code config/custom_discs-client.json}. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("custom_discs-client.json");

	private static ClientConfig instance = new ClientConfig();

	/** Downloaded tracks are kept up to this size; the least recently played are deleted first. */
	public int maxCacheSizeMb = 512;
	/** Full path to an ffmpeg executable. Empty = look in .minecraft/custom_discs/ffmpeg, then on PATH. */
	public String ffmpegPath = "";
	/** Ogg Vorbis bitrate for burned discs. The server may cap it. Mono 96 kbps sounds close to a 192 kbps stereo MP3 in game. */
	public int bitrateKbps = 96;
	/** Fill in Title and Artist from the file's tags when they are empty. */
	public boolean prefillFromTags = true;
	/** Don't play discs that their creator marked as explicit. */
	public boolean muteExplicitDiscs = false;

	public static ClientConfig get() {
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				ClientConfig loaded = GSON.fromJson(Files.readString(PATH), ClientConfig.class);

				if (loaded != null) {
					instance = loaded;
				}
			}
		} catch (Exception e) {
			CustomDiscs.LOGGER.error("Could not read {}, using defaults", PATH, e);
		}

		if (instance.ffmpegPath == null) {
			instance.ffmpegPath = "";
		}

		instance.bitrateKbps = Math.clamp(instance.bitrateKbps, 32, 320);
		instance.maxCacheSizeMb = Math.max(16, instance.maxCacheSizeMb);
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

	/** {@code .minecraft/custom_discs}, home of the cache and a downloaded ffmpeg. */
	public static Path dataDir() {
		return FabricLoader.getInstance().getGameDir().resolve(CustomDiscs.MOD_ID);
	}
}
