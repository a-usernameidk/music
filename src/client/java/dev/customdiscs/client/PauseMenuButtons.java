package dev.customdiscs.client;

import java.util.List;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import dev.customdiscs.client.screen.SettingsScreen;

/** Adds a "mute explicit discs" toggle and a settings button to the bottom of the pause menu. */
public final class PauseMenuButtons {
	private PauseMenuButtons() {
	}

	private static Component muteLabel() {
		return Component.translatable(ClientConfig.get().muteExplicitDiscs ? "gui.custom_discs.pause.explicit_muted" : "gui.custom_discs.pause.explicit_playing");
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof PauseScreen)) {
				return;
			}

			List<AbstractWidget> widgets = Screens.getWidgets(screen);

			// F3 + Esc opens the pause screen without its menu; leave that one alone.
			if (widgets.isEmpty()) {
				return;
			}

			int y = height - 28;
			int left = width / 2 - 102;

			widgets.add(Button.builder(muteLabel(), button -> {
				ClientConfig config = ClientConfig.get();
				config.muteExplicitDiscs = !config.muteExplicitDiscs;
				ClientConfig.save();
				button.setMessage(muteLabel());
			}).pos(left, y).size(150, 20).tooltip(Tooltip.create(Component.translatable("gui.custom_discs.pause.explicit.tooltip"))).build());

			widgets.add(Button.builder(Component.translatable("gui.custom_discs.pause.settings"), button -> client.gui.setScreen(new SettingsScreen(screen)))
					.pos(left + 154, y)
					.size(50, 20)
					.tooltip(Tooltip.create(Component.translatable("gui.custom_discs.settings")))
					.build());
		});
	}
}
