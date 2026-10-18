package dev.customdiscs.client.sound;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.TrackInfo;
import dev.customdiscs.client.net.TrackCache;
import dev.customdiscs.net.ModPayloads;

/** Client side of jukebox playback: fetches the track if needed and plays it at the jukebox. */
public final class JukeboxSounds {
	private static final Map<BlockPos, Active> ACTIVE = new HashMap<>();

	private JukeboxSounds() {
	}

	private static final class Active {
		final TrackInfo track;
		final long startGameTime;
		@Nullable CustomDiscSoundInstance sound;

		Active(TrackInfo track, long startGameTime) {
			this.track = track;
			this.startGameTime = startGameTime;
		}

		boolean sameAs(ModPayloads.PlayCustomDisc payload) {
			return track.equals(payload.track()) && startGameTime == payload.startGameTime();
		}
	}

	public static void handlePlay(ModPayloads.PlayCustomDisc payload) {
		Minecraft minecraft = Minecraft.getInstance();
		BlockPos pos = payload.pos();
		Active existing = ACTIVE.get(pos);

		if (existing != null && existing.sameAs(payload)
				&& (existing.sound == null || minecraft.getSoundManager().isActive(existing.sound))) {
			return; // Already playing or downloading.
		}

		stop(pos);
		Active active = new Active(payload.track(), payload.startGameTime());
		ACTIVE.put(pos, active);

		// Vanilla shows its own "Now Playing" from the shared song entry; replace it with the real title.
		minecraft.gui.hud.setOverlayMessage(Component.translatable("record.nowPlaying", payload.track().displayName()), true);

		TrackCache.request(payload.track().hash(), file -> {
			if (ACTIVE.get(pos) != active) {
				return; // Stopped or replaced while downloading.
			}

			if (CustomDiscSoundInstance.secondsSinceStart(active.startGameTime) * 20 >= active.track.durationTicks()) {
				ACTIVE.remove(pos);
				return;
			}

			CustomDiscSoundInstance sound = new CustomDiscSoundInstance(pos, file, active.startGameTime);
			active.sound = sound;
			minecraft.getSoundManager().play(sound);
		}, () -> {
			CustomDiscs.LOGGER.warn("Could not get track {} for the jukebox at {}", payload.track().hash(), pos);

			if (ACTIVE.get(pos) == active) {
				ACTIVE.remove(pos);
			}
		});
	}

	public static void handleStop(ModPayloads.StopCustomDisc payload) {
		stop(payload.pos());
	}

	private static void stop(BlockPos pos) {
		Active active = ACTIVE.remove(pos);

		if (active != null && active.sound != null) {
			Minecraft.getInstance().getSoundManager().stop(active.sound);
		}
	}

	public static void clear() {
		ACTIVE.values().forEach(active -> {
			if (active.sound != null) {
				Minecraft.getInstance().getSoundManager().stop(active.sound);
			}
		});
		ACTIVE.clear();
	}
}
