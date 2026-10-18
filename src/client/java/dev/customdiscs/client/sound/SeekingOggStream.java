package dev.customdiscs.client.sound;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;

/**
 * Streams a cached Ogg Vorbis file with Minecraft's own decoder, starting part way in so that players who
 * arrive in the middle of a song hear the same part as everyone else.
 */
final class SeekingOggStream implements AudioStream {
	private static final int SKIP_CHUNK = 64 * 1024;

	private final JOrbisAudioStream decoder;
	private @Nullable ByteBuffer pending;

	SeekingOggStream(Path file) throws IOException {
		this.decoder = new JOrbisAudioStream(new BufferedInputStream(Files.newInputStream(file)));
	}

	/** Decodes and throws away audio up to {@code seconds}. Returns false if the track is already over. */
	boolean skip(double seconds) throws IOException {
		if (seconds <= 0) {
			return true;
		}

		AudioFormat format = decoder.getFormat();
		int frameSize = Math.max(1, format.getFrameSize());
		long toSkip = (long) (seconds * format.getSampleRate()) * frameSize;

		if (pending != null) {
			int fromPending = (int) Math.min(toSkip, pending.remaining()) / frameSize * frameSize;
			pending.position(pending.position() + fromPending);
			toSkip -= fromPending;

			if (pending.hasRemaining()) {
				return true;
			}

			pending = null;
		}

		while (toSkip > 0) {
			ByteBuffer buffer = decoder.read(SKIP_CHUNK);

			if (buffer == null || !buffer.hasRemaining()) {
				return false;
			}

			if (buffer.remaining() > toSkip) {
				buffer.position(buffer.position() + (int) toSkip);
				pending = buffer;
				return true;
			}

			toSkip -= buffer.remaining();
		}

		return true;
	}

	@Override
	public AudioFormat getFormat() {
		return decoder.getFormat();
	}

	@Override
	public ByteBuffer read(int capacity) throws IOException {
		if (pending != null) {
			ByteBuffer buffer = pending;
			pending = null;
			return buffer;
		}

		return decoder.read(capacity);
	}

	@Override
	public void close() throws IOException {
		decoder.close();
	}
}
