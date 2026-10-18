package dev.customdiscs.client.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;

import dev.customdiscs.ModRegistry;
import dev.customdiscs.DiscInfo;
import dev.customdiscs.client.BurnController;
import dev.customdiscs.client.ClientConfig;
import dev.customdiscs.client.ServerLimits;
import dev.customdiscs.client.audio.FfmpegDownloader;
import dev.customdiscs.client.audio.NativeFilePicker;
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

	private static final int FILE_Y = 79;
	private static final int STATUS_Y = 90;
	private static final int PROGRESS_Y = 101;

	// Kept between openings so a half-typed title survives closing the screen.
	private static String titleDraft = "";
	private static String artistDraft = "";
	private static boolean explicitDraft;
	private static boolean shuffleDraft;
	private static @Nullable Path lastDirectory;
	/** True while the system file dialog is open, so Browse can't open a second one. */
	private static volatile boolean pickerOpen;

	private EditBox titleBox;
	private EditBox artistBox;
	private Button burnButton;
	private Button browseButton;
	private Button ffmpegButton;
	private Button explicitButton;
	private Button shuffleButton;

	public DiscBurnerScreen(DiscBurnerMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, DiscBurnerMenu.IMAGE_WIDTH, DiscBurnerMenu.IMAGE_HEIGHT);
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
		titleBox.setMaxLength(DiscInfo.MAX_TEXT_LENGTH);
		titleBox.setHint(Component.translatable("gui.custom_discs.title"));
		titleBox.setValue(titleDraft);
		titleBox.setResponder(value -> titleDraft = value);
		addRenderableWidget(titleBox);

		artistBox = new EditBox(font, x + 8, y + 38, 160, 16, Component.translatable("gui.custom_discs.artist"));
		artistBox.setMaxLength(DiscInfo.MAX_TEXT_LENGTH);
		artistBox.setHint(Component.translatable("gui.custom_discs.artist"));
		artistBox.setValue(artistDraft);
		artistBox.setResponder(value -> artistDraft = value);
		addRenderableWidget(artistBox);

		browseButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.browse"), button -> browse())
				.pos(x + 8, y + 58)
				.size(50, 16)
				.tooltip(Tooltip.create(Component.translatable("gui.custom_discs.browse.tooltip")))
				.build());

		explicitButton = addRenderableWidget(Button.builder(explicitLabel(), button -> {
			explicitDraft = !explicitDraft;
			button.setMessage(explicitLabel());
		}).pos(x + 61, y + 58).size(52, 16).tooltip(Tooltip.create(Component.translatable("gui.custom_discs.explicit.tooltip"))).build());

		shuffleButton = addRenderableWidget(Button.builder(shuffleLabel(), button -> {
			shuffleDraft = !shuffleDraft;
			button.setMessage(shuffleLabel());
		}).pos(x + 116, y + 58).size(52, 16).tooltip(Tooltip.create(Component.translatable("gui.custom_discs.shuffle.tooltip"))).build());

		ffmpegButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.download_ffmpeg"), button -> BurnController.downloadFfmpeg())
				.pos(x + 72, y + FILE_Y - 4)
				.size(96, 16)
				.build());

		burnButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.burn"), button -> burn())
				.pos(x + 50, y + DiscBurnerMenu.SLOT_Y - 2)
				.size(76, 20)
				.build());

		updateWidgets();
	}

	private static Component explicitLabel() {
		return Component.translatable(explicitDraft ? "gui.custom_discs.explicit_on" : "gui.custom_discs.explicit_off");
	}

	private static Component shuffleLabel() {
		return Component.translatable(shuffleDraft ? "gui.custom_discs.shuffle_on" : "gui.custom_discs.shuffle_off");
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
		browseButton.active = !busy && !pickerOpen;
		ffmpegButton.visible = BurnController.ffmpegMissing() && FfmpegDownloader.isSupported();
		ffmpegButton.active = !busy;
		titleBox.setEditable(!busy);
		artistBox.setEditable(!busy);
		explicitButton.active = !busy;
		shuffleButton.active = !busy && BurnController.selection().size() > 1;
		titleBox.setHint(Component.translatable(BurnController.selection().size() > 1 ? "gui.custom_discs.album_title" : "gui.custom_discs.title"));
	}

	private static String truncate(String text) {
		return text.length() > DiscInfo.MAX_TEXT_LENGTH ? text.substring(0, DiscInfo.MAX_TEXT_LENGTH) : text;
	}

	private void burn() {
		boolean album = BurnController.selection().size() > 1;
		BurnController.burn(menu.getPos(), titleBox.getValue().strip(), artistBox.getValue().strip(), explicitDraft, album && shuffleDraft);
	}

	private void browse() {
		if (!ClientConfig.get().useSystemFileDialog) {
			openInGameBrowser();
			return;
		}

		String title = Component.translatable("gui.custom_discs.choose_file").getString();
		pickerOpen = true;
		Thread thread = new Thread(() -> {
			List<Path> files = NativeFilePicker.pick(title, lastDirectory);

			minecraft.execute(() -> {
				pickerOpen = false;

				if (files == null) {
					// No system dialog on this computer: use the in-game browser instead.
					if (minecraft.gui.screen() == this) {
						openInGameBrowser();
					}
				} else if (!files.isEmpty()) {
					onFilesChosen(files);
				}
			});
		}, "Custom Discs file picker");
		thread.setDaemon(true);
		thread.start();
	}

	private void openInGameBrowser() {
		minecraft.gui.setScreen(new FileBrowserScreen(this, lastDirectory, files -> {
			if (!files.isEmpty()) {
				onFilesChosen(files);
			}
		}));
	}

	private static void onFilesChosen(List<Path> paths) {
		Path parent = paths.getFirst().toAbsolutePath().getParent();

		if (parent != null && Files.isDirectory(parent)) {
			lastDirectory = parent;
		}

		BurnController.selectFiles(paths);
	}

	/** Files dragged from the desktop onto the game window. Several files make an album disc. */
	@Override
	public void onFilesDrop(List<Path> paths) {
		if (!paths.isEmpty()) {
			onFilesChosen(paths);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Typing "e" or a hotbar number into a text field must not close the screen or move items.
		EditBox focused = titleBox.isFocused() ? titleBox : artistBox.isFocused() ? artistBox : null;

		if (focused != null && event.key() != InputConstants.KEY_ESCAPE) {
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

		List<BurnController.Selected> selection = BurnController.selection();
		String fileText = switch (selection.size()) {
			case 0 -> Component.translatable("gui.custom_discs.drop_hint").getString();
			case 1 -> selection.getFirst().path().getFileName().toString();
			default -> Component.translatable("gui.custom_discs.files_selected", selection.size()).getString();
		};

		if (!ffmpegButton.visible) {
			graphics.text(font, font.plainSubstrByWidth(fileText, 160), x + 8, y + FILE_Y, TEXT_COLOR, false);
			boolean hoveringFile = mouseX >= x + 8 && mouseX < x + 168 && mouseY >= y + FILE_Y - 1 && mouseY < y + FILE_Y + 9;

			if (hoveringFile && selection.size() > 1) {
				// List the album's tracks in the tooltip.
				MutableComponent list = Component.empty();

				for (int i = 0; i < selection.size(); i++) {
					BurnController.Selected selected = selection.get(i);
					list.append((i > 0 ? "\n" : "") + (i + 1) + ". " + selected.trackTitle() + "  " + DiscInfo.formatDuration((int) (selected.probe().durationSeconds() * 20)));
				}

				graphics.setTooltipForNextFrame(font, list, mouseX, mouseY);
			}
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

		List<BurnController.Selected> selection = BurnController.selection();

		return switch (BurnController.phase()) {
			case READY -> {
				if (selection.isEmpty()) {
					yield Component.empty();
				}

				int ticks = BurnController.totalTicks();
				double megabytes = ServerLimits.bitrateKbps() * (ticks / 20.0) / 8.0 / 1024.0;
				Component length = Component.literal(DiscInfo.formatDuration(ticks));

				if (!slotsReady()) {
					yield Component.translatable("gui.custom_discs.ready_need_disc", length);
				}

				yield Component.translatable(selection.size() > 1 ? "gui.custom_discs.ready_album" : "gui.custom_discs.ready", length, String.format("%.1f", megabytes), selection.size());
			}
			case IDLE -> BurnController.message().getString().isEmpty() ? Component.translatable("gui.custom_discs.idle") : BurnController.message();
			default -> BurnController.message();
		};
	}
}
