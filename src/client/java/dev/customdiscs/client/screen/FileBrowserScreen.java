package dev.customdiscs.client.screen;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.customdiscs.client.BurnController;
import dev.customdiscs.client.audio.Ffmpeg;

/**
 * A simple file browser drawn by the game itself, for when the system file dialog can't be opened. Click
 * folders to open them and songs to select them (several make an album), then press "Use".
 */
public class FileBrowserScreen extends Screen {
	private static final int ROW_HEIGHT = 22;
	private static final int LIST_TOP = 58;

	private final Screen parent;
	private final Consumer<List<Path>> onChosen;
	private final Set<Path> selected = new LinkedHashSet<>();
	/** Null means "this computer": the list of drives on Windows. */
	private @Nullable Path directory;
	private List<Entry> entries = List.of();
	private int page;
	private Component error = Component.empty();

	private final List<Button> rows = new ArrayList<>();
	private Button previousButton;
	private Button nextButton;
	private Button useButton;

	/** {@code path} null with {@code parent} true is the ".." row. */
	private record Entry(@Nullable Path path, boolean isDirectory, boolean isUp) {
	}

	public FileBrowserScreen(Screen parent, @Nullable Path startDirectory, Consumer<List<Path>> onChosen) {
		super(Component.translatable("gui.custom_discs.choose_file"));
		this.parent = parent;
		this.onChosen = onChosen;
		this.directory = startDirectory != null && Files.isDirectory(startDirectory) ? startDirectory : Path.of(System.getProperty("user.home"));
		load();
	}

	private int rowsPerPage() {
		return Math.max(3, (height - LIST_TOP - 40) / ROW_HEIGHT);
	}

	@Override
	protected void init() {
		int center = width / 2;
		int rowWidth = Math.min(320, width - 40);
		int left = center - rowWidth / 2;

		// Shortcut row.
		List<Button> shortcuts = new ArrayList<>();
		Path home = Path.of(System.getProperty("user.home"));
		shortcuts.add(Button.builder(Component.translatable("gui.custom_discs.browser.home"), b -> open(home)).build());
		shortcuts.add(Button.builder(Component.translatable("gui.custom_discs.browser.music"), b -> open(home.resolve("Music"))).build());
		shortcuts.add(Button.builder(Component.translatable("gui.custom_discs.browser.downloads"), b -> open(home.resolve("Downloads"))).build());
		shortcuts.add(Button.builder(Component.translatable("gui.custom_discs.browser.desktop"), b -> open(home.resolve("Desktop"))).build());

		if (Ffmpeg.isWindows()) {
			shortcuts.add(Button.builder(Component.translatable("gui.custom_discs.browser.drives"), b -> open(null)).build());
		}

		int shortcutWidth = (rowWidth - (shortcuts.size() - 1) * 4) / shortcuts.size();

		for (int i = 0; i < shortcuts.size(); i++) {
			Button button = shortcuts.get(i);
			button.setX(left + i * (shortcutWidth + 4));
			button.setY(24);
			button.setWidth(shortcutWidth);
			addRenderableWidget(button);
		}

		rows.clear();

		for (int i = 0; i < rowsPerPage(); i++) {
			int index = i;
			rows.add(addRenderableWidget(Button.builder(Component.empty(), b -> clickRow(index))
					.pos(left, LIST_TOP + i * ROW_HEIGHT)
					.size(rowWidth, 20)
					.build()));
		}

		int bottom = height - 28;
		int quarter = (rowWidth - 12) / 4;
		previousButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> setPage(page - 1)).pos(left, bottom).size(quarter, 20).build());
		nextButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> setPage(page + 1)).pos(left + quarter + 4, bottom).size(quarter, 20).build());
		useButton = addRenderableWidget(Button.builder(Component.empty(), b -> use()).pos(left + 2 * (quarter + 4), bottom).size(quarter, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).pos(left + 3 * (quarter + 4), bottom).size(quarter, 20).build());

		refreshRows();
	}

	private void open(@Nullable Path target) {
		if (target != null && !Files.isDirectory(target)) {
			error = Component.translatable("gui.custom_discs.browser.missing", target.toString());
			return;
		}

		directory = target;
		page = 0;
		load();
		refreshRows();
	}

	private void load() {
		error = Component.empty();
		List<Entry> list = new ArrayList<>();

		if (directory == null) {
			for (File root : File.listRoots()) {
				list.add(new Entry(root.toPath(), true, false));
			}

			entries = list;
			return;
		}

		Path up = directory.getParent();

		if (up != null || Ffmpeg.isWindows()) {
			list.add(new Entry(up, true, true));
		}

		try (Stream<Path> children = Files.list(directory)) {
			List<Path> sorted = children
					.filter(p -> !p.getFileName().toString().startsWith("."))
					.filter(p -> Files.isDirectory(p) || BurnController.isSupportedFile(p))
					.sorted(Comparator.<Path, Boolean>comparing(p -> !Files.isDirectory(p))
							.thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
					.toList();

			for (Path child : sorted) {
				list.add(new Entry(child, Files.isDirectory(child), false));
			}
		} catch (IOException | SecurityException e) {
			error = Component.translatable("gui.custom_discs.browser.cant_open", directory.toString());
		}

		entries = list;
	}

	private void setPage(int newPage) {
		int pages = Math.max(1, (entries.size() + rows.size() - 1) / rows.size());
		page = Math.clamp(newPage, 0, pages - 1);
		refreshRows();
	}

	private void refreshRows() {
		if (rows.isEmpty()) {
			return;
		}

		for (int i = 0; i < rows.size(); i++) {
			int index = page * rows.size() + i;
			Button row = rows.get(i);

			if (index >= entries.size()) {
				row.visible = false;
				continue;
			}

			Entry entry = entries.get(index);
			row.visible = true;
			row.setMessage(label(entry));
		}

		int pages = Math.max(1, (entries.size() + rows.size() - 1) / rows.size());
		previousButton.active = page > 0;
		nextButton.active = page < pages - 1;
		useButton.active = !selected.isEmpty();
		useButton.setMessage(Component.translatable("gui.custom_discs.browser.use", selected.size()));
	}

	private Component label(Entry entry) {
		if (entry.isUp()) {
			return Component.translatable("gui.custom_discs.browser.up");
		}

		Path path = entry.path();
		String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();

		if (entry.isDirectory()) {
			return Component.literal(name + (name.endsWith(File.separator) ? "" : File.separator));
		}

		return Component.literal((selected.contains(path) ? "[x] " : "[ ] ") + name);
	}

	private void clickRow(int row) {
		int index = page * rows.size() + row;

		if (index >= entries.size()) {
			return;
		}

		Entry entry = entries.get(index);

		if (entry.isDirectory()) {
			open(entry.path());
		} else if (!selected.remove(entry.path())) {
			selected.add(entry.path());
		}

		refreshRows();
	}

	private void use() {
		List<Path> files = List.copyOf(selected);
		minecraft.gui.setScreen(parent);
		onChosen.accept(files);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(font, title.getString(), width / 2, 8, 0xFFFFFFFF);
		String where = directory == null ? Component.translatable("gui.custom_discs.browser.drives").getString() : directory.toString();
		int maxWidth = Math.min(320, width - 40);
		// Show the end of a long path, which is the part that matters.
		String shown = where;

		while (font.width(shown) > maxWidth && shown.length() > 4) {
			shown = "..." + shown.substring(4);
		}

		graphics.text(font, shown, width / 2 - maxWidth / 2, 47, 0xFFA0A0A0);

		if (!error.getString().isEmpty()) {
			graphics.centeredText(font, error.getString(), width / 2, height - 40, 0xFFFF5555);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
