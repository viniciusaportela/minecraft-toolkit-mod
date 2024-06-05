package net.viniciusaportela.minecrafttoolkitmod;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.Enumeration;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(MinecraftToolkitMod.MODID)
public class MinecraftToolkitMod
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "minecrafttoolkitmod";

    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    public MinecraftToolkitMod()
    {
        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher
            .register(Commands.literal("toolkit-mod")
            .then(Commands.literal("dump")
            .executes(this::dump)));
    }

    public int dump(CommandContext<CommandSourceStack> context) {
        createFolderStructure();
        saveMetadata(context);
        saveItems();
        saveBlockList();
        savePotions();
        saveMods();
        saveEntityList();
        saveAttributeList();
        saveEffects();
        extractAllTextures();
        copyConfigs(context);

        context.getSource().sendSuccess(() -> Component.literal("Data dumped successfully! You can now open your " +
                        "project in Minecraft Toolkit"),
                true);

        return 1;
    }

    private void saveEffects() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> itemData = new HashMap<>();

        for (Map.Entry<ResourceKey<MobEffect>, MobEffect> effectEntry : ForgeRegistries.MOB_EFFECTS.getEntries()) {
            ResourceKey<MobEffect> itemKey = effectEntry.getKey();
            MobEffect effect = effectEntry.getValue();
            ResourceLocation effectId = itemKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", effectId.toString());
            details.put("name", I18n.get(effect.getDescriptionId()));
            details.put("mod", effectId.getNamespace());
            itemData.put(effectId.toString(), details);
        }

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/effects.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(itemData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            player.sendSystemMessage(Component.literal("Please run /toolkit-mod dump to start using the Minecraft " +
                    "Toolkit"));
        }
    }

    private void createFolderStructure() {
        Path basePath = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod");
        Path configs = basePath.resolve("configs");
        Path mods = basePath.resolve("mods");
        Path textures = basePath.resolve("textures");

        try {
            Files.createDirectories(basePath);
            Files.createDirectories(configs);
            Files.createDirectories(mods);
            Files.createDirectories(textures);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveMetadata(CommandContext<CommandSourceStack> context) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> metadata = new HashMap<>();

        metadata.put("modCount", ModList.get().getMods().size());
        metadata.put("timestamp", System.currentTimeMillis() / 1000);
        metadata.put("version", 1);

        MinecraftServer server = context.getSource().getServer();
        ServerLevel overworld = server.getLevel(ServerLevel.OVERWORLD);
        if (overworld != null) {
            Path worldSavePath = server.getWorldPath(LevelResource.ROOT);
            metadata.put("worldPath", worldSavePath.toAbsolutePath().toString());
        }

        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/metadata.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(metadata, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveItems() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> itemData = new HashMap<>();

        for (Map.Entry<ResourceKey<Item>, Item> itemEntry : ForgeRegistries.ITEMS.getEntries()) {
            ResourceKey<Item> itemKey = itemEntry.getKey();
            Item item = itemEntry.getValue();
            ResourceLocation itemId = itemKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", itemId.toString());
            details.put("name", I18n.get(item.getDescriptionId()));
            details.put("mod", itemId.getNamespace());
            itemData.put(itemId.toString(), details);
        }

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/items.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(itemData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void savePotions()
    {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> potionData = new HashMap<>();

        for (Map.Entry<ResourceKey<Potion>, Potion> potionRegistryObject : ForgeRegistries.POTIONS.getEntries()) {
            Potion potion = potionRegistryObject.getValue();
            ResourceLocation potionId = potionRegistryObject.getKey().location();
            if (potionId != null) {
                Map<String, Object> details = new HashMap<>();
                details.put("id", potionId.toString());

                // Collect potion effects
                for (MobEffectInstance effectInstance : potion.getEffects()) {
                    details.put("effect_" + effectInstance.getEffect().getDescriptionId(), effectInstance.getAmplifier());
                }

                potionData.put(potionId.toString(), details);
            }
        }

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/potions.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(potionData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveMods() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> modData = new HashMap<>();

        for (IModInfo modInfo : ModList.get().getMods()) {
            Map<String, Object> details = new HashMap<>();
            details.put("name", modInfo.getDisplayName());
            details.put("id", modInfo.getModId());
            details.put("path", modInfo.getOwningFile().getFile().getFilePath().toString());

            // Get mod icon
            Optional<String> iconPathOptional = modInfo.getLogoFile();
            iconPathOptional.ifPresent(iconPath -> details.put("icon", iconPath));

            modData.put(modInfo.getModId(), details);
        }

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/mods.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(modData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveBlockList() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> blockData = new HashMap<>();

        for (Map.Entry<ResourceKey<Block>, Block> blockEntry : ForgeRegistries.BLOCKS.getEntries()) {
            ResourceKey<Block> blockKey = blockEntry.getKey();
            Block block = blockEntry.getValue();
            ResourceLocation blockId = blockKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("name", I18n.get(block.getDescriptionId()));
            details.put("mod", blockId.getNamespace());
            details.put("id", blockId.toString());
            blockData.put(blockId.toString(), details);
        }

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/blocks.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(blockData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveAttributeList() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> attributeData = new HashMap<>();

        for (Map.Entry<ResourceKey<Attribute>, Attribute> attributeEntry : ForgeRegistries.ATTRIBUTES.getEntries()) {
            ResourceKey<Attribute> attributeKey = attributeEntry.getKey();
            Attribute attribute = attributeEntry.getValue();
            ResourceLocation attributeId = attributeKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", attributeId.toString());
            details.put("name", I18n.get(attribute.getDescriptionId()));
            attributeData.put(attributeId.toString(), details);
        }

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/attributes.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(attributeData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveEntityList() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> entityData = new HashMap<>();

        for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entityEntry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            ResourceKey<EntityType<?>> entityKey = entityEntry.getKey();
            EntityType<?> entityType = entityEntry.getValue();
            ResourceLocation entityId = entityKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", entityId.toString());
            details.put("name", I18n.get(entityType.getDescriptionId()));
            details.put("mod", entityId.getNamespace());
            entityData.put(entityId.toString(), details);
        }

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/entities.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(entityData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void extractAllTextures() {
        List<Path> MinecraftJar = FMLLoader.getLaunchHandler().getMinecraftPaths().minecraftPaths();

        File outputDir = new File(FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit-mod/textures").toString());

        for (Path path : MinecraftJar) {
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            try {
                extractAllTexturesFromJar(path.toString(), outputDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (IModFileInfo modFileInfo : ModList.get().getModFiles()) {
            IModFile modFile = modFileInfo.getFile();
            try {
                extractAllTexturesFromJar(modFile.getFilePath().toString(), outputDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void extractAllTexturesFromJar(String jarFilePath, File outputDir) throws IOException {
        Path jarPath = Paths.get(jarFilePath);
        if (!Files.exists(jarPath) || !jarFilePath.endsWith(".jar")) {
            return;
        }

        try (ZipFile zipFile = new ZipFile(jarFilePath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // Check if the entry is a texture file in any namespace
                if (entry.getName().matches("assets/[^/]+/textures/.*") && !entry.isDirectory()) {
                    String outputPath = entry.getName().replaceFirst("assets/", "");
                    File outputFile = new File(outputDir, outputPath);

                    if (!outputFile.getParentFile().exists()) {
                        outputFile.getParentFile().mkdirs();
                    }
                    try (InputStream inputStream = zipFile.getInputStream(entry);
                         FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private void copyConfigs(CommandContext<CommandSourceStack> context) {
        Path outputDir = Paths.get("minecraft-toolkit-mod/configs");
        try {
            Files.createDirectories(outputDir);

            // Copy global config folder
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path outputConfigDir = outputDir.resolve("config");
            copyDirectory(configDir, outputConfigDir);

            MinecraftServer server = context.getSource().getServer();
            ServerLevel overworld = server.getLevel(ServerLevel.OVERWORLD);
            if (overworld != null) {
                Path worldSavePath = server.getWorldPath(LevelResource.ROOT);
                Path outputServerConfigDir = outputDir.resolve("serverconfig");
                Path serverConfig = worldSavePath.resolve("serverconfig");
                copyDirectory(serverConfig, outputServerConfigDir);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            Files.walk(source).forEach(path -> {
                Path destination = target.resolve(source.relativize(path));
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }
}
