package dev.customdiscs.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
import dev.customdiscs.DiscInfo;
import dev.customdiscs.client.audio.Ffmpeg;
import dev.customdiscs.client.audio.FfmpegDownloader;
import dev.customdiscs.client.net.TrackCache;
import dev.customdiscs.net.ModPayloads;
import dev.customdiscs.net.ModPayloads.UploadStatus;
import dev.customdiscs.util.Hashing;

/**
 * State behind the Disc Burner screen: the chosen files, the conversion, the chunked uploads and the final burn
 * request. It lives outside the screen so that resizing the window (which rebuilds the screen) keeps progress.
 *
 * <p>One file makes a single; several files make an album disc. Each track is converted, then uploaded on its
 * own (the server skips files it already has), and finally {@link ModPayloads.BurnDisc} lists them all.
 */
public final class BurnController {
	private static final int CLIENT_WINDOW_CHUNKS = 4;
	private static final ExecutorService WORKER = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "Custom Discs worker");
		thread.setDaemon(true);
		return thread;
	});

	public enum Phase {
		IDLE, PROBING, READY, CONVERTING, UPLOADING, BURNING, DONE, ERROR, DOWNLOADING_FFMPEG
	}

	/** A chosen file and what ffmpeg says about it. */
	public record Selected(Path path, Ffmpeg.ProbeResult probe) {
		public String trackTitle() {
			if (!probe.title().isBlank()) {
				return probe.title();
			}

			String name = path.getFileName().toString();
			int dot = name.lastIndexOf('.');
			return dot > 0 ? name.substring(0, dot) : name;
		}
	}

	/** A converted track waiting to be uploaded. */
	private record Prepared(String hash, byte[] data, String title) {
	}

	private static Phase phase = Phase.IDLE;
	private static double progress;
	private static Component message = Component.empty();

	private static List<Selected> selection = List.of();
	private static String suggestedTitle = "";
	private static String suggestedArtist = "";
	private static boolean ffmpegMissing;
	private static List<Path> pendingFiles = List.of();

	private static int nextUploadId = 1;
	private static @Nullable Job job;
	private static AtomicBoolean cancelFlag = new AtomicBoolean();

	private BurnController() {
	}

	/** An upload in progress: the converted tracks, which one is being sent, and the disc details. */
	private static final class Job {
		final BlockPos pos;
		final String title;
		final String artist;
		final boolean explicit;
		final boolean shuffle;
		final List<Prepared> tracks;
		final long totalBytes;
		long doneBytes;
		int index;
		int uploadId;
		int sent;
		int nextSeq;
		int acked;

		Job(BlockPos pos, String title, String artist, boolean explicit, boolean shuffle, List<Prepared> tracks) {
			this.pos = pos;
			this.title = title;
			this.artist = artist;
			this.explicit = explicit;
			this.shuffle = shuffle;
			this.tracks = tracks;
			this.totalBytes = tracks.stream().mapToLong(t -> t.data().length).sum();
		}

		Prepared current() {
			return tracks.get(index);
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
		return phase == Phase.PROBING || phase == Phase.CONVERTING || phase == Phase.UPLOADING || phase == Phase.BURNING || phase == Phase.DOWNLOADING_FFMPEG;
	}

	public static List<Selected> selection() {
		return selection;
	}

	public static boolean ffmpegMissing() {
		return ffmpegMissing;
	}

	public static int totalTicks() {
		return (int) (selection.stream().mapToDouble(s -> Math.max(0, s.probe().durationSeconds())).sum() * 20);
	}

	/** Title/artist read from the files' tags, handed to the screen once. */
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
		job = null;
		set(Phase.ERROR, 0, reason);
	}

	private static void onClientThread(Runnable runnable) {
		Minecraft.getInstance().execute(runnable);
	}

	/** Shows a message in the status line without changing what is selected. */
	public static void showMessage(Component text) {
		if (!isBusy()) {
			set(phase == Phase.ERROR ? (selection.isEmpty() ? Phase.IDLE : Phase.READY) : phase, progress, text);
		}
	}

	public static boolean isSupportedFile(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		return dot >= 0 && Ffmpeg.EXTENSIONS.contains(name.substring(dot + 1));
	}

	/** Files were dropped on the screen or picked with Browse. One file makes a single, several make an album. */
	public static void selectFiles(List<Path> paths) {
		if (isBusy()) {
			return;
		}

		List<Path> files = paths.stream().filter(Files::isRegularFile).filter(BurnController::isSupportedFile).distinct().toList();

		if (files.isEmpty()) {
			fail(Component.translatable("message.custom_discs.error.unsupported", paths.isEmpty() ? "?" : paths.getFirst().getFileName().toString()));
			return;
		}

		int maxTracks = ServerLimits.get().maxTracks();

		if (files.size() > maxTracks) {
			fail(Component.translatable("message.custom_discs.error.too_many_tracks", maxTracks));
			return;
		}

		pendingFiles = files;
		selection = List.of();
		Optional<Path> ffmpeg = Ffmpeg.locate();
		ffmpegMissing = ffmpeg.isEmpty();

		if (ffmpegMissing) {
			fail(Component.translatable(FfmpegDownloader.isSupported() ? "message.custom_discs.error.no_ffmpeg_download" : "message.custom_discs.error.no_ffmpeg_install"));
			return;
		}

		set(Phase.PROBING, 0, Component.translatable("message.custom_discs.reading"));

		WORKER.execute(() -> {
			List<Selected> probed = new ArrayList<>();

			try {
				for (int i = 0; i < files.size(); i++) {
					probed.add(new Selected(files.get(i), Ffmpeg.probe(ffmpeg.get(), files.get(i))));
					double done = (i + 1.0) / files.size();
					onClientThread(() -> progress = done);
				}

				onClientThread(() -> onProbed(files, probed));
			} catch (IOException | InterruptedException e) {
				CustomDiscs.LOGGER.warn("Could not read {}", files, e);
				onClientThread(() -> fail(Component.translatable("message.custom_discs.error.ffmpeg_failed", String.valueOf(e.getMessage()))));
			}
		});
	}

	private static void onProbed(List<Path> files, List<Selected> probed) {
		if (files != pendingFiles) {
			return;
		}

		ModPayloads.ServerSettings limits = ServerLimits.get();

		for (Selected selected : probed) {
			if (!selected.probe().hasAudio()) {
				fail(Component.translatable("message.custom_discs.error.no_audio_in", selected.path().getFileName().toString()));
				return;
			}

			if (selected.probe().durationSeconds() > limits.maxTrackSeconds() + 1) {
				fail(Component.translatable("message.custom_discs.error.track_too_long", selected.path().getFileName().toString(), DiscInfo.formatDuration(limits.maxTrackSeconds() * 20)));
				return;
			}
		}

		// Album order: disc number, then track number, then file name.
		List<Selected> sorted = new ArrayList<>(probed);
		sorted.sort(Comparator.<Selected>comparingInt(s -> s.probe().discNumber())
				.thenComparingInt(s -> s.probe().trackNumber() == 0 ? Integer.MAX_VALUE : s.probe().trackNumber())
				.thenComparing(s -> s.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
		selection = List.copyOf(sorted);

		if (totalTicks() > (limits.maxDiscSeconds() + 1) * 20) {
			fail(Component.translatable("message.custom_discs.error.disc_too_long", DiscInfo.formatDuration(limits.maxDiscSeconds() * 20)));
			return;
		}

		if (ClientConfig.get().prefillFromTags) {
			Ffmpeg.ProbeResult first = selection.getFirst().probe();

			if (selection.size() == 1) {
				suggestedTitle = first.title();
				suggestedArtist = first.artist();
			} else {
				suggestedTitle = first.album();
				suggestedArtist = first.albumArtist().isBlank() ? first.artist() : first.albumArtist();
			}
		}

		set(Phase.READY, 0, Component.empty());
	}

	public static boolean canBurn() {
		return !selection.isEmpty() && !isBusy() && ServerLimits.get().canBurn();
	}

	public static void burn(BlockPos burnerPos, String title, String artist, boolean explicit, boolean shuffle) {
		List<Selected> files = selection;
		Optional<Path> ffmpeg = Ffmpeg.locate();

		if (files.isEmpty() || isBusy()) {
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
		int maxBytes = ServerLimits.get().maxFileBytes();
		set(Phase.CONVERTING, 0, Component.translatable("message.custom_discs.converting", bitrate));

		WORKER.execute(() -> {
			List<Prepared> prepared = new ArrayList<>();
			Path output = null;

			try {
				Files.createDirectories(ClientConfig.dataDir().resolve("tmp"));
				output = Files.createTempFile(ClientConfig.dataDir().resolve("tmp"), "burn", ".ogg");

				for (int i = 0; i < files.size(); i++) {
					Selected file = files.get(i);
					int index = i;

					if (files.size() > 1) {
						onClientThread(() -> message = Component.translatable("message.custom_discs.converting_track", index + 1, files.size(), bitrate));
					}

					Ffmpeg.convert(ffmpeg.get(), file.path(), output, bitrate, file.probe().durationSeconds(),
							p -> onClientThread(() -> {
								if (phase == Phase.CONVERTING) {
									progress = (index + p) / files.size();
								}
							}),
							cancelled::get);
					byte[] data = Files.readAllBytes(output);

					if (data.length > maxBytes) {
						onClientThread(() -> fail(Component.translatable("message.custom_discs.error.track_too_large", file.path().getFileName().toString(), maxBytes / (1024 * 1024))));
						return;
					}

					prepared.add(new Prepared(Hashing.sha256(data), data, file.trackTitle()));
				}

				onClientThread(() -> {
					if (!cancelled.get()) {
						startUpload(new Job(burnerPos, title, artist, explicit, shuffle, prepared));
					}
				});
			} catch (Ffmpeg.ConversionException e) {
				onClientThread(() -> fail(Component.translatable("message.custom_discs.error.ffmpeg_failed", e.getMessage())));
			} catch (InterruptedException e) {
				// Cancelled.
			} catch (IOException e) {
				CustomDiscs.LOGGER.warn("Conversion failed", e);
				onClientThread(() -> fail(Component.translatable("message.custom_discs.error.ffmpeg_failed", String.valueOf(e.getMessage()))));
			} finally {
				if (output != null) {
					try {
						Files.deleteIfExists(output);
					} catch (IOException ignored) {
						// Temp file.
					}
				}
			}
		});
	}

	private static void startUpload(Job newJob) {
		// Keep copies so this client never has to download its own disc.
		newJob.tracks.forEach(track -> TrackCache.store(track.hash(), track.data()));
		job = newJob;
		set(Phase.UPLOADING, 0, Component.translatable("message.custom_discs.uploading", String.format("%.1f", newJob.totalBytes / 1024.0 / 1024.0)));
		beginTrack(newJob);
	}

	private static void beginTrack(Job current) {
		Prepared track = current.current();
		current.uploadId = nextUploadId++;
		current.sent = 0;
		current.nextSeq = 0;
		current.acked = 0;
		ClientPlayNetworking.send(new ModPayloads.UploadBegin(current.uploadId, current.pos, track.hash(), track.data().length));
	}

	private static void sendChunks(Job current) {
		byte[] data = current.current().data();

		while (current.sent < data.length && current.nextSeq - current.acked < CLIENT_WINDOW_CHUNKS) {
			int to = Math.min(data.length, current.sent + ModPayloads.CHUNK_SIZE);
			ClientPlayNetworking.send(new ModPayloads.UploadChunk(current.uploadId, current.nextSeq, Arrays.copyOfRange(data, current.sent, to)));
			current.sent = to;
			current.nextSeq++;
		}
	}

	public static void handleStatus(UploadStatus status) {
		Job current = job;

		if (current == null || current.uploadId != status.uploadId() || phase != Phase.UPLOADING) {
			return;
		}

		switch (status.state()) {
			case UploadStatus.READY -> sendChunks(current);
			case UploadStatus.PROGRESS -> {
				// The server acknowledges every chunk in order, so the ack count follows from the bytes received.
				current.acked = (status.received() + ModPayloads.CHUNK_SIZE - 1) / ModPayloads.CHUNK_SIZE;
				progress = (double) (current.doneBytes + status.received()) / current.totalBytes;
				sendChunks(current);
			}
			case UploadStatus.STORED -> {
				current.doneBytes += current.current().data().length;
				progress = (double) current.doneBytes / current.totalBytes;
				current.index++;

				if (current.index < current.tracks.size()) {
					beginTrack(current);
				} else {
					set(Phase.BURNING, 1, Component.translatable("message.custom_discs.burning"));
					List<ModPayloads.TrackRequest> tracks = current.tracks.stream().map(t -> new ModPayloads.TrackRequest(t.hash(), t.title())).toList();
					ClientPlayNetworking.send(new ModPayloads.BurnDisc(current.pos, current.title, current.artist, current.explicit, current.shuffle, tracks));
				}
			}
			default -> fail(status.message());
		}
	}

	public static void handleBurnResult(ModPayloads.BurnResult result) {
		if (phase != Phase.BURNING) {
			return;
		}

		job = null;

		if (result.success()) {
			selection = List.of();
			set(Phase.DONE, 1, Component.translatable("message.custom_discs.done", result.message()));
		} else {
			set(Phase.ERROR, 0, result.message());
		}
	}

	/** Screen closed: stop converting or uploading, since the server needs the burner open to finish. */
	public static void cancel() {
		cancelFlag.set(true);
		Job current = job;

		if (current != null) {
			ClientPlayNetworking.send(new ModPayloads.UploadCancel(current.uploadId));
			job = null;
		}

		if (phase == Phase.CONVERTING || phase == Phase.UPLOADING || phase == Phase.BURNING) {
			set(selection.isEmpty() ? Phase.IDLE : Phase.READY, 0, Component.translatable("message.custom_discs.cancelled"));
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

					if (!pendingFiles.isEmpty()) {
						selectFiles(pendingFiles);
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
		job = null;
		selection = List.of();
		pendingFiles = List.of();
		set(Phase.IDLE, 0, Component.empty());
	}
}
