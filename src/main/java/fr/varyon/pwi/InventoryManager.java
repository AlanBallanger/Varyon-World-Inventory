package fr.varyon.pwi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class InventoryManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final int INVENTORY_READY_MAX_ATTEMPTS = 200;
    private static final long INVENTORY_READY_RETRY_MS = 100L;

    private final Path dataDirectory;
    private final WorldGroupConfig groupConfig;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * In-memory cache: playerUUID → (storage key → inventory data)
     * Updated by auto-save and used as fallback source at disconnect.
     */
    private final Map<UUID, Map<String, PlayerInventoryData>> cache = new ConcurrentHashMap<>();

    /**
     * Tracks the last known world for each online player.
     * Used in onAdd to know which world's inventory to save before loading the new one.
     */
    private final Map<UUID, String> currentWorld = new ConcurrentHashMap<>();

    /**
     * Stores the Holder reference captured at AddPlayerToWorldEvent time.
     *
     * After addToStore() is called (in onFinishPlayerJoining), the engine sets
     * playerRef.holder = null — so playerRef.getHolder() returns null for the rest
     * of the session. However, the Holder *object* itself remains alive and its
     * component map stays valid.  Keeping a direct reference here lets us read/write
     * inventory at any point (auto-save, disconnect) without going through PlayerRef.
     */
    private final Map<UUID, Holder<EntityStore>> playerHolders = new ConcurrentHashMap<>();

    public InventoryManager(Path dataDirectory, WorldGroupConfig groupConfig) {
        this.dataDirectory = dataDirectory;
        this.groupConfig = groupConfig;
    }

    private static boolean isInstance(String worldName) {
        return worldName != null && worldName.startsWith("instance-");
    }

    /**
     * Fired when a player is added to a world (first join or world change).
     *
     * At this point the holder's inventory still contains the items from the
     * previous world — the engine does NOT reset inventory on world transfer.
     * We therefore:
     *   1. Save the current (old-world) inventory if the group changes.
     *   2. Load the new world's inventory (or clear if none exists yet).
     *   3. Update the world tracker.
     */
    public void onAdd(AddPlayerToWorldEvent event) {
        try {
            onAddUnsafe(event);
        } catch (Throwable t) {
            logFailure("PWI AddPlayerToWorld", t);
        }
    }

    private void onAddUnsafe(AddPlayerToWorldEvent event) {
        World world = event.getWorld();
        if (world == null) return;

        Holder<EntityStore> holder = event.getHolder();
        if (holder == null) return;

        String newWorld = world.getName();
        if (newWorld == null || newWorld.isBlank()) return;

        if (isInstance(newWorld)) return;

        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID playerId = playerRef.getUuid();
        playerHolders.put(playerId, holder);

        String newKey = groupConfig.getStorageKey(newWorld);
        String prevWorld  = currentWorld.get(playerId);

        if (prevWorld != null && !isInstance(prevWorld)) {
            String prevKey = groupConfig.getStorageKey(prevWorld);
            if (!prevKey.equals(newKey)) {
                save(holder, playerId, prevWorld);
            }
        }

        currentWorld.put(playerId, newWorld);

        String prevKey = prevWorld != null ? groupConfig.getStorageKey(prevWorld) : null;
        if (prevKey == null || !prevKey.equals(newKey)) {
            scheduleLoad(world, holder, playerId, newWorld, 0);
        }
    }

    /**
     * Fired when a player disconnects.
     * Saves the cached inventory to disk and cleans up tracking.
     */
    public void onDisconnect(PlayerDisconnectEvent event) {
        try {
            onDisconnectUnsafe(event);
        } catch (Throwable t) {
            logFailure("PWI PlayerDisconnect", t);
        }
    }

    @SuppressWarnings("deprecation")
    public void onLivingInventoryChange(Object event) {
        try {
            onLivingInventoryChangeUnsafe(event);
        } catch (Throwable t) {
            logFailure("PWI LivingEntityInventoryChange", t);
        }
    }

    private void onLivingInventoryChangeUnsafe(Object event) {
        final Object entity;
        final Transaction transaction;
        try {
            entity = event.getClass().getMethod("getEntity").invoke(event);
            transaction = (Transaction) event.getClass().getMethod("getTransaction").invoke(event);
        } catch (ReflectiveOperationException e) {
            return;
        }
        if (!(entity instanceof com.hypixel.hytale.server.core.entity.entities.Player player)) {
            return;
        }
        if (transaction instanceof SlotTransaction slotTx && !slotTx.succeeded()) {
            return;
        }
        PlayerRef ref = player.getPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        UUID playerId = ref.getUuid();
        Holder<EntityStore> holder = playerHolders.get(playerId);
        String worldName = currentWorld.get(playerId);
        if (holder == null || worldName == null || isInstance(worldName)) {
            return;
        }
        String key = groupConfig.getStorageKey(worldName);
        PlayerInventoryData data = PlayerInventoryData.fromHolder(holder);
        cache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(key, data);
    }

    private void onDisconnectUnsafe(PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        if (playerRef == null) return;
        UUID playerId = playerRef.getUuid();

        String lastWorld = currentWorld.remove(playerId);
        Holder<EntityStore> holder = playerHolders.remove(playerId);
        Map<String, PlayerInventoryData> playerMap = cache.remove(playerId);

        if (lastWorld == null || isInstance(lastWorld)) {
            return;
        }

        String key = groupConfig.getStorageKey(lastWorld);

        PlayerInventoryData dataHolder = null;
        if (holder != null) {
            try {
                dataHolder = PlayerInventoryData.fromHolder(holder);
            } catch (Throwable t) {
                logFailure("PWI disconnect fromHolder", t);
            }
        }

        PlayerInventoryData cached = playerMap != null ? playerMap.get(key) : null;

        PlayerInventoryData data = holder != null ? dataHolder : cached;

        if (data != null) {
            writeToFile(playerId, key, data);
            int n = data.getItems() != null ? data.getItems().size() : 0;
            LOGGER.at(Level.INFO).log("SAVED on disconnect: %s | world=%s | key=%s | items=%d",
                    playerId, lastWorld, key, n);
            if (!groupConfig.isWorldListedInGroups(lastWorld)) {
                LOGGER.at(Level.WARNING).log(
                        "PWI: world '%s' is not listed in groups.yml — saved as %s.json. Add this world name under the correct group.",
                        lastWorld, key);
            }
        } else {
            LOGGER.at(Level.WARNING).log(
                    "PWI: no inventory data at disconnect for %s | world=%s | holder=%s | cache=%s",
                    playerId, lastWorld, holder != null ? "ok" : "null", playerMap != null ? "ok" : "null");
        }
    }

    private void save(Holder<EntityStore> holder, UUID playerId, String worldName) {
        try {
            String key = groupConfig.getStorageKey(worldName);
            PlayerInventoryData data = PlayerInventoryData.fromHolder(holder);
            cache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(key, data);
            writeToFile(playerId, key, data);
            int sz = data.getItems() != null ? data.getItems().size() : 0;
            LOGGER.at(Level.INFO).log("SAVED %s | world=%s | key=%s | items=%d",
                    playerId, worldName, key, sz);
        } catch (Throwable t) {
            logFailure("PWI save", t);
        }
    }

    private void scheduleLoad(World world, Holder<EntityStore> holder, UUID playerId, String worldName, int attempt) {
        try {
            world.execute(() -> {
                try {
                    if (!worldName.equals(currentWorld.get(playerId))) {
                        return;
                    }
                    String key = groupConfig.getStorageKey(worldName);
                    Map<String, PlayerInventoryData> playerMap = cache
                            .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                    PlayerInventoryData data = playerMap.computeIfAbsent(key, k -> readFromFile(playerId, key));
                    Path invFile = resolveInvFile(playerId, key);
                    if (data == null && fileExistsNonEmpty(invFile)) {
                        data = readFromFile(playerId, key);
                        if (data != null) {
                            playerMap.put(key, data);
                        } else {
                            LOGGER.at(Level.SEVERE).log(
                                    "PWI: inventory file exists but could not be loaded; not clearing to avoid wipe | %s",
                                    invFile);
                            return;
                        }
                    }

                    boolean hasItems = data != null && data.getItems() != null && !data.getItems().isEmpty();
                    if (hasItems && !PlayerInventoryData.inventoriesReadyForData(holder, data)) {
                        if (attempt < INVENTORY_READY_MAX_ATTEMPTS) {
                            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                                try {
                                    scheduleLoad(world, holder, playerId, worldName, attempt + 1);
                                } catch (Throwable t) {
                                    logFailure("PWI load retry", t);
                                }
                            }, INVENTORY_READY_RETRY_MS, TimeUnit.MILLISECONDS);
                        } else {
                            LOGGER.at(Level.SEVERE).log(
                                    "PWI: inventory containers still not ready after %d attempts — skipping apply to avoid data loss | player=%s | world=%s",
                                    INVENTORY_READY_MAX_ATTEMPTS, playerId, worldName);
                        }
                        return;
                    }

                    if (hasItems) {
                        data.applyToInventory(holder);
                        PlayerInventoryData synced = PlayerInventoryData.fromHolder(holder);
                        playerMap.put(key, synced);
                        int sz = synced.getItems() != null ? synced.getItems().size() : 0;
                        LOGGER.at(Level.INFO).log("LOADED %s | world=%s | key=%s | items=%d",
                                playerId, worldName, key, sz);
                    } else {
                        PlayerInventoryData.clearInventory(holder);
                        PlayerInventoryData empty = PlayerInventoryData.fromHolder(holder);
                        playerMap.put(key, empty);
                        writeToFile(playerId, key, empty);
                        LOGGER.at(Level.INFO).log("NEW cleared | player=%s | world=%s | key=%s",
                                playerId, worldName, key);
                    }
                } catch (Throwable t) {
                    logFailure("PWI scheduleLoad", t);
                }
            });
        } catch (Throwable t) {
            logFailure("PWI world.execute", t);
        }
    }

    private void logFailure(String context, Throwable t) {
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        LOGGER.at(Level.SEVERE).log("%s: %s", context, String.valueOf(t.getMessage()));
    }

    public void saveAll() {
        try {
            int count = 0;
            for (Map.Entry<UUID, String> e : currentWorld.entrySet()) {
                UUID playerId = e.getKey();
                String worldName = e.getValue();
                if (worldName == null || isInstance(worldName)) continue;
                Holder<EntityStore> holder = playerHolders.get(playerId);
                if (holder == null) continue;
                String key = groupConfig.getStorageKey(worldName);
                PlayerInventoryData data = PlayerInventoryData.fromHolder(holder);
                cache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(key, data);
                writeToFile(playerId, key, data);
                count++;
            }
            if (count > 0) {
                LOGGER.at(Level.INFO).log("Auto-save: %d file(s) written", count);
            }
        } catch (Throwable t) {
            logFailure("PWI saveAll", t);
        }
    }

    private Path resolveInvFile(UUID playerId, String storageKey) {
        return dataDirectory.resolve(playerId.toString()).resolve(storageKey + ".json");
    }

    private static boolean fileExistsNonEmpty(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0L;
        } catch (IOException e) {
            return false;
        }
    }

    private void writeToFile(UUID playerId, String storageKey, PlayerInventoryData data) {
        try {
            Path file = resolveInvFile(playerId, storageKey);
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                gson.toJson(data, w);
            }
        } catch (IOException e) {
            LOGGER.at(Level.SEVERE).log("Failed to write inventory file: %s", e.getMessage());
        }
    }

    private PlayerInventoryData readFromFile(UUID playerId, String storageKey) {
        Path file = resolveInvFile(playerId, storageKey);
        if (!Files.isRegularFile(file)) return null;
        try (Reader r = Files.newBufferedReader(file)) {
            return gson.fromJson(r, PlayerInventoryData.class);
        } catch (IOException e) {
            LOGGER.at(Level.SEVERE).log("Failed to read inventory file: %s", e.getMessage());
            return null;
        } catch (JsonSyntaxException e) {
            LOGGER.at(Level.SEVERE).log("Invalid inventory JSON %s: %s", file, e.getMessage());
            return null;
        }
    }

    public static final class WorldGroupConfig {

        private static final HytaleLogger CFG_LOGGER = HytaleLogger.forEnclosingClass();

        public static final String DEFAULT_GROUP_KEY = "default";

        private final Path configFile;

        private final Map<String, String> worldToGroupKey = new HashMap<>();
        private int groupCount = 0;

        private static String normalizeWorldName(String world) {
            if (world == null) return null;
            String t = world.trim();
            return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
        }

        public WorldGroupConfig(Path dataDirectory) {
            this.configFile = dataDirectory.resolve("groups.yml");
            load();
        }

        private void load() {
            if (!Files.exists(configFile)) {
                createDefault();
            }
            reload();
        }

        private void createDefault() {
            try {
                Files.createDirectories(configFile.getParent());
                try (InputStream in = getClass().getResourceAsStream("/groups.yml")) {
                    if (in != null) {
                        try (OutputStream out = Files.newOutputStream(configFile)) {
                            in.transferTo(out);
                            return;
                        }
                    }
                }
                Files.writeString(configFile, "groups: {}\n");
            } catch (IOException e) {
                CFG_LOGGER.at(Level.SEVERE).log("Could not create groups.yml: %s", e.getMessage());
            }
        }

        public void reload() {
            worldToGroupKey.clear();
            groupCount = 0;

            try (BufferedReader reader = Files.newBufferedReader(configFile)) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(reader);
                if (root == null) return;

                @SuppressWarnings("unchecked")
                Map<String, Object> groups = (Map<String, Object>) root.get("groups");
                if (groups == null || groups.isEmpty()) return;

                for (Map.Entry<String, Object> entry : groups.entrySet()) {
                    String groupKey = entry.getKey();
                    if (groupKey == null || groupKey.isBlank()) {
                        CFG_LOGGER.at(Level.WARNING).log("Skipping group with blank name");
                        continue;
                    }

                    Object rawWorlds = entry.getValue();
                    if (!(rawWorlds instanceof List)) {
                        CFG_LOGGER.at(Level.WARNING).log(
                                "Group '%s' must be a list of world names — skipped", groupKey);
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> worlds = (List<String>) rawWorlds;
                    if (worlds.isEmpty()) {
                        CFG_LOGGER.at(Level.WARNING).log("Group '%s' is empty — skipped", groupKey);
                        continue;
                    }

                    groupCount++;
                    for (String world : worlds) {
                        String canon = normalizeWorldName(world);
                        if (canon == null) continue;
                        if (worldToGroupKey.containsKey(canon)) {
                            CFG_LOGGER.at(Level.WARNING).log(
                                    "World '%s' already in a group — overwriting with '%s'",
                                    world != null ? world.trim() : "", groupKey);
                        }
                        worldToGroupKey.put(canon, groupKey);
                    }

                    CFG_LOGGER.at(Level.INFO).log("Loaded group '%s': %d world(s) → %s.json",
                            groupKey, worlds.size(), groupKey);
                }
            } catch (IOException e) {
                CFG_LOGGER.at(Level.SEVERE).log("Could not read groups.yml: %s", e.getMessage());
            }
        }

        public String getStorageKey(String worldName) {
            String canon = normalizeWorldName(worldName);
            if (canon == null) {
                return DEFAULT_GROUP_KEY;
            }
            return worldToGroupKey.getOrDefault(canon, DEFAULT_GROUP_KEY);
        }

        public boolean isWorldListedInGroups(String worldName) {
            String canon = normalizeWorldName(worldName);
            if (canon == null) {
                return false;
            }
            return worldToGroupKey.containsKey(canon);
        }

        public int getGroupCount() {
            return groupCount;
        }
    }
}
