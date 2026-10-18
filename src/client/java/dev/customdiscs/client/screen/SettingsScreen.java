package dev.customdiscs.client.screen;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.customdiscs.client.ClientConfig;

/** Player settings for Custom Music Discs. Open it with {@code /customdiscs}. */
public class SettingsScreen extends Screen {
	private static final List<Integer> BITRATES = List.of(64, 96, 128, 160, 192);

	private final @Nullable Screen parent;

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("gui.custom_discs.settings"));
		this.parent = parent;
	}

	private static Component onOff(String key, boolean value) {
		return Component.translatable(key, value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	@Override
	protected void init() {
		ClientConfig config = ClientConfig.get();
		int x = width / 2 - 100;
		int y = height / 4 + 8;

		addRenderableWidget(Button.builder(onOff("gui.custom_discs.settings.mute_explicit", config.muteExplicitDiscs), button -> {
			config.muteExplicitDiscs = !config.muteExplicitDiscs;
			button.setMessage(onOff("gui.custom_discs.settings.mute_explicit", config.muteExplicitDiscs));
			ClientConfig.save();
		}).pos(x, y).size(200, 20).build());

		addRenderableWidget(Button.builder(onOff("gui.custom_discs.settings.prefill", config.prefillFromTags), button -> {
			config.prefillFromTags = !config.prefillFromTags;
			button.setMessage(onOff("gui.custom_discs.settings.prefill", config.prefillFromTags));
			ClientConfig.save();
		}).pos(x, y + 24).size(200, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("gui.custom_discs.settings.bitrate", config.bitrateKbps), button -> {
			int index = BITRATES.indexOf(config.bitrateKbps);
			config.bitrateKbps = BITRATES.get((index + 1) % BITRATES.size());
			button.setMessage(Component.translatable("gui.custom_discs.settings.bitrate", config.bitrateKbps));
			ClientConfig.save();
		}).pos(x, y + 48).size(200, 20).build());

		addRenderableWidget(Button.builder(Component.translatable(config.useSystemFileDialog ? "gui.custom_discs.settings.picker_system" : "gui.custom_discs.settings.picker_game"), button -> {
			config.useSystemFileDialog = !config.useSystemFileDialog;
			button.setMessage(Component.translatable(config.useSystemFileDialog ? "gui.custom_discs.settings.picker_system" : "gui.custom_discs.settings.picker_game"));
			ClientConfig.save();
		}).pos(x, y + 72).size(200, 20).build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.pos(x, y + 108)
				.size(200, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(font, title.getString(), width / 2, height / 4 - 12, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
