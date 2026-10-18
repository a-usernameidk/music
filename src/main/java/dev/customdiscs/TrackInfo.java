package dev.customdiscs;

import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.TooltipFlag;

/**
 * The identity of a burned disc, stored in the {@code custom_discs:track} data component.
 *
 * @param hash          SHA-256 of the Ogg Vorbis file (lowercase hex). It is the track id: identical uploads share a file.
 * @param title         song title typed in the Disc Burner
 * @param artist        artist typed in the Disc Burner (may be empty)
 * @param durationTicks length of the audio in game ticks, measured by the server from the file itself
 */
public record TrackInfo(String hash, String title, String artist, int durationTicks) {
	public static final int MAX_TEXT_LENGTH = 64;

	public static final Codec<TrackInfo> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("hash").forGetter(TrackInfo::hash),
			Codec.STRING.fieldOf("title").forGetter(TrackInfo::title),
			Codec.STRING.optionalFieldOf("artist", "").forGetter(TrackInfo::artist),
			Codec.INT.fieldOf("duration_ticks").forGetter(TrackInfo::durationTicks)
	).apply(instance, TrackInfo::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrackInfo> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(64), TrackInfo::hash,
			ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH * 4), TrackInfo::title,
			ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH * 4), TrackInfo::artist,
			ByteBufCodecs.VAR_INT, TrackInfo::durationTicks,
			TrackInfo::new
	);

	/** "Artist - Title", or just the title when there is no artist. */
	public Component displayName() {
		return displayName(title, artist);
	}

	public static Component displayName(String title, String artist) {
		if (artist == null || artist.isBlank()) {
			return Component.literal(title);
		}

		return Component.translatable("item.custom_discs.custom_music_disc.desc", artist, title);
	}

	public static String formatDuration(int ticks) {
		int seconds = Math.max(0, ticks / 20);
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	/** Called through the tooltip provider registered in {@link ModRegistry#register()}. */
	public void appendTooltip(Consumer<Component> consumer, TooltipFlag flag) {
		// Same style as the vanilla disc tooltip line ("C418 - cat" in gray).
		consumer.accept(displayName().copy().withStyle(ChatFormatting.GRAY));

		if (flag.isAdvanced()) {
			consumer.accept(Component.literal(formatDuration(durationTicks) + "  " + hash.substring(0, Math.min(12, hash.length()))).withStyle(ChatFormatting.DARK_GRAY));
		}
	}
}
