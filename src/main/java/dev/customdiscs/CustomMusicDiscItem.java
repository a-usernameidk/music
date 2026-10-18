package dev.customdiscs;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A burned disc. Sneak + use with an album disc in hand turns shuffle on or off. */
public class CustomMusicDiscItem extends Item {
	public CustomMusicDiscItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		DiscInfo disc = stack.get(ModRegistry.DISC);

		if (!player.isShiftKeyDown() || disc == null || !disc.isAlbum()) {
			return super.use(level, player, hand);
		}

		if (!level.isClientSide()) {
			DiscInfo toggled = disc.withShuffle(!disc.shuffle());
			stack.set(ModRegistry.DISC, toggled);
			player.sendOverlayMessage(Component.translatable(toggled.shuffle() ? "item.custom_discs.custom_music_disc.shuffle_on" : "item.custom_discs.custom_music_disc.shuffle_off"));
		}

		return InteractionResult.SUCCESS;
	}
}
