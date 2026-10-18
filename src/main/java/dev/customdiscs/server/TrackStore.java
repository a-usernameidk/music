package dev.customdiscs.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.util.Hashing;

/** Stores uploaded tracks as {@code <world>/data/custom_discs/<sha256>.ogg}. */
public final class TrackStore {
	private static @Nullable Path directory;

	private TrackStore() {
	}

	public static void open(MinecraftServer server) {
		directory = server.getWorldPath(LevelResource.DATA).resolve(CustomDiscs.MOD_ID);

		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			CustomDiscs.LOGGER.error("Could not create track folder {}", directory, e);
		}
	}

	public static void close() {
		directory = null;
	}

	private static @Nullable Path path(String hash) {
		if (directory == null || !Hashing.isValidHash(hash)) {
			return null;
		}

		return directory.resolve(hash + ".ogg");
	}

	public static boolean has(String hash) {
		Path path = path(hash);
		return path != null && Files.isRegularFile(path);
	}

	public static byte @Nullable [] read(String hash) {
		Path path = path(hash);

		if (path == null || !Files.isRegularFile(path)) {
			return null;
		}

		try {
			byte[] data = Files.readAllBytes(path);
			touch(hash);
			return data;
		} catch (IOException e) {
			CustomDiscs.LOGGER.error("Could not read track {}", path, e);
			return null;
		}
	}

	public static boolean write(String hash, byte[] data) {
		Path path = path(hash);

		if (path == null) {
			return false;
		}

		try {
			Path temp = path.resolveSibling(hash + ".tmp");
			Files.write(temp, data);
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			CustomDiscs.LOGGER.error("Could not write track {}", path, e);
			return false;
		}
	}

	/** Marks a track as recently used, for {@code deleteUnusedTracksAfterDays}. */
	public static void touch(String hash) {
		Path path = path(hash);

		if (path != null) {
			try {
				Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
			} catch (IOException ignored) {
				// Not important.
			}
		}
	}

	public static void deleteUnused(int days) {
		if (directory == null || days <= 0) {
			return;
		}

		Instant cutoff = Instant.now().minus(Duration.ofDays(days));

		try (Stream<Path> files = Files.list(directory)) {
			files.filter(p -> p.getFileName().toString().endsWith(".ogg")).forEach(p -> {
				try {
					if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
						Files.delete(p);
						CustomDiscs.LOGGER.info("Deleted unused track {}", p.getFileName());
					}
				} catch (IOException e) {
					CustomDiscs.LOGGER.warn("Could not check or delete {}", p, e);
				}
			});
		} catch (IOException e) {
			CustomDiscs.LOGGER.warn("Could not list {}", directory, e);
		}
	}
}
