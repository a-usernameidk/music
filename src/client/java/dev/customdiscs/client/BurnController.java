package dev.customdiscs.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.TrackInfo;
import dev.customdiscs.client.audio.Ffmpeg;
import dev.customdiscs.client.audio.FfmpegDownloader;
import dev.customdiscs.client.net.TrackCache;
import dev.customdiscs.net.ModPayloads;
import dev.customdiscs.net.ModPayloads.UploadStatus;
import dev.customdiscs.util.Hashing;

/**
 * State behind the Disc Burner screen: the chosen file, the conversion, and the chunked upload. It lives outside
 * the screen so that resizing the window (which rebuilds the screen) does not lose progress.
 */
public final class BurnController {
	private static final int CLIENT_WINDOW_CHUNKS = 4;
	private static final ExecutorService WORKER = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "Custom Discs worker");
		thread.setDaemon(true);
		return thread;
	});

	public enum Phase {
		IDLE, PROBING, READY, CONVERTING, UPLOADING, DONE, ERROR, DOWNLOADING_FFMPEG
	}

	private static Phase phase = Phase.IDLE;
	private static double progress;
	private static Component message = Component.empty();

	private static @Nullable Path selectedFile;
	private static Ffmpeg.@Nullable ProbeResult probe;
	private static String suggestedTitle = "";
	private static String suggestedArtist = "";
	private static boolean ffmpegMissing;

	private static int nextUploadId = 1;
	private static @Nullable Upload upload;
	private static AtomicBoolean cancelFlag = new AtomicBoolean();

	private BurnController() {
	}

	private static final class Upload {
		final int id;
		final byte[] data;
		final String hash;
		int sent;
		int nextSeq;
		int acked;
		boolean started;

		Upload(int id, byte[] data, String hash) {
			this.id = id;
			this.data = data;
			this.hash = hash;
		}
	}

	public static Phase phase() {
		return phase;
	}

	public static double progress() {
		return progress;
	}

	public static Component message() {
		return message;
	}

	public static boolean isError() {
		return phase == Phase.ERROR;
	}

	public static boolean isBusy() {
		return phase == Phase.PROBING || phase == Phase.CONVERTING || phase == Phase.UPLOADING || phase == Phase.DOWNLOADING_FFMPEG;
	}

	public static @Nullable Path selectedFile() {
		return selectedFile;
	}

	public static Ffmpeg.@Nullable ProbeResult probe() {
		return probe;
	}

	public static boolean ffmpegMissing() {
		return ffmpegMissing;
	}

	/** Title/artist read from the file's tags, handed to the screen once. */
	public static String takeSuggestedTitle() {
		String value = suggestedTitle;
		suggestedTitle = "";
		return value;
	}

	public static String takeSuggestedArtist() {
		String value = suggestedArtist;
		suggestedArtist = "";
		return value;
	}

	private static void set(Phase newPhase, double newProgress, Component newMessage) {
		phase = newPhase;
		progress = newProgress;
		message = newMessage;
	}

	private static void fail(Component reason) {
		upload = null;
		set(Phase.ERROR, 0, reason);
	}

	private static void onClientThread(Runnable runnable) {
		Minecraft.getInstance().execute(runnable);
	}

	public static boolean isSupportedFile(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		return dot >= 0 && Ffmpeg.EXTENSIONS.contains(name.substring(dot + 1));
	}

	/** Shows a message in the status line without changing what is selected. */
	public static void showMessage(Component text) {
		if (!isBusy()) {
			set(phase == Phase.ERROR ? Phase.IDLE : phase, progress, text);
		}
	}

	/** A file was dropped on the screen or picked with Browse. */
	public static void selectFile(Path path) {
		if (isBusy()) {
			return;
		}

		if (!Files.isRegularFile(path)) {
			fail(Component.translatable("message.custom_discs.error.not_a_file"));
			return;
		}

		if (!isSupportedFile(path)) {
			fail(Component.translatable("message.custom_discs.error.unsupported", path.getFileName().toString()));
			return;
		}

		selectedFile = path;
		probe = null;
		Optional<Path> ffmpeg = Ffmpeg.locate();
		ffmpegMissing = ffmpeg.isEmpty();

		if (ffmpegMissing) {
			fail(Component.translatable(FfmpegDownloader.isSupported() ? "message.custom_discs.error.no_ffmpeg_download" : "message.custom_discs.error.no_ffmpeg_install"));
			return;
		}

		set(Phase.PROBING, 0, Component.translatable("message.custom_discs.reading"));

		WORKER.execute(() -> {
			try {
				Ffmpeg.ProbeResult result = Ffmpeg.probe(ffmpeg.get(), path);
				onClientThread(() -> onProbed(path, result));
			} catch (IOException | InterruptedException e) {
				CustomDiscs.LOGGER.warn("Could not read {}", path, e);
				onClientThread(() -> fail(Component.translatable("message.custom_discs.error.ffmpeg_failed", e.getMessage())));
			}
		});
	}

	private static void onProbed(Path path, Ffmpeg.ProbeResult result) {
		if (!path.equals(selectedFile)) {
			return;
		}

		if (!result.hasAudio()) {
			fail(Component.translatable("message.custom_discs.error.no_audio"));
			return;
		}

		int maxSeconds = ServerLimits.get().maxDurationSeconds();

		if (result.durationSeconds() > maxSeconds + 1) {
			fail(Component.translatable("message.custom_discs.error.too_long", TrackInfo.formatDuration(maxSeconds * 20)));
			return;
		}

		probe = result;

		if (ClientConfig.get().prefillFromTags) {
			suggestedTitle = result.title();
			suggestedArtist = result.artist();
		}

		set(Phase.READY, 0, Component.empty());
	}

	public static boolean canBurn() {
		return probe != null && selectedFile != null && !isBusy() && ServerLimits.get().canBurn();
	}

	public static void burn(BlockPos burnerPos, String title, String artist) {
		Path input = selectedFile;
		Ffmpeg.ProbeResult info = probe;
		Optional<Path> ffmpeg = Ffmpeg.locate();

		if (input == null || info == null || isBusy()) {
			return;
		}

		if (ffmpeg.isEmpty()) {
			ffmpegMissing = true;
			fail(Component.translatable("message.custom_discs.error.no_ffmpeg_install"));
			return;
		}

		AtomicBoolean cancelled = new AtomicBoolean();
		cancelFlag = cancelled;
		int bitrate = ServerLimits.bitrateKbps();
		set(Phase.CONVERTING, 0, Component.translatable("message.custom_discs.converting", bitrate));

		WORKER.execute(() -> {
			Path output = null;

			try {
				Files.createDirectories(ClientConfig.dataDir().resolve("tmp"));
				output = Files.createTempFile(ClientConfig.dataDir().resolve("tmp"), "burn", ".ogg");
				Ffmpeg.convert(ffmpeg.get(), input, output, bitrate, info.durationSeconds(),
						p -> onClientThread(() -> {
							if (phase == Phase.CONVERTING) {
								progress = p;
							}
						}),
						cancelled::get);
				byte[] data = Files.readAllBytes(output);
				String hash = Hashing.sha256(data);
				onClientThread(() -> {
					if (!cancelled.get()) {
						startUpload(burnerPos, title, artist, data, hash);
					}
				});
			} catch (Ffmpeg.ConversionException e) {
				onClientThread(() -> fail(Component.translatable("message.custom_discs.error.ffmpeg_failed", e.getMessage())));
			} catch (InterruptedException e) {
				// Cancelled.
			} catch (IOException e) {
				CustomDiscs.LOGGER.warn("Conversion of {} failed", input, e);
				onClientThread(() -> fail(Component.translatable("message.custom_discs.error.ffmpeg_failed", e.getMessage())));
			} finally {
				if (output != null) {
					try {
						Files.deleteIfExists(output);
					} catch (IOException ignored) {
						// Temp file; cleaned up next time.
					}
				}
			}
		});
	}

	private static void startUpload(BlockPos pos, String title, String artist, byte[] data, String hash) {
		int maxBytes = ServerLimits.get().maxFileBytes();

		if (data.length > maxBytes) {
			fail(Component.translatable("message.custom_discs.error.too_large", maxBytes / (1024 * 1024)));
			return;
		}

		// Keep a copy so this client never has to download its own disc.
		TrackCache.store(hash, data);

		upload = new Upload(nextUploadId++, data, hash);
		set(Phase.UPLOADING, 0, Component.translatable("message.custom_discs.uploading", String.format("%.1f", data.length / 1024.0 / 1024.0)));
		ClientPlayNetworking.send(new ModPayloads.UploadBegin(upload.id, pos, title, artist, hash, data.length));
	}

	private static void sendChunks(Upload current) {
		while (current.sent < current.data.length && current.nextSeq - current.acked < CLIENT_WINDOW_CHUNKS) {
			int to = Math.min(current.data.length, current.sent + ModPayloads.CHUNK_SIZE);
			ClientPlayNetworking.send(new ModPayloads.UploadChunk(current.id, current.nextSeq, Arrays.copyOfRange(current.data, current.sent, to)));
			current.sent = to;
			current.nextSeq++;
		}
	}

	public static void handleStatus(UploadStatus status) {
		Upload current = upload;

		if (current == null || current.id != status.uploadId()) {
			return;
		}

		switch (status.state()) {
			case UploadStatus.READY -> {
				current.started = true;
				sendChunks(current);
			}
			case UploadStatus.PROGRESS -> {
				// The server acknowledges every chunk in order, so the ack count follows from the bytes received.
				current.acked = (status.received() + ModPayloads.CHUNK_SIZE - 1) / ModPayloads.CHUNK_SIZE;
				progress = (double) status.received() / current.data.length;
				sendChunks(current);
			}
			case UploadStatus.DONE -> {
				upload = null;
				selectedFile = null;
				probe = null;
				set(Phase.DONE, 1, Component.translatable("message.custom_discs.done", status.message()));
			}
			default -> fail(status.message());
		}
	}

	/** Screen closed: stop converting or uploading, since the server needs the burner open to finish. */
	public static void cancel() {
		cancelFlag.set(true);
		Upload current = upload;

		if (current != null) {
			ClientPlayNetworking.send(new ModPayloads.UploadCancel(current.id));
			upload = null;
		}

		if (isBusy() && phase != Phase.DOWNLOADING_FFMPEG && phase != Phase.PROBING) {
			set(probe != null ? Phase.READY : Phase.IDLE, 0, Component.translatable("message.custom_discs.cancelled"));
		}
	}

	public static void downloadFfmpeg() {
		if (isBusy() || !FfmpegDownloader.isSupported()) {
			return;
		}

		set(Phase.DOWNLOADING_FFMPEG, 0, Component.translatable("message.custom_discs.downloading_ffmpeg"));

		WORKER.execute(() -> {
			try {
				FfmpegDownloader.download(p -> onClientThread(() -> {
					if (phase == Phase.DOWNLOADING_FFMPEG) {
						progress = p;
					}
				}));
				onClientThread(() -> {
					ffmpegMissing = false;
					set(Phase.IDLE, 0, Component.translatable("message.custom_discs.ffmpeg_ready"));

					if (selectedFile != null) {
						selectFile(selectedFile);
					}
				});
			} catch (IOException | InterruptedException e) {
				CustomDiscs.LOGGER.warn("ffmpeg download failed", e);
				onClientThread(() -> fail(Component.translatable("message.custom_discs.error.ffmpeg_download_failed", String.valueOf(e.getMessage()))));
			}
		});
	}

	/** Disconnected: forget everything tied to the old server. */
	public static void reset() {
		cancelFlag.set(true);
		upload = null;
		selectedFile = null;
		probe = null;
		set(Phase.IDLE, 0, Component.empty());
	}
}
