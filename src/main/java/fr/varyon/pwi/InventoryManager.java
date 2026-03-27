package fr.varyon.pwi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class InventoryManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final Path dataDirectory;
    private final WorldGroupConfig groupConfig;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * In-memory cache: playerUUID → (primaryWorld → inventory data)
     * Used for fast access and as source of truth for auto-save / disconnect save.
     */
    private final Map<UUID, Map<String, PlayerInventoryData>> cache = new ConcurrentHashMap<>();

    /**
     * Tracks the last known world for each online player.
     * Used in onAdd to know which world's inventory to save before loading the new one.
     */
    private final Map<UUID, String> currentWorld = new ConcurrentHashMap<>();

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
        World world = event.getWorld();
        if (isInstance(world.getName())) return;

        Holder<EntityStore> holder = event.getHolder();
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID playerId     = playerRef.getUuid();
        String newWorld   = world.getName();
        String newPrimary = groupConfig.getPrimaryWorld(newWorld);
        String prevWorld  = currentWorld.get(playerId);

        if (prevWorld != null && !isInstance(prevWorld)) {
            String prevPrimary = groupConfig.getPrimaryWorld(prevWorld);
            if (!prevPrimary.equals(newPrimary)) {
                // Group change → holder still has old-world items → save them now.
                save(holder, playerId, prevWorld);
            }
        }

        currentWorld.put(playerId, newWorld);

        // Load the new world's inventory (or clear if first visit).
        String prevPrimary = prevWorld != null ? groupConfig.getPrimaryWorld(prevWorld) : null;
        if (prevPrimary == null || !prevPrimary.equals(newPrimary)) {
            load(holder, playerId, newWorld);
        }
    }

    /**
     * Fired when a player disconnects.
     * Saves the cached inventory to disk and cleans up tracking.
     */
    public void onDisconnect(PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID playerId = playerRef.getUuid();
        String lastWorld = currentWorld.remove(playerId);

        Map<String, PlayerInventoryData> playerCache = cache.remove(playerId);
        if (playerCache == null || lastWorld == null) return;

        String primary = groupConfig.getPrimaryWorld(lastWorld);
        PlayerInventoryData data = playerCache.get(primary);
        if (data != null) {
            writeToFile(playerId, primary, data);
            LOGGER.at(Level.INFO).log("SAVED on disconnect: {0} | primary={1} | items={2}",
                    playerId, primary, data.getItems().size());
        }
    }

    private void save(Holder<EntityStore> holder, UUID playerId, String worldName) {
        try {
            String primary = groupConfig.getPrimaryWorld(worldName);
            PlayerInventoryData data = PlayerInventoryData.fromHolder(holder);
            cache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(primary, data);
            writeToFile(playerId, primary, data);
            LOGGER.at(Level.INFO).log("SAVED {0} | world={1} | primary={2} | items={3}",
                    playerId, worldName, primary, data.getItems().size());
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to save inventory for {0}: {1}", playerId, e.getMessage());
        }
    }

    private void load(Holder<EntityStore> holder, UUID playerId, String worldName) {
        try {
            String primary = groupConfig.getPrimaryWorld(worldName);
            PlayerInventoryData data = cache
                    .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(primary, k -> readFromFile(playerId, primary));

            if (data != null && !data.getItems().isEmpty()) {
                data.applyToInventory(holder);
                LOGGER.at(Level.INFO).log("LOADED {0} | world={1} | primary={2} | items={3}",
                        playerId, worldName, primary, data.getItems().size());
            } else {
                PlayerInventoryData.clearInventory(holder);
                LOGGER.at(Level.INFO).log("NEW {0} | world={1} | primary={2} | cleared",
                        playerId, worldName, primary);
            }
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to load inventory for {0}: {1}", playerId, e.getMessage());
        }
    }

    public void saveAll() {
        int count = 0;
        for (Map.Entry<UUID, Map<String, PlayerInventoryData>> entry : cache.entrySet()) {
            for (Map.Entry<String, PlayerInventoryData> worldEntry : entry.getValue().entrySet()) {
                writeToFile(entry.getKey(), worldEntry.getKey(), worldEntry.getValue());
                count++;
            }
        }
        if (count > 0) {
            LOGGER.at(Level.INFO).log("Auto-save: {0} file(s) written", count);
        }
    }

    private void writeToFile(UUID playerId, String primaryWorld, PlayerInventoryData data) {
        Path playerDir = dataDirectory.resolve(playerId.toString());
        try {
            Files.createDirectories(playerDir);
            Path file = playerDir.resolve(primaryWorld + ".json");
            try (Writer w = Files.newBufferedWriter(file)) {
                gson.toJson(data, w);
            }
        } catch (IOException e) {
            LOGGER.at(Level.SEVERE).log("Failed to write inventory file: {0}", e.getMessage());
        }
    }

    private PlayerInventoryData readFromFile(UUID playerId, String primaryWorld) {
        Path file = dataDirectory.resolve(playerId.toString()).resolve(primaryWorld + ".json");
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file)) {
            return gson.fromJson(r, PlayerInventoryData.class);
        } catch (IOException e) {
            LOGGER.at(Level.SEVERE).log("Failed to read inventory file: {0}", e.getMessage());
            return null;
        }
    }
}
