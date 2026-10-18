package dev.customdiscs.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.customdiscs.ModRegistry;
import dev.customdiscs.TrackInfo;
import dev.customdiscs.net.ModPayloads;

/**
 * Keeps track of jukeboxes that are playing a burned disc, and tells nearby players about them.
 *
 * <p>Vanilla does the jukebox logic (insert, eject, hoppers, comparator, redstone) through the shared
 * {@code custom_discs:burned} song. Every server tick the jukebox calls {@link #onJukeboxTick}; this stops the song
 * when the real track ends and refreshes the entry here. A jukebox that has not ticked for a couple of ticks has
 * stopped (ejected, broken or unloaded), and the players who were told about it get a stop packet. Anyone
 * who comes into range, including players who log in mid-song, gets the play packet with the start time so they
 * can seek to the right spot.
 */
public final class JukeboxTracker {
	/** Players closer than this hear about a playing disc. Vanilla discs are audible up to about 64 blocks. */
	private static final double START_RANGE = 80.0;
	/** Players further than this are told to stop, which frees the sound on their side. */
	private static final double STOP_RANGE = 112.0;

	private static final Map<Key, Playing> PLAYING = new HashMap<>();
	private static long serverTick;

	private JukeboxTracker() {
	}

	private record Key(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private static final class Playing {
		final TrackInfo track;
		final long startGameTime;
		final Set<UUID> notified = new HashSet<>();
		long lastSeenTick;

		Playing(TrackInfo track, long startGameTime) {
			this.track = track;
			this.startGameTime = startGameTime;
		}
	}

	/** Called from the start of {@code JukeboxBlockEntity.tick} on the server. */
	public static void onJukeboxTick(Level level, BlockPos pos, BlockState state, JukeboxBlockEntity jukebox) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		ItemStack stack = jukebox.getTheItem();
		JukeboxSongPlayer songPlayer = jukebox.getSongPlayer();

		if (!stack.is(ModRegistry.CUSTOM_MUSIC_DISC) || !songPlayer.isPlaying()) {
			return;
		}

		TrackInfo track = stack.get(ModRegistry.TRACK);
		long elapsed = songPlayer.getTicksSinceSongStarted();

		// The shared song entry is 2 hours long; stop at the real end of this track instead (same 1s margin as vanilla).
		if (track == null || elapsed >= track.durationTicks() + 20L) {
			songPlayer.stop(level, state);
			return;
		}

		Key key = new Key(serverLevel.dimension(), pos.immutable());
		long start = serverLevel.getGameTime() - elapsed;
		Playing playing = PLAYING.get(key);

		if (playing == null || !playing.track.equals(track) || Math.abs(playing.startGameTime - start) > 2) {
			if (playing != null) {
				sendStop(serverLevel.getServer(), key, playing);
			}

			playing = new Playing(track, start);
			PLAYING.put(key, playing);
			TrackStore.touch(track.hash());
		}

		playing.lastSeenTick = serverTick;
	}

	public static void tick(MinecraftServer server) {
		serverTick++;
		Iterator<Map.Entry<Key, Playing>> iterator = PLAYING.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<Key, Playing> entry = iterator.next();
			Key key = entry.getKey();
			Playing playing = entry.getValue();

			// lastSeenTick is set during the level tick, which runs before this end-of-server-tick handler.
			if (serverTick - playing.lastSeenTick > 2) {
				sendStop(server, key, playing);
				iterator.remove();
				continue;
			}

			double cx = key.pos.getX() + 0.5;
			double cy = key.pos.getY() + 0.5;
			double cz = key.pos.getZ() + 0.5;

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				boolean sameLevel = player.level().dimension() == key.dimension;
				double distanceSq = sameLevel ? player.distanceToSqr(cx, cy, cz) : Double.MAX_VALUE;
				boolean notified = playing.notified.contains(player.getUUID());

				if (!notified && distanceSq <= START_RANGE * START_RANGE) {
					ServerPlayNetworking.send(player, new ModPayloads.PlayCustomDisc(key.pos, playing.track, playing.startGameTime));
					playing.notified.add(player.getUUID());
				} else if (notified && distanceSq > STOP_RANGE * STOP_RANGE) {
					if (sameLevel) {
						ServerPlayNetworking.send(player, new ModPayloads.StopCustomDisc(key.pos));
					}

					playing.notified.remove(player.getUUID());
				}
			}

			// Forget players who left the server.
			playing.notified.removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
		}
	}

	private static void sendStop(MinecraftServer server, Key key, Playing playing) {
		for (UUID uuid : playing.notified) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);

			if (player != null && Objects.equals(player.level().dimension(), key.dimension)) {
				ServerPlayNetworking.send(player, new ModPayloads.StopCustomDisc(key.pos));
			}
		}

		playing.notified.clear();
	}

	public static void clear() {
		PLAYING.clear();
		serverTick = 0;
	}
}
