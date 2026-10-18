package dev.customdiscs.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import dev.customdiscs.ModRegistry;
import dev.customdiscs.menu.DiscBurnerMenu;

public class DiscBurnerBlockEntity extends BlockEntity implements ExtendedMenuProvider<BlockPos> {
	public static final int SLOT_INPUT = 0;
	public static final int SLOT_OUTPUT = 1;

	private final SimpleContainer container = new SimpleContainer(2) {
		@Override
		public void setChanged() {
			super.setChanged();
			DiscBurnerBlockEntity.this.setChanged();
		}
	};

	public DiscBurnerBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.DISC_BURNER_BLOCK_ENTITY, pos, state);
	}

	public SimpleContainer getContainer() {
		return container;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		container.setItem(SLOT_INPUT, input.read("input", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
		container.setItem(SLOT_OUTPUT, input.read("output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store("input", ItemStack.OPTIONAL_CODEC, container.getItem(SLOT_INPUT));
		output.store("output", ItemStack.OPTIONAL_CODEC, container.getItem(SLOT_OUTPUT));
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("container.custom_discs.disc_burner");
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
		return new DiscBurnerMenu(containerId, inventory, container, worldPosition);
	}

	@Override
	public BlockPos getScreenOpeningData(ServerPlayer player) {
		return worldPosition;
	}
}
