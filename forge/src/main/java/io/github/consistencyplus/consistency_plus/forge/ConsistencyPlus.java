package io.github.consistencyplus.consistency_plus.forge;

import com.google.common.base.Suppliers;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.mojang.serialization.Codec;
import io.github.consistencyplus.consistency_plus.ConsistencyPlusMain;
import io.github.consistencyplus.consistency_plus.registry.CPlusBlocks;
import io.github.consistencyplus.consistency_plus.registry.PseudoRegistry;
import io.github.consistencyplus.consistency_plus.util.AdditionalBlockSettings;
import io.github.consistencyplus.consistency_plus.util.BlockData;
import io.github.consistencyplus.consistency_plus.util.BlockShape;
import io.github.consistencyplus.consistency_plus.util.LoaderHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Oxidizable;
import net.minecraft.data.DataGenerator;
import net.minecraft.item.*;
import net.minecraft.loot.condition.LootCondition;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.registry.Registry;
import net.minecraftforge.common.data.GlobalLootModifierProvider;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootTableIdCondition;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

@Mod(ConsistencyPlusMain.MOD_ID)
public class ConsistencyPlus {
	LoaderHelper forge = new LoaderVariant();
	public static Map<Identifier, Identifier> oxidizationMap = new HashMap<>();
	public static Map<Identifier, Identifier> waxingMap = new HashMap<>();
	public static Map<Identifier, String> blockToRenderLayers = new HashMap<>();
	public static Map<Identifier, BlockData> blockDataMap;

	private static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLOBAL_LOOT = DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, "consistency_plus");

	private static final RegistryObject<Codec<WitheredBonesModifier>> CPLUS_WITHERED_BONES = GLOBAL_LOOT.register("withered_bones", WitheredBonesModifier.CODEC);

	public static boolean hasAccessedRegistry = false;

	public ConsistencyPlus() {
		FMLJavaModLoadingContext.get().getModEventBus().register(this);
		GLOBAL_LOOT.register(FMLJavaModLoadingContext.get().getModEventBus());
	}

	@SubscribeEvent
	public void onInitialize(RegisterEvent event) {
		ConsistencyPlusMain.init(forge);

		if (!hasAccessedRegistry) {
			blockDataMap = PseudoRegistry.export();
			hasAccessedRegistry = true;
		}
		event.register(ForgeRegistries.Keys.BLOCKS, helper -> {
			for (Identifier id : blockDataMap.keySet()) {
				BlockData data = blockDataMap.get(id);
				if (data.block() == BlockShape.PROVIDED) {
					accessRegistry(id, data, helper);
					continue;
				}
				if (forge.getIsClient() && data.settings().layer() != null) {
					blockToRenderLayers.put(id, data.settings().layer());
				}
				helper.register(id, data.block().initFunc().apply(data.settings().settings()));
			}
		});

		event.register(ForgeRegistries.Keys.ITEMS, helper -> {
			for (Identifier id : blockDataMap.keySet()) {
				if (Objects.equals(id.getPath(), "warped_wart")) continue;
				BlockData data = blockDataMap.get(id);
				/*if (data.block() == BlockShape.PROVIDED) {
					helper.register(id, new BlockItem(RegistryObject.create(id, ForgeRegistries.BLOCKS).get(), new Item.Settings().group(getItemGroup(data.settings().additionalBlockSettings().itemGroup()))));
					continue;
				}*/
				helper.register(id, new BlockItem(RegistryObject.create(id, ForgeRegistries.BLOCKS).get(), new Item.Settings().group(getItemGroup(data.settings().additionalBlockSettings().itemGroup()))));
			}

			for (Identifier id : CPlusBlocks.itemRegistry.keySet()) {
				helper.register(id, CPlusBlocks.itemRegistry.get(id).apply(new Item.Settings()));
			}

		});

		finish();


	}

	@SubscribeEvent
	public static void lootEvent(GatherDataEvent event) {
		event.getGenerator().addProvider(event.includeServer(), new DataProvider(event.getGenerator(), "consistency_plus"));
	}

	public void accessRegistry(Identifier id, BlockData data, RegisterEvent.RegisterHelper<Block> helper) {
		AdditionalBlockSettings addBloSet = data.settings().additionalBlockSettings();
		Function<AbstractBlock.Settings, Block> blockFunc = CPlusBlocks.registry.get(id);
		helper.register(id, blockFunc.apply(data.settings().settings()));

		if (addBloSet.oxidizeToBlock() != null) {
			oxidizationMap.put(id, new Identifier(addBloSet.oxidizeToBlock()));
		}

		if (addBloSet.waxToBlock() != null) {
			waxingMap.put(id, new Identifier(addBloSet.waxToBlock()));
		}
	}

	// this is yoinked from Create, which is licensed under MIT, so this is as well.
	// https://github.com/Creators-of-Create/Create/blob/mc1.18/dev/src/main/java/com/simibubi/create/foundation/block/CopperRegistries.java
	public static void finish() {
		try {
			Field delegateField = Oxidizable.OXIDATION_LEVEL_INCREASES.getClass().getDeclaredField("delegate");
			delegateField.setAccessible(true);
			// Get the original delegate to prevent an infinite loop
			@SuppressWarnings("unchecked")
			Supplier<BiMap<Block, Block>> originalWeatheringMapDelegate = (Supplier<BiMap<Block, Block>>) delegateField.get(Oxidizable.OXIDATION_LEVEL_INCREASES);
			com.google.common.base.Supplier<BiMap<Block, Block>> weatheringMapDelegate = () -> {
				ImmutableBiMap.Builder<Block, Block> builder = ImmutableBiMap.builder();
				builder.putAll(originalWeatheringMapDelegate.get());
				ConsistencyPlus.oxidizationMap.forEach((lesserID, greaterID) -> {
					builder.put(RegistryObject.create(lesserID, ForgeRegistries.BLOCKS).get(), RegistryObject.create(greaterID, ForgeRegistries.BLOCKS).get());
				});
				return builder.build();
			};
			// Replace the memoized supplier's delegate, since interface fields cannot be reassigned
			delegateField.set(Oxidizable.OXIDATION_LEVEL_INCREASES, weatheringMapDelegate);
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize Consistency+ copper blocks", e);
		}

		Supplier<BiMap<Block, Block>> originalWaxableMapSupplier = HoneycombItem.UNWAXED_TO_WAXED_BLOCKS;
		Supplier<BiMap<Block, Block>> waxableMapSupplier = Suppliers.memoize(() -> {
			ImmutableBiMap.Builder<Block, Block> builder = ImmutableBiMap.builder();
			builder.putAll(originalWaxableMapSupplier.get());
			ConsistencyPlus.waxingMap.forEach((unwaxedID, waxedID) -> {
				builder.put(RegistryObject.create(unwaxedID, ForgeRegistries.BLOCKS).get(), RegistryObject.create(waxedID, ForgeRegistries.BLOCKS).get());
			});
			return builder.build();
		});
		HoneycombItem.UNWAXED_TO_WAXED_BLOCKS = waxableMapSupplier;
	}

	public static ItemGroup getItemGroup(String string) {
		return switch (string) {
			case "stones" -> CPLUS_STONES;
			case "dyeable" -> CPLUS_DYABLE;
			case "misc" -> CPLUS_MISC;
			default -> CPLUS_STONES;
		};
	}


	public static final	ItemGroup CPLUS_STONES = new ItemGroup("consistency_plus.stones") {
		@Override
		public ItemStack createIcon() {
			return RegistryObject.create(new Identifier("consistency_plus", "polished_stone"), ForgeRegistries.ITEMS).get().getDefaultStack();
		}
	};

	public static final	ItemGroup CPLUS_DYABLE = new ItemGroup("consistency_plus.dyeable") {
		@Override
		public ItemStack createIcon() {
			return RegistryObject.create(new Identifier("consistency_plus", "polished_" + DyeColor.byId(Random.create().nextBetween(0, 15)).getName() + "_concrete"), ForgeRegistries.ITEMS).get().getDefaultStack();
		}
	};

	public static final	ItemGroup CPLUS_MISC = new ItemGroup("consistency_plus.misc") {
		@Override
		public ItemStack createIcon() {
			return RegistryObject.create(new Identifier("consistency_plus", "polished_purpur"), ForgeRegistries.ITEMS).get().getDefaultStack();
		}
	};

	private static class DataProvider extends GlobalLootModifierProvider {
		public DataProvider(DataGenerator output, String modid) {
			super(output, modid);
		}

		@Override
		protected void start() {
			add("withered_bones", new WitheredBonesModifier(new LootCondition[] {
					LootTableIdCondition.builder(new Identifier("entities/wither_skeleton")).build()
			}));
		}
	}
}
