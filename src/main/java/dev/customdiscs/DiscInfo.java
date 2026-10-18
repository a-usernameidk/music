package dev.customdiscs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.TooltipFlag;

/**
 * What is burned on a disc, stored in the {@code custom_discs:disc} data component. A single song is a disc with
 * one track; an album has several, played one after another (or shuffled).
 *
 * @param title    song title for a single, album title for an album
 * @param artist   may be empty
 * @param explicit flagged as explicit by the person who burned it; players can choose to mute these
 * @param shuffle  play the tracks in a random order; a new order every time the disc starts
 * @param tracks   at least one track
 */
public record DiscInfo(String title, String artist, boolean explicit, boolean shuffle, List<Track> tracks) {
	public static final int MAX_TEXT_LENGTH = 64;
	public static final int MAX_TRACKS = 64;

	/**
	 * @param hash          SHA-256 of the Ogg Vorbis file (lowercase hex); identical files share one copy
	 * @param title         track title (for a single, the same as the disc title)
	 * @param durationTicks length in game ticks, measured by the server from the file itself
	 */
	public record Track(String hash, String title, int durationTicks) {
		public static final Codec<Track> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("hash").forGetter(Track::hash),
				Codec.STRING.fieldOf("title").forGetter(Track::title),
				Codec.INT.fieldOf("duration_ticks").forGetter(Track::durationTicks)
		).apply(instance, Track::new));

		public static final StreamCodec<RegistryFriendlyByteBuf, Track> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), Track::hash,
				ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH * 4), Track::title,
				ByteBufCodecs.VAR_INT, Track::durationTicks,
				Track::new
		);
	}

	public static final Codec<DiscInfo> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("title").forGetter(DiscInfo::title),
			Codec.STRING.optionalFieldOf("artist", "").forGetter(DiscInfo::artist),
			Codec.BOOL.optionalFieldOf("explicit", false).forGetter(DiscInfo::explicit),
			Codec.BOOL.optionalFieldOf("shuffle", false).forGetter(DiscInfo::shuffle),
			Track.CODEC.listOf(1, MAX_TRACKS).fieldOf("tracks").forGetter(DiscInfo::tracks)
	).apply(instance, DiscInfo::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, DiscInfo> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH * 4), DiscInfo::title,
			ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH * 4), DiscInfo::artist,
			ByteBufCodecs.BOOL, DiscInfo::explicit,
			ByteBufCodecs.BOOL, DiscInfo::shuffle,
			Track.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TRACKS)), DiscInfo::tracks,
			DiscInfo::new
	);

	public DiscInfo {
		tracks = List.copyOf(tracks);
	}

	public boolean isAlbum() {
		return tracks.size() > 1;
	}

	public int totalTicks() {
		int total = 0;

		for (Track track : tracks) {
			total += track.durationTicks();
		}

		return total;
	}

	public DiscInfo withShuffle(boolean value) {
		return new DiscInfo(title, artist, explicit, value, tracks);
	}

	/**
	 * The order tracks play in, as indexes into {@link #tracks}. With shuffle on, the order comes from the
	 * jukebox position and start time, so the server and every client work out the same order without sending it.
	 */
	public int[] playOrder(BlockPos pos, long startGameTime) {
		List<Integer> order = new ArrayList<>();

		for (int i = 0; i < tracks.size(); i++) {
			order.add(i);
		}

		if (shuffle && tracks.size() > 1) {
			Collections.shuffle(order, new Random(pos.asLong() * 31 + startGameTime));
		}

		return order.stream().mapToInt(Integer::intValue).toArray();
	}

	/** "Artist - Title", or just the title when there is no artist. */
	public Component displayName() {
		return displayName(title, artist);
	}

	public Component trackDisplayName(Track track) {
		return displayName(track.title(), artist);
	}

	public static Component displayName(String title, String artist) {
		if (artist == null || artist.isBlank()) {
			return Component.literal(title);
		}

		return Component.translatable("item.custom_discs.custom_music_disc.desc", artist, title);
	}

	public static String formatDuration(int ticks) {
		int seconds = Math.max(0, ticks / 20);

		if (seconds >= 3600) {
			return String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
		}

		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	/** Called through the tooltip provider registered in {@link ModRegistry#register()}. */
	public void appendTooltip(Consumer<Component> consumer, TooltipFlag flag) {
		// Same style as the vanilla disc tooltip line ("C418 - cat" in gray).
		consumer.accept(displayName().copy().withStyle(ChatFormatting.GRAY));

		if (explicit) {
			consumer.accept(Component.translatable("item.custom_discs.custom_music_disc.explicit").withStyle(ChatFormatting.RED));
		}

		if (isAlbum()) {
			consumer.accept(Component.translatable("item.custom_discs.custom_music_disc.album", tracks.size(), formatDuration(totalTicks())).withStyle(ChatFormatting.DARK_GRAY));
			int shown = Math.min(tracks.size(), 12);

			for (int i = 0; i < shown; i++) {
				Track track = tracks.get(i);
				consumer.accept(Component.literal((i + 1) + ". " + track.title() + "  " + formatDuration(track.durationTicks())).withStyle(ChatFormatting.DARK_GRAY));
			}

			if (shown < tracks.size()) {
				consumer.accept(Component.translatable("item.custom_discs.custom_music_disc.more", tracks.size() - shown).withStyle(ChatFormatting.DARK_GRAY));
			}

			consumer.accept(Component.translatable(shuffle ? "item.custom_discs.custom_music_disc.shuffle_on" : "item.custom_discs.custom_music_disc.shuffle_off").withStyle(ChatFormatting.DARK_AQUA));
		} else if (flag.isAdvanced()) {
			consumer.accept(Component.literal(formatDuration(totalTicks())).withStyle(ChatFormatting.DARK_GRAY));
		}
	}
}
