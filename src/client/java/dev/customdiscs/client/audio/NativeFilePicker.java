package dev.customdiscs.client.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import dev.customdiscs.CustomDiscs;

/**
 * Opens the operating system's own "open file" dialog by running a small helper program: PowerShell on
 * Windows, AppleScript on macOS, and zenity or kdialog on Linux. Minecraft 26.x no longer ships LWJGL's
 * tinyfd. Supports picking several files at once. If no dialog can be shown, the caller falls back to the
 * in-game file browser.
 */
public final class NativeFilePicker {
	private NativeFilePicker() {
	}

	/** Blocks until the dialog closes. Returns the chosen files (empty if cancelled), or null if no dialog could be shown. */
	public static @Nullable List<Path> pick(String title, @Nullable Path startDirectory) {
		String start = startDirectory != null ? startDirectory.toAbsolutePath().toString() : System.getProperty("user.home");

		for (List<String> command : commands(title, start)) {
			try {
				Process process = new ProcessBuilder(command).start();
				process.getOutputStream().close();
				// Read stderr on another thread so a chatty helper can never fill its pipe and hang.
				ByteArrayOutputStream errors = new ByteArrayOutputStream();
				Thread errorReader = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), errors));
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				int exit = process.waitFor();
				errorReader.join(1000);

				List<Path> files = new ArrayList<>();

				for (String line : output.split("\\R")) {
					line = line.strip();

					if (!line.isEmpty()) {
						Path path = Path.of(line);

						if (Files.isRegularFile(path)) {
							files.add(path);
						}
					}
				}

				String errorText = errors.toString(StandardCharsets.UTF_8).strip();

				// zenity/kdialog exit 1 and osascript reports "User canceled" when the player cancels; that is not a failure.
				if (files.isEmpty() && exit != 0 && !errorText.isEmpty() && !errorText.contains("-128") && !errorText.toLowerCase().contains("cancel")) {
					CustomDiscs.LOGGER.warn("File dialog {} failed (exit {}): {}", command.getFirst(), exit, errorText);
					continue;
				}

				return files;
			} catch (IOException e) {
				CustomDiscs.LOGGER.info("File dialog helper {} is not available: {}", command.getFirst(), e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return List.of();
			}
		}

		return null;
	}

	private static void copy(InputStream in, ByteArrayOutputStream out) {
		try (in) {
			in.transferTo(out);
		} catch (IOException ignored) {
			// Process ended.
		}
	}

	private static List<List<String>> commands(String title, String start) {
		List<List<String>> commands = new ArrayList<>();

		if (Ffmpeg.isWindows()) {
			String filter = Ffmpeg.EXTENSIONS.stream().map(e -> "*." + e).collect(Collectors.joining(";"));
			// An invisible top-most window owns the dialog, so it opens in front of the game instead of behind it.
			String script = String.join("\n",
					"$ErrorActionPreference = 'Stop'",
					"[Console]::OutputEncoding = [Text.Encoding]::UTF8",
					"Add-Type -AssemblyName System.Windows.Forms",
					"$owner = New-Object System.Windows.Forms.Form",
					"$owner.TopMost = $true",
					"$owner.ShowInTaskbar = $false",
					"$owner.Opacity = 0",
					"$owner.Show()",
					"$owner.Activate()",
					"$d = New-Object System.Windows.Forms.OpenFileDialog",
					"$d.Title = " + psString(title),
					"$d.InitialDirectory = " + psString(start),
					"$d.Multiselect = $true",
					"$d.Filter = " + psString("Audio files|" + filter + "|All files|*.*"),
					"if ($d.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) { $d.FileNames | ForEach-Object { [Console]::Out.WriteLine($_) } }",
					"$owner.Close()");
			// -EncodedCommand avoids every quoting problem: Java does not escape quotes inside Windows arguments.
			String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
			String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
			List<String> args = List.of("-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
			commands.add(prepend(systemRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", args));
			commands.add(prepend("powershell", args));
			commands.add(prepend("pwsh", args));
		} else if (Ffmpeg.isMac()) {
			String script = String.join("\n",
					"tell me to activate",
					"set picked to choose file with prompt " + appleString(title) + " default location (POSIX file " + appleString(start) + ") with multiple selections allowed",
					"set out to \"\"",
					"repeat with f in picked",
					"set out to out & POSIX path of f & linefeed",
					"end repeat",
					"return out");
			commands.add(List.of("/usr/bin/osascript", "-e", script));
		} else {
			String patterns = Ffmpeg.EXTENSIONS.stream().map(e -> "*." + e + " *." + e.toUpperCase()).collect(Collectors.joining(" "));
			commands.add(List.of("zenity", "--file-selection", "--multiple", "--separator=\n", "--title=" + title,
					"--filename=" + start + "/", "--file-filter=Audio | " + patterns, "--file-filter=All files | *"));
			commands.add(List.of("kdialog", "--title", title, "--getopenfilename", start, patterns + "|Audio files",
					"--multiple", "--separate-output"));
			commands.add(List.of("yad", "--file", "--multiple", "--separator=\n", "--title=" + title, "--filename=" + start + "/"));
		}

		return commands;
	}

	private static List<String> prepend(String first, List<String> rest) {
		List<String> list = new ArrayList<>();
		list.add(first);
		list.addAll(rest);
		return list;
	}

	/** A PowerShell single-quoted string literal. */
	private static String psString(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	/** An AppleScript string literal. */
	private static String appleString(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
