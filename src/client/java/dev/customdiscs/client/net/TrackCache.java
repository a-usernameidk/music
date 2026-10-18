package dev.customdiscs.client.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.client.ClientConfig;
import dev.customdiscs.net.ModPayloads;
import dev.customdiscs.util.Hashing;

/**
 * Local cache of tracks at {@code .minecraft/custom_discs/cache/<sha256>.ogg}. A track is downloaded from the
 * server only the first time it needs to play, and the hash in the file name is checked after every download.
 */
public final class TrackCache {
	private static final long TIMEOUT_MS = 60_000;
	private static final Map<String, Pending> PENDING = new HashMap<>();

	private TrackCache() {
	}

	private static final class Pending {
		final List<Consumer<Path>> onReady = new ArrayList<>();
		final List<Runnable> onFail = new ArrayList<>();
		byte[] data;
		int received;
		int nextSeq;
		long lastActivity = System.currentTimeMillis();
	}

	public static Path dir() {
		return ClientConfig.dataDir().resolve("cache");
	}

	public static Path path(String hash) {
		return dir().resolve(hash + ".ogg");
	}

	/** Calls {@code onReady} with the cached file, downloading it first if needed. Must be called on the client thread. */
	public static void request(String hash, Consumer<Path> onReady, Runnable onFail) {
		if (!Hashing.isValidHash(hash)) {
			onFail.run();
			return;
		}

		Path path = path(hash);

		if (Files.isRegularFile(path)) {
			touch(path);
			onReady.accept(path);
			return;
		}

		Pending pending = PENDING.get(hash);
		boolean isNew = pending == null;

		if (isNew) {
			pending = new Pending();
			PENDING.put(hash, pending);
		}

		pending.onReady.add(onReady);
		pending.onFail.add(onFail);

		if (isNew) {
			ClientPlayNetworking.send(new ModPayloads.RequestTrack(hash));
		}
	}

	public static void handleChunk(ModPayloads.TrackChunk chunk) {
		Pending pending = PENDING.get(chunk.hash());

		if (pending == null) {
			return;
		}

		if (pending.data == null) {
			if (chunk.totalSize() <= 0 || chunk.totalSize() > 256 * 1024 * 1024) {
				fail(chunk.hash());
				return;
			}

			pending.data = new byte[chunk.totalSize()];
		}

		if (chunk.seq() != pending.nextSeq || pending.received + chunk.data().length > pending.data.length) {
			// Out of order should not happen on a TCP connection; start over next time it is needed.
			fail(chunk.hash());
			return;
		}

		System.arraycopy(chunk.data(), 0, pending.data, pending.received, chunk.data().length);
		pending.received += chunk.data().length;
		pending.nextSeq++;
		pending.lastActivity = System.currentTimeMillis();
		ClientPlayNetworking.send(new ModPayloads.TrackChunkAck(chunk.hash(), chunk.seq()));

		if (pending.received < pending.data.length) {
			return;
		}

		PENDING.remove(chunk.hash());

		if (!Hashing.sha256(pending.data).equals(chunk.hash())) {
			CustomDiscs.LOGGER.warn("Downloaded track {} has the wrong checksum", chunk.hash());
			pending.onFail.forEach(Runnable::run);
			return;
		}

		Path path = store(chunk.hash(), pending.data);

		if (path == null) {
			pending.onFail.forEach(Runnable::run);
		} else {
			pending.onReady.forEach(callback -> callback.accept(path));
		}
	}

	public static void handleUnavailable(ModPayloads.TrackUnavailable payload) {
		fail(payload.hash());
	}

	private static void fail(String hash) {
		Pending pending = PENDING.remove(hash);

		if (pending != null) {
			pending.onFail.forEach(Runnable::run);
		}
	}

	/** Writes a track into the cache (used for downloads and for discs this client just burned). */
	public static Path store(String hash, byte[] data) {
		try {
			Files.createDirectories(dir());
			Path path = path(hash);
			Path temp = dir().resolve(hash + ".tmp");
			Files.write(temp, data);
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			evict();
			return path;
		} catch (IOException e) {
			CustomDiscs.LOGGER.error("Could not save track {} to the cache", hash, e);
			return null;
		}
	}

	private static void touch(Path path) {
		try {
			Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
		} catch (IOException ignored) {
			// Only affects which tracks get evicted first.
		}
	}

	private static void evict() {
		long limit = ClientConfig.get().maxCacheSizeMb * 1024L * 1024L;

		try (Stream<Path> files = Files.list(dir())) {
			List<Path> tracks = new ArrayList<>(files.filter(p -> p.getFileName().toString().endsWith(".ogg")).toList());
			tracks.sort(Comparator.comparingLong(TrackCache::lastModified).reversed());
			long total = 0;

			for (Path track : tracks) {
				total += Files.size(track);

				if (total > limit) {
					Files.deleteIfExists(track);
				}
			}
		} catch (IOException e) {
			CustomDiscs.LOGGER.warn("Could not clean the track cache", e);
		}
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	public static void tick() {
		long now = System.currentTimeMillis();
		List<String> expired = PENDING.entrySet().stream()
				.filter(entry -> now - entry.getValue().lastActivity > TIMEOUT_MS)
				.map(Map.Entry::getKey)
				.toList();
		expired.forEach(TrackCache::fail);
	}

	public static void clear() {
		PENDING.clear();
	}
}
