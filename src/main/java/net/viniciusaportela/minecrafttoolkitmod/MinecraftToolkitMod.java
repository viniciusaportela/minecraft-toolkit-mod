package net.viniciusaportela.minecrafttoolkitmod;

import com.google.gson.*;
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
import net.minecraft.world.item.BlockItem;
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
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
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
import java.util.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

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

    private final List<Map<String, Object>> texturePaths = new ArrayList<>();

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher
            .register(Commands.literal("toolkit-mod")
            .then(Commands.literal("dump")
            .executes(this::dump)));
    }

    public int dump(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("Dumping... this can take a while"),
                true);

        createFolderStructure();
        saveItems();
        saveBlockList();
        savePotions();
        saveMods();
        saveEntityList();
        saveAttributeList();
        saveEffects();
        saveRecipes();
        extractAllTextures();
        copyConfigs(context);
        saveMetadata(context);

        context.getSource().sendSuccess(() -> Component.literal("Data dumped successfully! You can now open your " +
                        "project in Minecraft Toolkit"),
                true);

        return 1;
    }

    private void saveEffects() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> itemData = new HashMap<>();
        List<Map<String, Object>> effects = new ArrayList<>();

        int index = 0;
        for (Map.Entry<ResourceKey<MobEffect>, MobEffect> effectEntry : ForgeRegistries.MOB_EFFECTS.getEntries()) {
            ResourceKey<MobEffect> itemKey = effectEntry.getKey();
            MobEffect effect = effectEntry.getValue();
            ResourceLocation effectId = itemKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", effectId.toString());
            details.put("name", I18n.get(effect.getDescriptionId()));
            details.put("mod", effectId.getNamespace());
            details.put("index", index);
            index++;

            effects.add(details);
        }

        itemData.put("mods", effects);
        itemData.put("version", 1);

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/effects.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(itemData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<String> getJars() {
        List<String> jarPath = new ArrayList<>();

        List<Path> minecraftJars = FMLLoader.getLaunchHandler().getMinecraftPaths().minecraftPaths();
        for (Path path : minecraftJars) {
            jarPath.add(path.toString());
        }

        for (IModFileInfo modFileInfo : ModList.get().getModFiles()) {
            IModFile modFile = modFileInfo.getFile();
            jarPath.add(modFile.getFilePath().toString());
        }

        return jarPath;
    }

    private void saveRecipes() {
        Map<String, Object> recipeData = new HashMap<>();
        List<JsonElement> recipes = new ArrayList<>();
        Set<String> recipeTypes = new HashSet<>();

        List<String> jarPaths = this.getJars();

        for (String path : jarPaths) {
            try {
                extractRecipesFromJar(path, recipes, recipeTypes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        recipeData.put("version", 1);
        recipeData.put("recipes", recipes);
        recipeData.put("types", new ArrayList<>(recipeTypes));

        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/recipes.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(recipeData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void extractRecipesFromJar(String jarFilePath, List<JsonElement> recipes, Set<String> recipeTypes) throws IOException {
        Path jarPath = Paths.get(jarFilePath);
        if (!Files.exists(jarPath) || !jarFilePath.endsWith(".jar")) {
            return;
        }

        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().matches("data/.*/recipes/.*\\.json") && !entry.isDirectory()) {
                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                        JsonElement recipeJson = JsonParser.parseReader(new InputStreamReader(inputStream));

                        if (recipeJson.isJsonObject()) {
                            JsonObject recipeObject = recipeJson.getAsJsonObject();
                            recipeObject.addProperty("filePath", entry.getName());
                            recipes.add(recipeObject);

                            if (recipeObject.has("type")) {
                                recipeTypes.add(recipeObject.get("type").getAsString());
                            }
                        }
                    }
                }
            }
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
        Path basePath = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit");
        Path configs = basePath.resolve("configs");
        Path mods = basePath.resolve("mods");
        Path textures = basePath.resolve("assets");
        Path icons = basePath.resolve("icons");

        try {
            Files.createDirectories(basePath);
            Files.createDirectories(configs);
            Files.createDirectories(mods);
            Files.createDirectories(textures);
            Files.createDirectories(icons);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveMetadata(CommandContext<CommandSourceStack> context) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> metadata = new HashMap<>();

        String minecraftVersion = FMLLoader.versionInfo().mcVersion();
        String forgeVersion = ModList.get().getModContainerById("forge").get().getModInfo().getVersion().toString();

        metadata.put("modCount", ModList.get().getMods().size());
        metadata.put("timestamp", System.currentTimeMillis() / 1000);
        metadata.put("modLoader", "forge");
        metadata.put("version", 1);
        metadata.put("minecraftVersion", minecraftVersion);
        metadata.put("loaderVersion", forgeVersion);
        metadata.put("path", FMLPaths.GAMEDIR.get().toString());

        MinecraftServer server = context.getSource().getServer();
        ServerLevel overworld = server.getLevel(ServerLevel.OVERWORLD);
        if (overworld != null) {
            Path worldSavePath = server.getWorldPath(LevelResource.ROOT);
            metadata.put("worldPath", worldSavePath.toAbsolutePath().toString());
        }

        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/metadata.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(metadata, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveItems() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> itemData = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();

        int index = 0;
        for (Map.Entry<ResourceKey<Item>, Item> itemEntry : ForgeRegistries.ITEMS.getEntries()) {
            ResourceKey<Item> itemKey = itemEntry.getKey();
            Item item = itemEntry.getValue();
            ResourceLocation itemId = itemKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", itemId.toString());
            details.put("name", I18n.get(item.getDescriptionId()));
            details.put("mod", itemId.getNamespace());
            details.put("isBlock", item instanceof BlockItem);
            if (item instanceof BlockItem) {
                Block block = ((BlockItem) item).getBlock();
                details.put("blockName", I18n.get(block.getDescriptionId()));

                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
                if (blockId != null) {
                    details.put("blockId", blockId.toString());
                }
            }
            details.put("index", index);
            index++;

            items.add(details);
        }

        itemData.put("items", items);
        itemData.put("version", 1);

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/items.json");
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
        List<Map<String, Object>> potions = new ArrayList<>();
        
        int index = 0;
        for (Map.Entry<ResourceKey<Potion>, Potion> potionRegistryObject : ForgeRegistries.POTIONS.getEntries()) {
            Potion potion = potionRegistryObject.getValue();
            ResourceLocation potionId = potionRegistryObject.getKey().location();
            if (potionId != null) {
                Map<String, Object> details = new HashMap<>();
                details.put("id", potionId.toString());
                details.put("index", index);

                // Collect potion effects
                for (MobEffectInstance effectInstance : potion.getEffects()) {
                    details.put("effect_" + effectInstance.getEffect().getDescriptionId(), effectInstance.getAmplifier());
                }

                potions.add(details);
                index++;
            }
        }

        potionData.put("potions", potions);
        potionData.put("version", 1);

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/potions.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(potionData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveMods() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> modData = new HashMap<>();
        List<Map<String, Object>> mods = new ArrayList<>();

        int index = 0;
        for (IModInfo modInfo : ModList.get().getMods()) {
            Map<String, Object> details = new HashMap<>();
            details.put("name", modInfo.getDisplayName());
            details.put("id", modInfo.getModId());
            details.put("path", modInfo.getOwningFile().getFile().getFilePath().toString());
            details.put("index", index);

            // Get mod icon
            Optional<String> iconPathOptional = modInfo.getLogoFile();
            iconPathOptional.ifPresent(iconPath -> {
                try {
                    String extractedIconPath = extractIcon(modInfo, iconPath);
                    if (!extractedIconPath.isEmpty()) {
                        details.put("icon", extractedIconPath);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            mods.add(details);
            index++;
        }

        modData.put("mods", mods);
        modData.put("version", 1);

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/mods.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(modData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String extractIcon(IModInfo modInfo, String iconPath) throws IOException {
        IModFile modFile = modInfo.getOwningFile().getFile();
        File jarFile = new File(modFile.getFilePath().toString());
        File outputDir = new File(FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/icons").toString());

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry entry = zipFile.getEntry(iconPath);
            if (entry != null) {
                File outputFile = new File(outputDir, modInfo.getModId() + "_" + new File(iconPath).getName());
                try (InputStream inputStream = zipFile.getInputStream(entry);
                     FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, len);
                    }
                }
                return outputFile.getAbsolutePath();
            }
        }

        return "";
    }

    private void saveBlockList() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> blockData = new HashMap<>();
        List<Map<String, Object>> blocks = new ArrayList<>();

        int index = 0;
        for (Map.Entry<ResourceKey<Block>, Block> blockEntry : ForgeRegistries.BLOCKS.getEntries()) {
            ResourceKey<Block> blockKey = blockEntry.getKey();
            Block block = blockEntry.getValue();
            ResourceLocation blockId = blockKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("name", I18n.get(block.getDescriptionId()));
            details.put("mod", blockId.getNamespace());
            details.put("id", blockId.toString());
            details.put("index", index);
            blocks.add(details);
            index++;
        }

        blockData.put("blocks", blocks);
        blockData.put("version", 1);

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/blocks.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(blockData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveAttributeList() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> attributeData = new HashMap<>();
        List<Map<String, Object>> attributes = new ArrayList<>();

        int index = 0;
        for (Map.Entry<ResourceKey<Attribute>, Attribute> attributeEntry : ForgeRegistries.ATTRIBUTES.getEntries()) {
            ResourceKey<Attribute> attributeKey = attributeEntry.getKey();
            Attribute attribute = attributeEntry.getValue();
            ResourceLocation attributeId = attributeKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", attributeId.toString());
            details.put("name", I18n.get(attribute.getDescriptionId()));
            details.put("index", index);
            attributes.add(details);
            index++;
        }

        attributeData.put("attributes", attributes);
        attributeData.put("version", 1);

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/attributes.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(attributeData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveEntityList() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> entityData = new HashMap<>();
        List<Map<String, Object>> entities = new ArrayList<>();

        int index = 0;
        for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entityEntry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            ResourceKey<EntityType<?>> entityKey = entityEntry.getKey();
            EntityType<?> entityType = entityEntry.getValue();
            ResourceLocation entityId = entityKey.location();

            Map<String, Object> details = new HashMap<>();
            details.put("id", entityId.toString());
            details.put("name", I18n.get(entityType.getDescriptionId()));
            details.put("mod", entityId.getNamespace());
            details.put("index", index);
            entities.add(details);
            index++;
        }

        entityData.put("entities", entities);
        entityData.put("version", 1);

        // Save to JSON file
        Path path = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/entities.json");
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(entityData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void extractAllTextures() {
        texturePaths.clear();
        File outputDir = new File(FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/assets").toString());

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        List<String> jarPaths = this.getJars();

        for (String path : jarPaths) {
            try {
                extractAllTexturesFromJar(path, outputDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Path texturesJsonPath = FMLPaths.GAMEDIR.get().resolve("minecraft-toolkit/textures.json");
        saveTexturesToJson(texturesJsonPath);
    }

    private void extractAllTexturesFromJar(String jarFilePath, File outputDir) throws IOException {
        Path jarPath = Paths.get(jarFilePath);
        if (!Files.exists(jarPath) || !jarFilePath.endsWith(".jar")) {
            return;
        }

        try (ZipFile zipFile = new ZipFile(jarFilePath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().matches("assets/[^/]+/textures/.*") && !entry.isDirectory()) {
                    String modId = extractModId(entry.getName());
                    String outputPath = entry.getName().replaceFirst("assets/", "");
                    File outputFile = new File(outputDir, outputPath);

                    Map<String, Object> details = new HashMap<>();
                    details.put("modId", modId);
                    details.put("internalPath", entry.getName());
                    details.put("outPath", outputFile.getAbsolutePath());
                    details.put("index", texturePaths.size());

                    String withoutModId = entry.getName().replaceFirst("assets/[^/]+/", "");

                    details.put("id", modId + ":" + withoutModId);

                    texturePaths.add(details);

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

    private String extractModId(String assetPath) {
        String[] parts = assetPath.split("/");
        return (parts.length > 1) ? parts[1] : "unknown";
    }

    private void saveTexturesToJson(Path jsonPath) {
        Map<String, Object> root = new HashMap<>();
        root.put("textures", texturePaths);
        root.put("version", 1);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(jsonPath.toFile())) {
            gson.toJson(root, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void copyConfigs(CommandContext<CommandSourceStack> context) {
        Path outputDir = Paths.get("minecraft-toolkit/configs");
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
