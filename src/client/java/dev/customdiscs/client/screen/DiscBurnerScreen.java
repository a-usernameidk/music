package dev.customdiscs.client.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import dev.customdiscs.ModRegistry;
import dev.customdiscs.TrackInfo;
import dev.customdiscs.client.BurnController;
import dev.customdiscs.client.ServerLimits;
import dev.customdiscs.client.audio.Ffmpeg;
import dev.customdiscs.client.audio.FfmpegDownloader;
import dev.customdiscs.menu.DiscBurnerMenu;

public class DiscBurnerScreen extends AbstractContainerScreen<DiscBurnerMenu> {
	private static final int TEXT_COLOR = 0xFF404040;
	private static final int ERROR_COLOR = 0xFFB00000;
	private static final int OK_COLOR = 0xFF207020;
	private static final int PANEL = 0xFFC6C6C6;
	private static final int LIGHT = 0xFFFFFFFF;
	private static final int DARK = 0xFF555555;
	private static final int SLOT_DARK = 0xFF373737;
	private static final int SLOT_FILL = 0xFF8B8B8B;
	private static final int PROGRESS = 0xFF3FBF3F;

	private static final int STATUS_Y = 79;
	private static final int PROGRESS_Y = 91;

	// Kept between openings so a half-typed title survives closing the screen.
	private static String titleDraft = "";
	private static String artistDraft = "";
	private static @Nullable Path lastDirectory;

	private EditBox titleBox;
	private EditBox artistBox;
	private Button burnButton;
	private Button browseButton;
	private Button ffmpegButton;

	public DiscBurnerScreen(DiscBurnerMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		imageWidth = DiscBurnerMenu.IMAGE_WIDTH;
		imageHeight = DiscBurnerMenu.IMAGE_HEIGHT;
		inventoryLabelY = DiscBurnerMenu.INVENTORY_Y - 11;
	}

	private int left() {
		return (width - imageWidth) / 2;
	}

	private int top() {
		return (height - imageHeight) / 2;
	}

	@Override
	protected void init() {
		super.init();
		int x = left();
		int y = top();

		titleBox = new EditBox(font, x + 8, y + 18, 160, 16, Component.translatable("gui.custom_discs.title"));
		titleBox.setMaxLength(TrackInfo.MAX_TEXT_LENGTH);
		titleBox.setHint(Component.translatable("gui.custom_discs.title"));
		titleBox.setValue(titleDraft);
		titleBox.setResponder(value -> titleDraft = value);
		addRenderableWidget(titleBox);

		artistBox = new EditBox(font, x + 8, y + 38, 160, 16, Component.translatable("gui.custom_discs.artist"));
		artistBox.setMaxLength(TrackInfo.MAX_TEXT_LENGTH);
		artistBox.setHint(Component.translatable("gui.custom_discs.artist"));
		artistBox.setValue(artistDraft);
		artistBox.setResponder(value -> artistDraft = value);
		addRenderableWidget(artistBox);

		browseButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.browse"), button -> browse())
				.pos(x + 8, y + 58)
				.size(60, 16)
				.build());

		ffmpegButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.download_ffmpeg"), button -> BurnController.downloadFfmpeg())
				.pos(x + 72, y + 58)
				.size(96, 16)
				.build());

		burnButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.burn"), button -> burn())
				.pos(x + 50, y + DiscBurnerMenu.SLOT_Y - 2)
				.size(76, 20)
				.build());

		updateWidgets();
	}

	private boolean slotsReady() {
		return menu.getSlot(0).getItem().is(ModRegistry.BLANK_MUSIC_DISC) && menu.getSlot(1).getItem().isEmpty();
	}

	private void updateWidgets() {
		// Use tags from the file for empty fields.
		String suggestedTitle = BurnController.takeSuggestedTitle();
		String suggestedArtist = BurnController.takeSuggestedArtist();

		if (titleBox.getValue().isBlank() && !suggestedTitle.isBlank()) {
			titleBox.setValue(truncate(suggestedTitle));
		}

		if (artistBox.getValue().isBlank() && !suggestedArtist.isBlank()) {
			artistBox.setValue(truncate(suggestedArtist));
		}

		boolean busy = BurnController.isBusy();
		burnButton.active = BurnController.canBurn() && slotsReady() && !titleBox.getValue().isBlank();
		browseButton.active = !busy;
		ffmpegButton.visible = BurnController.ffmpegMissing() && FfmpegDownloader.isSupported();
		ffmpegButton.active = !busy;
		titleBox.setEditable(!busy);
		artistBox.setEditable(!busy);
	}

	private static String truncate(String text) {
		return text.length() > TrackInfo.MAX_TEXT_LENGTH ? text.substring(0, TrackInfo.MAX_TEXT_LENGTH) : text;
	}

	private void burn() {
		BurnController.burn(menu.getPos(), titleBox.getValue().strip(), artistBox.getValue().strip());
	}

	private void browse() {
		Path start = lastDirectory != null ? lastDirectory : Path.of(System.getProperty("user.home"));
		String startPath = start.toAbsolutePath() + start.getFileSystem().getSeparator();

		Runnable open = () -> {
			String result;

			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(Ffmpeg.EXTENSIONS.size() * 2);

				for (String extension : Ffmpeg.EXTENSIONS) {
					filters.put(stack.UTF8("*." + extension));
					filters.put(stack.UTF8("*." + extension.toUpperCase(Locale.ROOT)));
				}

				filters.flip();
				result = TinyFileDialogs.tinyfd_openFileDialog(
						Component.translatable("gui.custom_discs.choose_file").getString(), startPath, filters,
						Component.translatable("gui.custom_discs.audio_files").getString(), false);
			}

			if (result != null) {
				minecraft.execute(() -> onFileChosen(Path.of(result)));
			}
		};

		if (Ffmpeg.isMac()) {
			// macOS only allows dialogs on the main thread. The game waits until the dialog closes.
			open.run();
		} else {
			Thread thread = new Thread(open, "Custom Discs file picker");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static void onFileChosen(Path path) {
		Path parent = path.toAbsolutePath().getParent();

		if (parent != null && Files.isDirectory(parent)) {
			lastDirectory = parent;
		}

		BurnController.selectFile(path);
	}

	/** Files dragged from the desktop onto the game window, the same hook the resource pack screen uses. */
	@Override
	public void onFilesDrop(List<Path> paths) {
		paths.stream()
				.filter(BurnController::isSupportedFile)
				.findFirst()
				.or(() -> paths.stream().findFirst())
				.ifPresent(DiscBurnerScreen::onFileChosen);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Typing "e" or a hotbar number into a text field must not close the screen or move items.
		EditBox focused = titleBox.isFocused() ? titleBox : artistBox.isFocused() ? artistBox : null;

		if (focused != null && event.key() != GLFW.GLFW_KEY_ESCAPE) {
			return focused.keyPressed(event) || focused.canConsumeInput() || super.keyPressed(event);
		}

		return super.keyPressed(event);
	}

	@Override
	public void removed() {
		super.removed();

		if (BurnController.phase() == BurnController.Phase.CONVERTING || BurnController.phase() == BurnController.Phase.UPLOADING) {
			BurnController.cancel();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractBackground(graphics, mouseX, mouseY, delta);
		int x = left();
		int y = top();

		// Vanilla-style panel.
		graphics.fill(x, y, x + imageWidth, y + imageHeight, DARK);
		graphics.fill(x, y, x + imageWidth - 1, y + imageHeight - 1, LIGHT);
		graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, PANEL);

		slot(graphics, x + DiscBurnerMenu.INPUT_X, y + DiscBurnerMenu.SLOT_Y);
		slot(graphics, x + DiscBurnerMenu.OUTPUT_X, y + DiscBurnerMenu.SLOT_Y);

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				slot(graphics, x + 8 + col * 18, y + DiscBurnerMenu.INVENTORY_Y + row * 18);
			}
		}

		for (int col = 0; col < 9; col++) {
			slot(graphics, x + 8 + col * 18, y + DiscBurnerMenu.INVENTORY_Y + 58);
		}

		// Progress bar.
		graphics.fill(x + 8, y + PROGRESS_Y, x + 168, y + PROGRESS_Y + 5, SLOT_DARK);
		int filled = (int) Math.round(158 * Math.clamp(BurnController.progress(), 0.0, 1.0));

		if (filled > 0) {
			graphics.fill(x + 9, y + PROGRESS_Y + 1, x + 9 + filled, y + PROGRESS_Y + 4, PROGRESS);
		}
	}

	private static void slot(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_DARK);
		graphics.fill(x, y, x + 17, y + 17, LIGHT);
		graphics.fill(x, y, x + 16, y + 16, SLOT_FILL);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		updateWidgets();
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int x = left();
		int y = top();

		Path file = BurnController.selectedFile();
		String fileText = file != null ? file.getFileName().toString() : Component.translatable("gui.custom_discs.drop_hint").getString();

		if (!ffmpegButton.visible) {
			graphics.text(font, font.plainSubstrByWidth(fileText, 96), x + 72, y + 62, TEXT_COLOR, false);
		}

		Component status = statusLine();
		int color = BurnController.isError() ? ERROR_COLOR : BurnController.phase() == BurnController.Phase.DONE ? OK_COLOR : TEXT_COLOR;
		String statusText = status.getString();
		String shown = font.plainSubstrByWidth(statusText, 160);
		graphics.text(font, shown, x + 8, y + STATUS_Y, color, false);

		boolean hoveringStatus = mouseX >= x + 8 && mouseX < x + 168 && mouseY >= y + STATUS_Y - 1 && mouseY < y + STATUS_Y + 9;

		if (hoveringStatus && !shown.equals(statusText)) {
			graphics.setTooltipForNextFrame(font, status, mouseX, mouseY);
		}

		extractTooltip(graphics, mouseX, mouseY);
	}

	private Component statusLine() {
		if (!ServerLimits.get().canBurn()) {
			return Component.translatable("message.custom_discs.error.no_permission");
		}

		Ffmpeg.ProbeResult probe = BurnController.probe();

		return switch (BurnController.phase()) {
			case READY -> {
				if (probe == null) {
					yield Component.empty();
				}

				double megabytes = ServerLimits.bitrateKbps() * Math.max(0, probe.durationSeconds()) / 8.0 / 1024.0;
				Component length = Component.literal(probe.durationSeconds() > 0 ? TrackInfo.formatDuration((int) (probe.durationSeconds() * 20)) : "?");

				if (!slotsReady()) {
					yield Component.translatable("gui.custom_discs.ready_need_disc", length);
				}

				yield Component.translatable("gui.custom_discs.ready", length, String.format("%.1f", megabytes));
			}
			case IDLE -> BurnController.message().getString().isEmpty() ? Component.translatable("gui.custom_discs.idle") : BurnController.message();
			default -> BurnController.message();
		};
	}
}
