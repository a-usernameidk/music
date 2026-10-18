package dev.customdiscs.client.sound;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import dev.customdiscs.CustomDiscs;

/**
 * A burned disc playing in a jukebox. It sits at the jukebox like a vanilla disc (linear falloff to about 64
 * blocks, "Jukebox/Note Blocks" volume slider) and streams the cached file through Fabric's custom audio
 * stream hook.
 */
public final class CustomDiscSoundInstance extends AbstractSoundInstance {
	/** Defined in sounds.json as a streamed placeholder, so the sound engine resolves it and asks us for the stream. */
	public static final Identifier SOUND_ID = CustomDiscs.id("music_disc.burned");

	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Custom Discs seek");
		thread.setDaemon(true);
		return thread;
	});

	private final Path file;
	private final long startGameTime;

	public CustomDiscSoundInstance(BlockPos pos, Path file, long startGameTime) {
		super(SOUND_ID, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
		this.file = file;
		this.startGameTime = startGameTime;
		this.x = pos.getX() + 0.5;
		this.y = pos.getY() + 0.5;
		this.z = pos.getZ() + 0.5;
		// Same as vanilla jukebox songs: volume 4 makes the sound reach 4 x 16 = 64 blocks.
		this.volume = 4.0F;
		this.pitch = 1.0F;
		this.looping = false;
		this.delay = 0;
		this.relative = false;
		this.attenuation = SoundInstance.Attenuation.LINEAR;
	}

	static double secondsSinceStart(long startGameTime) {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 0 : Math.max(0, level.getGameTime() - startGameTime) / 20.0;
	}

	@Override
	public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary library, Identifier id, boolean repeatInstantly) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				SeekingOggStream stream = new SeekingOggStream(file);
				long began = System.nanoTime();

				if (!stream.skip(secondsSinceStart(startGameTime))) {
					return stream; // Already over: the stream is at its end and the sound stops straight away.
				}

				// Decoding to the seek point takes a moment on long songs; catch up on that time too.
				stream.skip((System.nanoTime() - began) / 1e9);
				return stream;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, DECODER);
	}
}
