package dev.customdiscs.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads just enough of an Ogg file to check that it is a single Ogg Vorbis stream and to measure its length.
 * The server uses this to validate uploads instead of trusting what the client says.
 */
public record OggInfo(int channels, int sampleRate, long samples) {
	public double durationSeconds() {
		return (double) samples / sampleRate;
	}

	public int durationTicks() {
		return (int) Math.ceil(durationSeconds() * 20.0);
	}

	public static OggInfo parse(byte[] data) throws InvalidOggException {
		ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int offset = 0;
		int serial = 0;
		int channels = -1;
		int sampleRate = -1;
		long lastGranule = -1;
		boolean first = true;

		while (offset < data.length) {
			if (offset + 27 > data.length || data[offset] != 'O' || data[offset + 1] != 'g' || data[offset + 2] != 'g' || data[offset + 3] != 'S') {
				throw new InvalidOggException("not an Ogg file");
			}

			long granule = buf.getLong(offset + 6);
			int pageSerial = buf.getInt(offset + 14);
			int segments = data[offset + 26] & 0xFF;
			int headerSize = 27 + segments;

			if (offset + headerSize > data.length) {
				throw new InvalidOggException("truncated Ogg page");
			}

			int bodySize = 0;

			for (int i = 0; i < segments; i++) {
				bodySize += data[offset + 27 + i] & 0xFF;
			}

			int body = offset + headerSize;

			if (body + bodySize > data.length) {
				throw new InvalidOggException("truncated Ogg page");
			}

			if (first) {
				// Identification header: 0x01 "vorbis" version(4) channels(1) rate(4)
				if (bodySize < 16 || data[body] != 1 || !new String(data, body + 1, 6, StandardCharsets.US_ASCII).equals("vorbis")) {
					throw new InvalidOggException("not Ogg Vorbis audio");
				}

				channels = data[body + 11] & 0xFF;
				sampleRate = buf.getInt(body + 12);
				serial = pageSerial;
				first = false;
			} else if (pageSerial != serial) {
				throw new InvalidOggException("more than one stream in the file");
			}

			if (granule > lastGranule) {
				lastGranule = granule;
			}

			offset = body + bodySize;
		}

		if (channels <= 0 || sampleRate <= 0 || lastGranule <= 0) {
			throw new InvalidOggException("empty or corrupt Ogg Vorbis file");
		}

		return new OggInfo(channels, sampleRate, lastGranule);
	}

	public static final class InvalidOggException extends Exception {
		public InvalidOggException(String message) {
			super(message);
		}
	}
}
