package dev.customdiscs.client.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * Opens the operating system's own "open file" dialog by running a small helper command: PowerShell on
 * Windows, AppleScript on macOS, and zenity or kdialog on Linux. Minecraft 26.x no longer ships LWJGL's
 * tinyfd, and this avoids blocking the game while the dialog is open. Supports picking several files at once.
 */
public final class NativeFilePicker {
	private NativeFilePicker() {
	}

	/** Blocks until the dialog closes. Returns the chosen files (empty if cancelled), or null if no dialog could be shown. */
	public static @Nullable List<Path> pick(String title, @Nullable Path startDirectory) {
		List<List<String>> commands = new ArrayList<>();
		String start = startDirectory != null ? startDirectory.toAbsolutePath().toString() : System.getProperty("user.home");

		if (Ffmpeg.isWindows()) {
			String filter = Ffmpeg.EXTENSIONS.stream().map(e -> "*." + e).collect(Collectors.joining(";"));
			String script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
					+ "Add-Type -AssemblyName System.Windows.Forms;"
					+ "$d=New-Object System.Windows.Forms.OpenFileDialog;"
					+ "$d.Title='" + title.replace("'", "''") + "';"
					+ "$d.InitialDirectory='" + start.replace("'", "''") + "';"
					+ "$d.Multiselect=$true;"
					+ "$d.Filter='Audio files|" + filter + "|All files|*.*';"
					+ "if($d.ShowDialog() -eq 'OK'){$d.FileNames -join \"`n\"}";
			commands.add(List.of("powershell", "-NoProfile", "-STA", "-NonInteractive", "-Command", script));
		} else if (Ffmpeg.isMac()) {
			String script = "set out to \"\"\n"
					+ "set picked to choose file with prompt \"" + title.replace("\"", "'") + "\" default location (POSIX file \"" + start.replace("\"", "") + "\") with multiple selections allowed\n"
					+ "repeat with f in picked\nset out to out & POSIX path of f & linefeed\nend repeat\n"
					+ "return out";
			commands.add(List.of("osascript", "-e", script));
		} else {
			commands.add(List.of("zenity", "--file-selection", "--multiple", "--separator=\n", "--title=" + title,
					"--filename=" + start + "/",
					"--file-filter=Audio | " + Ffmpeg.EXTENSIONS.stream().map(e -> "*." + e + " *." + e.toUpperCase()).collect(Collectors.joining(" ")),
					"--file-filter=All files | *"));
			commands.add(List.of("kdialog", "--title", title, "--getopenfilename", start,
					Ffmpeg.EXTENSIONS.stream().map(e -> "*." + e).collect(Collectors.joining(" ")) + "|Audio files",
					"--multiple", "--separate-output"));
		}

		for (List<String> command : commands) {
			try {
				Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				List<Path> files = new ArrayList<>();

				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;

					while ((line = reader.readLine()) != null) {
						line = line.strip();

						if (!line.isEmpty()) {
							Path path = Path.of(line);

							if (Files.isRegularFile(path)) {
								files.add(path);
							}
						}
					}
				}

				process.waitFor(5, TimeUnit.SECONDS);
				return files;
			} catch (IOException e) {
				// This helper is not installed; try the next one.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return List.of();
			}
		}

		return null;
	}
}
