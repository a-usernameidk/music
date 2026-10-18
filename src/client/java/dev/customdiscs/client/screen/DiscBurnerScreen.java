package dev.customdiscs.client.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;

import dev.customdiscs.DiscInfo;
import dev.customdiscs.ModRegistry;
import dev.customdiscs.client.BurnController;
import dev.customdiscs.client.ClientConfig;
import dev.customdiscs.client.ServerLimits;
import dev.customdiscs.client.audio.FfmpegDownloader;
import dev.customdiscs.client.audio.NativeFilePicker;
import dev.customdiscs.menu.DiscBurnerMenu;

/**
 * Disc Burner screen, top to bottom: title and artist fields, the song picker, the Explicit and Shuffle
 * switches, then blank disc -> Burn -> burned disc (the Burn button turns into a progress bar while working),
 * a status line, and the player inventory.
 */
public class DiscBurnerScreen extends AbstractContainerScreen<DiscBurnerMenu> {
	private static final int TEXT_COLOR = 0xFF404040;
	private static final int ERROR_COLOR = 0xFFAA0000;
	private static final int OK_COLOR = 0xFF1E7A1E;
	private static final int PANEL = 0xFFC6C6C6;
	private static final int LIGHT = 0xFFFFFFFF;
	private static final int DARK = 0xFF555555;
	private static final int SLOT_DARK = 0xFF373737;
	private static final int SLOT_FILL = 0xFF8B8B8B;
	private static final int BAR_EMPTY = 0xFF555555;
	private static final int BAR_FILL = 0xFF4CC04C;

	private static final int FIELD_X = 8;
	private static final int FIELD_WIDTH = 160;
	private static final int TITLE_Y = 17;
	private static final int ARTIST_Y = 36;
	private static final int PICK_Y = 56;
	private static final int TOGGLES_Y = 79;
	private static final int BURN_X = 52;
	private static final int BURN_WIDTH = 72;
	private static final int STATUS_Y = 123;

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
	private Button pickButton;
	private Button explicitButton;
	private Button shuffleButton;
	private Button burnButton;
	private Button ffmpegButton;

	public DiscBurnerScreen(DiscBurnerMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, DiscBurnerMenu.IMAGE_WIDTH, DiscBurnerMenu.IMAGE_HEIGHT);
		inventoryLabelY = DiscBurnerMenu.INVENTORY_Y - 10;
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
		int x = left() + FIELD_X;
		int y = top();

		titleBox = new EditBox(font, x, y + TITLE_Y, FIELD_WIDTH, 16, Component.translatable("gui.custom_discs.title"));
		titleBox.setMaxLength(DiscInfo.MAX_TEXT_LENGTH);
		titleBox.setValue(titleDraft);
		titleBox.setResponder(value -> titleDraft = value);
		addRenderableWidget(titleBox);

		artistBox = new EditBox(font, x, y + ARTIST_Y, FIELD_WIDTH, 16, Component.translatable("gui.custom_discs.artist"));
		artistBox.setMaxLength(DiscInfo.MAX_TEXT_LENGTH);
		artistBox.setHint(Component.translatable("gui.custom_discs.artist").withStyle(ChatFormatting.DARK_GRAY));
		artistBox.setValue(artistDraft);
		artistBox.setResponder(value -> artistDraft = value);
		addRenderableWidget(artistBox);

		pickButton = addRenderableWidget(Button.builder(Component.empty(), button -> browse())
				.pos(x, y + PICK_Y)
				.size(FIELD_WIDTH, 20)
				.build());

		explicitButton = addRenderableWidget(Button.builder(Component.empty(), button -> explicitDraft = !explicitDraft)
				.pos(x, y + TOGGLES_Y)
				.size(FIELD_WIDTH / 2 - 2, 16)
				.tooltip(Tooltip.create(Component.translatable("gui.custom_discs.explicit.tooltip")))
				.build());

		shuffleButton = addRenderableWidget(Button.builder(Component.empty(), button -> shuffleDraft = !shuffleDraft)
				.pos(x + FIELD_WIDTH / 2 + 2, y + TOGGLES_Y)
				.size(FIELD_WIDTH / 2 - 2, 16)
				.tooltip(Tooltip.create(Component.translatable("gui.custom_discs.shuffle.tooltip")))
				.build());

		burnButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.burn"), button -> burn())
				.pos(left() + BURN_X, y + DiscBurnerMenu.SLOT_Y - 2)
				.size(BURN_WIDTH, 20)
				.build());

		ffmpegButton = addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.download_ffmpeg"), button -> BurnController.downloadFfmpeg())
				.pos(x + 20, y + STATUS_Y + 1)
				.size(FIELD_WIDTH - 40, 16)
				.build());

		updateWidgets();
	}

	private boolean slotsReady() {
		return menu.getSlot(0).getItem().is(ModRegistry.BLANK_MUSIC_DISC) && menu.getSlot(1).getItem().isEmpty();
	}

	private static String duration(double seconds) {
		return DiscInfo.formatDuration((int) (Math.max(0, seconds) * 20));
	}

	/** What the song picker button says: what to do, or what is chosen. */
	private Component pickLabel() {
		List<BurnController.Selected> selection = BurnController.selection();

		if (selection.isEmpty()) {
			return Component.translatable("gui.custom_discs.pick");
		}

		if (selection.size() == 1) {
			BurnController.Selected song = selection.getFirst();
			String name = font.plainSubstrByWidth(song.path().getFileName().toString(), FIELD_WIDTH - 50);
			return Component.translatable("gui.custom_discs.picked_one", name, duration(song.probe().durationSeconds()));
		}

		return Component.translatable("gui.custom_discs.picked_many", selection.size(), DiscInfo.formatDuration(BurnController.totalTicks()));
	}

	private Component pickTooltip() {
		List<BurnController.Selected> selection = BurnController.selection();

		if (selection.isEmpty()) {
			return Component.translatable("gui.custom_discs.browse.tooltip");
		}

		MutableComponent list = Component.translatable("gui.custom_discs.pick.change").withStyle(ChatFormatting.GRAY);

		for (int i = 0; i < selection.size(); i++) {
			BurnController.Selected song = selection.get(i);
			list.append("\n" + (selection.size() > 1 ? (i + 1) + ". " : "") + song.trackTitle() + "  " + duration(song.probe().durationSeconds()));
		}

		return list;
	}

	private void updateWidgets() {
		// Use tags from the files for empty fields.
		String suggestedTitle = BurnController.takeSuggestedTitle();
		String suggestedArtist = BurnController.takeSuggestedArtist();

		if (titleBox.getValue().isBlank() && !suggestedTitle.isBlank()) {
			titleBox.setValue(truncate(suggestedTitle));
		}

		if (artistBox.getValue().isBlank() && !suggestedArtist.isBlank()) {
			artistBox.setValue(truncate(suggestedArtist));
		}

		boolean busy = BurnController.isBusy();
		boolean album = BurnController.selection().size() > 1;

		titleBox.setEditable(!busy);
		artistBox.setEditable(!busy);
		titleBox.setHint(Component.translatable(album ? "gui.custom_discs.album_title" : "gui.custom_discs.title").withStyle(ChatFormatting.DARK_GRAY));

		pickButton.active = !busy && !pickerOpen;
		pickButton.setMessage(pickLabel());
		pickButton.setTooltip(Tooltip.create(pickTooltip()));

		explicitButton.active = !busy;
		explicitButton.setMessage(explicitDraft
				? Component.translatable("gui.custom_discs.explicit_on").withStyle(ChatFormatting.RED)
				: Component.translatable("gui.custom_discs.explicit_off"));

		shuffleButton.active = !busy && album;
		shuffleButton.setMessage(Component.translatable(!album ? "gui.custom_discs.shuffle_single" : shuffleDraft ? "gui.custom_discs.shuffle_on" : "gui.custom_discs.shuffle_off"));

		// The Burn button makes way for the progress bar while converting or uploading.
		burnButton.visible = !busy;
		burnButton.active = BurnController.canBurn() && slotsReady() && !titleBox.getValue().isBlank();
		burnButton.setTooltip(burnButton.active ? null : Tooltip.create(burnHint()));

		ffmpegButton.visible = BurnController.ffmpegMissing() && FfmpegDownloader.isSupported() && !busy;
	}

	/** Why Burn is greyed out. */
	private Component burnHint() {
		if (!ServerLimits.get().canBurn()) {
			return Component.translatable("message.custom_discs.error.no_permission");
		}

		if (BurnController.selection().isEmpty()) {
			return Component.translatable("gui.custom_discs.hint.pick");
		}

		if (titleBox.getValue().isBlank()) {
			return Component.translatable("gui.custom_discs.hint.title");
		}

		if (!menu.getSlot(1).getItem().isEmpty()) {
			return Component.translatable("message.custom_discs.error.output_full");
		}

		return Component.translatable("message.custom_discs.error.no_blank_disc");
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

		// Vanilla-style raised panel.
		graphics.fill(x, y, x + imageWidth, y + imageHeight, DARK);
		graphics.fill(x, y, x + imageWidth - 1, y + imageHeight - 1, LIGHT);
		graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, PANEL);

		// Disc slots.
		slot(graphics, x + DiscBurnerMenu.INPUT_X, y + DiscBurnerMenu.SLOT_Y);
		slot(graphics, x + DiscBurnerMenu.OUTPUT_X, y + DiscBurnerMenu.SLOT_Y);

		// Divider above the inventory.
		graphics.fill(x + 7, y + DiscBurnerMenu.INVENTORY_Y - 13, x + imageWidth - 7, y + DiscBurnerMenu.INVENTORY_Y - 12, DARK);
		graphics.fill(x + 7, y + DiscBurnerMenu.INVENTORY_Y - 12, x + imageWidth - 7, y + DiscBurnerMenu.INVENTORY_Y - 11, LIGHT);

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				slot(graphics, x + 8 + col * 18, y + DiscBurnerMenu.INVENTORY_Y + row * 18);
			}
		}

		for (int col = 0; col < 9; col++) {
			slot(graphics, x + 8 + col * 18, y + DiscBurnerMenu.INVENTORY_Y + 58);
		}

		// Progress bar in place of the Burn button while working.
		if (BurnController.isBusy()) {
			int barX = x + BURN_X;
			int barY = y + DiscBurnerMenu.SLOT_Y + 4;
			graphics.fill(barX, barY, barX + BURN_WIDTH, barY + 8, SLOT_DARK);
			graphics.fill(barX + 1, barY + 1, barX + BURN_WIDTH - 1, barY + 7, BAR_EMPTY);
			int filled = (int) Math.round((BURN_WIDTH - 2) * Math.clamp(BurnController.progress(), 0.0, 1.0));

			if (filled > 0) {
				graphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + 7, BAR_FILL);
			}
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

		if (BurnController.isBusy()) {
			String percent = Math.round(BurnController.progress() * 100) + "%";
			graphics.centeredText(font, percent, x + BURN_X + BURN_WIDTH / 2, y + DiscBurnerMenu.SLOT_Y - 6, TEXT_COLOR);
		}

		// Explain the empty disc slots when hovered.
		slotHint(graphics, mouseX, mouseY, 0, DiscBurnerMenu.INPUT_X, "gui.custom_discs.slot.blank");
		slotHint(graphics, mouseX, mouseY, 1, DiscBurnerMenu.OUTPUT_X, "gui.custom_discs.slot.burned");

		if (!ffmpegButton.visible) {
			Component status = statusLine();
			int color = BurnController.isError() ? ERROR_COLOR : BurnController.phase() == BurnController.Phase.DONE ? OK_COLOR : TEXT_COLOR;
			List<String> lines = wrap(status.getString(), FIELD_WIDTH, 2);
			int lineY = y + STATUS_Y + (lines.size() == 1 ? 5 : 0);

			for (String line : lines) {
				graphics.centeredText(font, line, x + imageWidth / 2, lineY, color);
				lineY += 10;
			}

			boolean hovering = mouseX >= x + FIELD_X && mouseX < x + FIELD_X + FIELD_WIDTH && mouseY >= y + STATUS_Y && mouseY < y + STATUS_Y + 20;

			if (hovering && !String.join(" ", lines).equals(status.getString())) {
				graphics.setTooltipForNextFrame(font, status, mouseX, mouseY);
			}
		}

		extractTooltip(graphics, mouseX, mouseY);
	}

	private void slotHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int slot, int slotX, String key) {
		int x = left() + slotX;
		int y = top() + DiscBurnerMenu.SLOT_Y;

		if (menu.getSlot(slot).getItem().isEmpty() && mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
			graphics.setTooltipForNextFrame(font, Component.translatable(key), mouseX, mouseY);
		}
	}

	/** Word-wraps {@code text} into at most {@code maxLines} lines, ending with "..." if it doesn't fit. */
	private List<String> wrap(String text, int width, int maxLines) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;

			if (font.width(candidate) <= width) {
				line = new StringBuilder(candidate);
				continue;
			}

			if (!line.isEmpty()) {
				lines.add(line.toString());
			}

			line = new StringBuilder(word);

			if (lines.size() == maxLines) {
				break;
			}
		}

		if (!line.isEmpty() && lines.size() < maxLines) {
			lines.add(line.toString());
		}

		if (lines.size() == maxLines && font.width(String.join(" ", lines)) < font.width(text)) {
			String last = lines.get(maxLines - 1);
			lines.set(maxLines - 1, font.plainSubstrByWidth(last, width - font.width("...")) + "...");
		}

		for (int i = 0; i < lines.size(); i++) {
			if (font.width(lines.get(i)) > width) {
				lines.set(i, font.plainSubstrByWidth(lines.get(i), width));
			}
		}

		return lines;
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

				if (!slotsReady() || titleBox.getValue().isBlank()) {
					yield burnHint();
				}

				yield Component.translatable("gui.custom_discs.ready", String.format("%.1f", megabytes));
			}
			case IDLE -> BurnController.message().getString().isEmpty() ? Component.translatable("gui.custom_discs.idle") : BurnController.message();
			default -> BurnController.message();
		};
	}
}
