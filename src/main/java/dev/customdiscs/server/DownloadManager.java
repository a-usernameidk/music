package dev.customdiscs.server;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.customdiscs.config.ServerConfig;
import dev.customdiscs.net.ModPayloads;
import dev.customdiscs.util.Hashing;

/**
 * Sends tracks to clients that asked for them. Each player gets one transfer at a time, with at most
 * {@code transferWindowChunks} unacknowledged chunks in flight, so a slow relay only ever has a little queued.
 */
public final class DownloadManager {
	private static final int MAX_QUEUED_PER_PLAYER = 16;
	private static final int MAX_CHUNKS_PER_TICK = 2;
	private static final long TIMEOUT_MS = 30_000;

	private static final Map<UUID, Deque<Download>> QUEUES = new HashMap<>();

	private DownloadManager() {
	}

	private static final class Download {
		final String hash;
		byte[] data;
		int chunkCount;
		int nextSeq;
		int acked;
		long lastActivity = System.currentTimeMillis();

		Download(String hash) {
			this.hash = hash;
		}
	}

	public static void handleRequest(ServerPlayer player, ModPayloads.RequestTrack request) {
		String hash = request.hash();

		if (!Hashing.isValidHash(hash) || !TrackStore.has(hash)) {
			ServerPlayNetworking.send(player, new ModPayloads.TrackUnavailable(hash));
			return;
		}

		Deque<Download> queue = QUEUES.computeIfAbsent(player.getUUID(), uuid -> new ArrayDeque<>());

		if (queue.size() >= MAX_QUEUED_PER_PLAYER || queue.stream().anyMatch(d -> d.hash.equals(hash))) {
			return;
		}

		queue.add(new Download(hash));
	}

	public static void handleAck(ServerPlayer player, ModPayloads.TrackChunkAck ack) {
		Deque<Download> queue = QUEUES.get(player.getUUID());
		Download active = queue == null ? null : queue.peekFirst();

		if (active != null && active.hash.equals(ack.hash()) && ack.seq() >= active.acked && ack.seq() < active.nextSeq) {
			active.acked = ack.seq() + 1;
			active.lastActivity = System.currentTimeMillis();
		}
	}

	public static void tick(MinecraftServer server) {
		int window = ServerConfig.get().transferWindowChunks;
		long now = System.currentTimeMillis();

		QUEUES.entrySet().removeIf(entry -> {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Deque<Download> queue = entry.getValue();

			if (player == null) {
				return true;
			}

			Download active = queue.peekFirst();

			if (active == null) {
				return true;
			}

			if (active.data == null) {
				active.data = TrackStore.read(active.hash);

				if (active.data == null) {
					ServerPlayNetworking.send(player, new ModPayloads.TrackUnavailable(active.hash));
					queue.pollFirst();
					return queue.isEmpty();
				}

				active.chunkCount = Math.max(1, (active.data.length + ModPayloads.CHUNK_SIZE - 1) / ModPayloads.CHUNK_SIZE);
				active.lastActivity = now;
			}

			if (active.acked >= active.chunkCount) {
				queue.pollFirst();
				return queue.isEmpty();
			}

			if (now - active.lastActivity > TIMEOUT_MS) {
				// Client stopped acknowledging; it will ask again when it needs the track.
				queue.pollFirst();
				return queue.isEmpty();
			}

			int sent = 0;

			while (sent < MAX_CHUNKS_PER_TICK && active.nextSeq < active.chunkCount && active.nextSeq - active.acked < window) {
				int from = active.nextSeq * ModPayloads.CHUNK_SIZE;
				int to = Math.min(active.data.length, from + ModPayloads.CHUNK_SIZE);
				ServerPlayNetworking.send(player, new ModPayloads.TrackChunk(active.hash, active.nextSeq, active.data.length, Arrays.copyOfRange(active.data, from, to)));
				active.nextSeq++;
				sent++;
			}

			return false;
		});
	}

	public static void onDisconnect(UUID player) {
		QUEUES.remove(player);
	}

	public static void clear() {
		QUEUES.clear();
	}
}
