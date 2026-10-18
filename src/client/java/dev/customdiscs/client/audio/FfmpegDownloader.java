package dev.customdiscs.client.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jspecify.annotations.Nullable;

/**
 * Downloads a static ffmpeg build into {@code .minecraft/custom_discs/ffmpeg/} on Windows and macOS, only when
 * the player presses the button. Linux players should install ffmpeg from their package manager.
 */
public final class FfmpegDownloader {
	private FfmpegDownloader() {
	}

	/** The zip to download for this computer, or null when there is no automatic download for it. */
	public static @Nullable String downloadUrl() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

		if (Ffmpeg.isWindows()) {
			// BtbN's static LGPL builds include libvorbis.
			return arch.contains("aarch64") || arch.contains("arm")
					? "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-winarm64-lgpl.zip"
					: "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip";
		}

		if (Ffmpeg.isMac()) {
			// Intel build; Apple Silicon Macs run it through Rosetta 2.
			return "https://evermeet.cx/ffmpeg/getrelease/zip";
		}

		return null;
	}

	public static boolean isSupported() {
		return downloadUrl() != null;
	}

	public static Path download(DoubleConsumer progress) throws IOException, InterruptedException {
		String url = downloadUrl();

		if (url == null) {
			throw new IOException("No automatic ffmpeg download for this system");
		}

		Path target = Ffmpeg.managedPath();
		Files.createDirectories(target.getParent());
		Path zip = target.resolveSibling("download.zip");

		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.connectTimeout(Duration.ofSeconds(20))
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "CustomDiscs-Minecraft-Mod")
				.GET()
				.build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException("HTTP " + response.statusCode() + " from " + url);
		}

		long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);

		try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(zip)) {
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int read;

			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				total += read;

				if (length > 0) {
					progress.accept(Math.min(1.0, (double) total / length));
				}
			}
		}

		boolean found = false;

		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;

			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName().replace('\\', '/');
				String fileName = name.substring(name.lastIndexOf('/') + 1);

				if (!entry.isDirectory() && fileName.equals(Ffmpeg.executableName())) {
					Path temp = target.resolveSibling(Ffmpeg.executableName() + ".tmp");
					Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
					Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
					found = true;
					break;
				}
			}
		} finally {
			Files.deleteIfExists(zip);
		}

		if (!found) {
			throw new IOException("The downloaded archive did not contain " + Ffmpeg.executableName());
		}

		target.toFile().setExecutable(true);
		return target;
	}
}
