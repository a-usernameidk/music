package dev.customdiscs.client.sound;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.DiscInfo;
import dev.customdiscs.client.ClientConfig;
import dev.customdiscs.client.net.TrackCache;
import dev.customdiscs.net.ModPayloads;

/**
 * Client side of jukebox playback. Every tick it works out which track of each playing disc should be heard
 * right now (from the start time, the track lengths and the shuffle order), downloads it if needed, and plays
 * it from the right position. That one rule covers albums, shuffle, late joiners and unmuting mid-song.
 */
public final class JukeboxSounds {
	private static final Map<BlockPos, Active> ACTIVE = new HashMap<>();

	private JukeboxSounds() {
	}

	private static final class Active {
		final BlockPos pos;
		final DiscInfo disc;
		final long startGameTime;
		final int[] order;
		/** Position in {@link #order} that is playing or being fetched; -1 for none. */
		int playingSlot = -1;
		@Nullable CustomDiscSoundInstance sound;
		long lastStartGameTime = Long.MIN_VALUE;
		boolean mutedNoticeShown;

		Active(BlockPos pos, DiscInfo disc, long startGameTime) {
			this.pos = pos;
			this.disc = disc;
			this.startGameTime = startGameTime;
			this.order = disc.playOrder(pos, startGameTime);
		}

		boolean sameAs(ModPayloads.PlayCustomDisc payload) {
			return disc.equals(payload.disc()) && startGameTime == payload.startGameTime();
		}

		void stopSound() {
			if (sound != null) {
				Minecraft.getInstance().getSoundManager().stop(sound);
				sound = null;
			}

			playingSlot = -1;
		}
	}

	public static void handlePlay(ModPayloads.PlayCustomDisc payload) {
		Active existing = ACTIVE.get(payload.pos());

		if (existing != null && existing.sameAs(payload)) {
			return;
		}

		stop(payload.pos());
		ACTIVE.put(payload.pos(), new Active(payload.pos(), payload.disc(), payload.startGameTime()));
	}

	public static void handleStop(ModPayloads.StopCustomDisc payload) {
		stop(payload.pos());
	}

	private static void stop(BlockPos pos) {
		Active active = ACTIVE.remove(pos);

		if (active != null) {
			active.stopSound();
		}
	}

	public static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;

		if (level == null || ACTIVE.isEmpty()) {
			return;
		}

		boolean muteExplicit = ClientConfig.get().muteExplicitDiscs;

		for (Active active : ACTIVE.values()) {
			long elapsed = level.getGameTime() - active.startGameTime;

			if (active.disc.explicit() && muteExplicit) {
				active.stopSound();

				if (!active.mutedNoticeShown && isNear(minecraft, active.pos)) {
					minecraft.gui.hud.setOverlayMessage(Component.translatable("message.custom_discs.muted_explicit", active.disc.displayName()), false);
					active.mutedNoticeShown = true;
				}

				continue;
			}

			active.mutedNoticeShown = false;

			// Find the track that should be playing now.
			int slot = -1;
			long trackStart = 0;

			for (int i = 0; i < active.order.length; i++) {
				int length = active.disc.tracks().get(active.order[i]).durationTicks();

				if (elapsed < trackStart + length) {
					slot = i;
					break;
				}

				trackStart += length;
			}

			if (slot == -1) {
				active.stopSound();
				continue;
			}

			if (slot == active.playingSlot) {
				boolean stillPlaying = active.sound == null || minecraft.getSoundManager().isActive(active.sound);
				long remaining = trackStart + active.disc.tracks().get(active.order[slot]).durationTicks() - elapsed;

				// Restart only if the sound engine dropped it (e.g. resources reloaded) with plenty of the track left,
				// not when it simply finished a moment before the next track is due.
				if (stillPlaying || remaining < 60 || level.getGameTime() - active.lastStartGameTime < 100) {
					continue;
				}
			}

			active.stopSound();
			start(minecraft, active, slot, active.startGameTime + trackStart);
		}
	}

	private static boolean isNear(Minecraft minecraft, BlockPos pos) {
		return minecraft.player != null && minecraft.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64 * 64;
	}

	private static void start(Minecraft minecraft, Active active, int slot, long trackStartGameTime) {
		DiscInfo.Track track = active.disc.tracks().get(active.order[slot]);
		active.playingSlot = slot;
		active.lastStartGameTime = minecraft.level != null ? minecraft.level.getGameTime() : 0;

		if (isNear(minecraft, active.pos)) {
			minecraft.gui.hud.setNowPlaying(active.disc.trackDisplayName(track));
		}

		TrackCache.request(track.hash(), file -> {
			if (ACTIVE.get(active.pos) != active || active.playingSlot != slot || active.sound != null) {
				return; // Stopped, replaced, or moved on while downloading.
			}

			CustomDiscSoundInstance sound = new CustomDiscSoundInstance(active.pos, file, trackStartGameTime);
			active.sound = sound;
			minecraft.getSoundManager().play(sound);
		}, () -> CustomDiscs.LOGGER.warn("Could not get track {} for the jukebox at {}", track.hash(), active.pos));

		// Fetch the next track in the background so it is ready on time.
		if (slot + 1 < active.order.length) {
			TrackCache.request(active.disc.tracks().get(active.order[slot + 1]).hash(), file -> { }, () -> { });
		}
	}

	public static void clear() {
		ACTIVE.values().forEach(Active::stopSound);
		ACTIVE.clear();
	}
}
