package dev.customdiscs.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.customdiscs.ModRegistry;
import dev.customdiscs.block.DiscBurnerBlockEntity;

public class DiscBurnerMenu extends AbstractContainerMenu {
	// Layout shared with the client screen.
	public static final int IMAGE_WIDTH = 176;
	public static final int IMAGE_HEIGHT = 228;
	public static final int INPUT_X = 26;
	public static final int OUTPUT_X = 134;
	public static final int SLOT_Y = 112;
	public static final int INVENTORY_Y = 146;

	private final Container container;
	private final BlockPos pos;

	/** Client constructor, called by {@link net.fabricmc.fabric.api.menu.v1.ExtendedMenuType}. */
	public DiscBurnerMenu(int containerId, Inventory inventory, BlockPos pos) {
		this(containerId, inventory, new SimpleContainer(2), pos);
	}

	public DiscBurnerMenu(int containerId, Inventory inventory, Container container, BlockPos pos) {
		super(ModRegistry.DISC_BURNER_MENU, containerId);
		this.container = container;
		this.pos = pos;

		addSlot(new Slot(container, DiscBurnerBlockEntity.SLOT_INPUT, INPUT_X, SLOT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.is(ModRegistry.BLANK_MUSIC_DISC);
			}
		});
		addSlot(new Slot(container, DiscBurnerBlockEntity.SLOT_OUTPUT, OUTPUT_X, SLOT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		});

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18));
			}
		}

		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(inventory, col, 8 + col * 18, INVENTORY_Y + 58));
		}
	}

	public BlockPos getPos() {
		return pos;
	}

	public Container getContainer() {
		return container;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);

		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();

		if (index < 2) {
			if (!moveItemStackTo(stack, 2, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (stack.is(ModRegistry.BLANK_MUSIC_DISC)) {
			if (!moveItemStackTo(stack, 0, 1, false)) {
				return ItemStack.EMPTY;
			}
		} else {
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}

		slot.onTake(player, stack);
		return original;
	}

	@Override
	public boolean stillValid(Player player) {
		return player.level().getBlockState(pos).is(ModRegistry.DISC_BURNER)
				&& player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
	}
}
