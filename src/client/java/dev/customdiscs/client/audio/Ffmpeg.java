package dev.customdiscs.client.audio;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.customdiscs.client.ClientConfig;

/** Runs the ffmpeg executable to read audio files and convert them to mono Ogg Vorbis. */
public final class Ffmpeg {
	public static final List<String> EXTENSIONS = List.of("mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "aiff", "aif", "webm", "mka");

	private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
	private static final Pattern TAG = Pattern.compile("^\\s{4}(\\w+)\\s*:\\s?(.*)$");

	private Ffmpeg() {
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	public static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

	public static String executableName() {
		return isWindows() ? "ffmpeg.exe" : "ffmpeg";
	}

	/** Where {@link FfmpegDownloader} puts its copy. */
	public static Path managedPath() {
		return ClientConfig.dataDir().resolve("ffmpeg").resolve(executableName());
	}

	/** Finds ffmpeg: the config override, then the downloaded copy, then PATH and a few usual install folders. */
	public static Optional<Path> locate() {
		String override = ClientConfig.get().ffmpegPath;

		if (!override.isBlank()) {
			Path path = Path.of(override.strip());
			return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
		}

		if (Files.isRegularFile(managedPath())) {
			return Optional.of(managedPath());
		}

		List<String> dirs = new ArrayList<>();
		String envPath = System.getenv("PATH");

		if (envPath != null) {
			dirs.addAll(List.of(envPath.split(Pattern.quote(File.pathSeparator))));
		}

		// Launchers often start the game with a minimal PATH, so also try the usual places.
		dirs.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/snap/bin", "C:\\ffmpeg\\bin"));

		for (String dir : dirs) {
			if (dir.isBlank()) {
				continue;
			}

			try {
				Path candidate = Path.of(dir.strip()).resolve(executableName());

				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
					return Optional.of(candidate);
				}
			} catch (RuntimeException ignored) {
				// Malformed PATH entry.
			}
		}

		return Optional.empty();
	}

	/** What ffmpeg reports about a file. Tag values are empty strings and numbers 0 when missing. */
	public record ProbeResult(double durationSeconds, boolean hasAudio, String title, String artist, String album, String albumArtist, int trackNumber, int discNumber) {
	}

	/** "3/12" or "3" -> 3; anything else -> 0. */
	private static int leadingNumber(String value) {
		Matcher matcher = Pattern.compile("^\\s*(\\d+)").matcher(value);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	/** Reads duration and tags with {@code ffmpeg -i file} (it prints the info and exits with an error, which is expected). */
	public static ProbeResult probe(Path ffmpeg, Path input) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", input.toString())
				.redirectErrorStream(true)
				.start();

		double duration = -1;
		boolean hasAudio = false;
		boolean inInputSection = false;
		boolean inStream = false;
		String title = "";
		String artist = "";
		String albumArtist = "";
		String album = "";
		int trackNumber = 0;
		int discNumber = 0;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.startsWith("Input #")) {
					inInputSection = true;
				} else if (line.startsWith("Output #") || line.startsWith("At least one output")) {
					inInputSection = false;
				}

				if (!inInputSection) {
					continue;
				}

				Matcher duration_ = DURATION.matcher(line);

				if (duration_.find()) {
					duration = Integer.parseInt(duration_.group(1)) * 3600 + Integer.parseInt(duration_.group(2)) * 60 + Double.parseDouble(duration_.group(3));
				}

				String trimmed = line.strip();

				if (trimmed.startsWith("Stream #")) {
					inStream = true;

					if (trimmed.contains("Audio:")) {
						hasAudio = true;
					}
				}

				// File-level tags are indented by 4 spaces; stream tags come after "Stream #" lines and are ignored.
				Matcher tag = TAG.matcher(line);

				if (!inStream && tag.matches()) {
					String key = tag.group(1).toLowerCase(Locale.ROOT);
					String value = tag.group(2).strip();

					switch (key) {
						case "title" -> title = value;
						case "artist" -> artist = value;
						case "album_artist" -> albumArtist = value;
						case "album" -> album = value;
						case "track" -> trackNumber = leadingNumber(value);
						case "disc" -> discNumber = leadingNumber(value);
						default -> {
						}
					}
				}
			}
		}

		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
		}

		return new ProbeResult(duration, hasAudio, title, artist.isEmpty() ? albumArtist : artist, album, albumArtist, trackNumber, discNumber);
	}

	public static final class ConversionException extends Exception {
		public ConversionException(String message) {
			super(message);
		}
	}

	/**
	 * Converts {@code input} to mono 44.1 kHz Ogg Vorbis at {@code bitrateKbps}, without tags or cover art.
	 * Mono matters: Minecraft only places mono sounds in the world, so a stereo disc would be heard everywhere.
	 */
	public static void convert(Path ffmpeg, Path input, Path output, int bitrateKbps, double durationSeconds, DoubleConsumer progress, BooleanSupplier cancelled)
			throws IOException, InterruptedException, ConversionException {
		List<String> command = List.of(
				ffmpeg.toString(), "-hide_banner", "-nostdin", "-y",
				"-i", input.toString(),
				"-map", "0:a:0", "-vn", "-sn", "-dn",
				"-map_metadata", "-1", "-map_chapters", "-1",
				"-ac", "1", "-ar", "44100",
				"-c:a", "libvorbis", "-b:a", bitrateKbps + "k",
				"-progress", "pipe:1", "-nostats",
				"-f", "ogg", output.toString());

		Process process = new ProcessBuilder(command).start();
		Deque<String> errorTail = new ArrayDeque<>();

		Thread stderrReader = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;

				while ((line = reader.readLine()) != null) {
					synchronized (errorTail) {
						errorTail.addLast(line);

						if (errorTail.size() > 8) {
							errorTail.removeFirst();
						}
					}
				}
			} catch (IOException ignored) {
				// Process ended.
			}
		}, "Custom Discs ffmpeg stderr");
		stderrReader.setDaemon(true);
		stderrReader.start();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (cancelled.getAsBoolean()) {
					process.destroyForcibly();
					throw new InterruptedException("cancelled");
				}

				// "out_time_us" and the older, misnamed "out_time_ms" are both microseconds.
				if ((line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) && durationSeconds > 0) {
					try {
						long micros = Long.parseLong(line.substring(line.indexOf('=') + 1).strip());
						progress.accept(Math.clamp(micros / 1_000_000.0 / durationSeconds, 0.0, 1.0));
					} catch (NumberFormatException ignored) {
						// "N/A" at the start.
					}
				}
			}
		}

		int exit = process.waitFor();
		stderrReader.join(1000);

		if (exit != 0) {
			String tail;

			synchronized (errorTail) {
				tail = String.join("\n", errorTail);
			}

			if (tail.contains("Unknown encoder 'libvorbis'")) {
				throw new ConversionException("This ffmpeg build has no libvorbis encoder. Install a full build of ffmpeg.");
			}

			String last = errorTail.isEmpty() ? "exit code " + exit : errorTail.peekLast();
			throw new ConversionException(last);
		}
	}
}
