package dev.customdiscs;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.item.v1.ItemComponentTooltipProviderRegistry;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import dev.customdiscs.block.DiscBurnerBlock;
import dev.customdiscs.block.DiscBurnerBlockEntity;
import dev.customdiscs.menu.DiscBurnerMenu;

public final class ModRegistry {
	/**
	 * One shared jukebox song (data/custom_discs/jukebox_song/burned.json) used by every burned disc. Vanilla
	 * code handles inserting, ejecting, hoppers and comparator output through it; the actual audio, title and
	 * length come from the {@link DiscInfo} component on the disc.
	 */
	public static final ResourceKey<JukeboxSong> BURNED_SONG = ResourceKey.create(Registries.JUKEBOX_SONG, CustomDiscs.id("burned"));

	public static final DataComponentType<DiscInfo> DISC = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			CustomDiscs.id("disc"),
			DataComponentType.<DiscInfo>builder().persistent(DiscInfo.CODEC).networkSynchronized(DiscInfo.STREAM_CODEC).build()
	);

	public static final ResourceKey<Block> DISC_BURNER_KEY = ResourceKey.create(Registries.BLOCK, CustomDiscs.id("disc_burner"));
	public static final Block DISC_BURNER = Registry.register(BuiltInRegistries.BLOCK, DISC_BURNER_KEY, new DiscBurnerBlock(
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F, 6.0F)
					.requiresCorrectToolForDrops()
					.sound(SoundType.METAL)
					.setId(DISC_BURNER_KEY)));

	public static final Item DISC_BURNER_ITEM = item("disc_burner", properties -> new BlockItem(DISC_BURNER, properties), new Item.Properties());

	public static final Item BLANK_MUSIC_DISC = item("blank_music_disc", Item::new, new Item.Properties().stacksTo(16));

	public static final Item CUSTOM_MUSIC_DISC = item("custom_music_disc", CustomMusicDiscItem::new, new Item.Properties()
			.stacksTo(1)
			.rarity(Rarity.RARE)
			.jukeboxPlayable(BURNED_SONG)
			// Hide the vanilla "song description" line; DiscInfo adds "Artist - Title" instead.
			.component(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.JUKEBOX_PLAYABLE, true)));

	public static final BlockEntityType<DiscBurnerBlockEntity> DISC_BURNER_BLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			CustomDiscs.id("disc_burner"),
			FabricBlockEntityTypeBuilder.create(DiscBurnerBlockEntity::new, DISC_BURNER).build()
	);

	public static final MenuType<DiscBurnerMenu> DISC_BURNER_MENU = Registry.register(
			BuiltInRegistries.MENU,
			CustomDiscs.id("disc_burner"),
			new ExtendedMenuType<>(DiscBurnerMenu::new, BlockPos.STREAM_CODEC.cast())
	);

	private ModRegistry() {
	}

	private static Item item(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, CustomDiscs.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	public static void register() {
		ItemComponentTooltipProviderRegistry.addFirst(DISC, disc -> (context, consumer, flag, components) -> disc.appendTooltip(consumer, flag));

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(content -> content.insertAfter(Items.JUKEBOX, DISC_BURNER_ITEM));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(content -> content.accept(BLANK_MUSIC_DISC));
	}
}
