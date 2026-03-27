package fr.varyon.pwi;

import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class WorldGroupConfig {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final Path configFile;

    /**
     * Maps every world name to the "key" of its group (= first world in the list).
     * Worlds not listed here are their own group (key = their own name).
     */
    private final Map<String, String> worldToKey = new HashMap<>();
    private int groupCount = 0;

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
            LOGGER.at(Level.SEVERE).log("Could not create groups.yml: {0}", e.getMessage());
        }
    }

    public void reload() {
        worldToKey.clear();
        groupCount = 0;

        try (var reader = Files.newBufferedReader(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(reader);
            if (root == null) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> groups = (Map<String, Object>) root.get("groups");
            if (groups == null || groups.isEmpty()) return;

            for (Map.Entry<String, Object> entry : groups.entrySet()) {
                String groupName = entry.getKey();

                @SuppressWarnings("unchecked")
                List<String> worlds = (List<String>) entry.getValue();
                if (worlds == null || worlds.isEmpty()) {
                    LOGGER.at(Level.WARNING).log("Group ''{0}'' is empty — skipped", groupName);
                    continue;
                }

                String key = worlds.get(0);
                if (key == null || key.isBlank()) {
                    LOGGER.at(Level.WARNING).log("Group ''{0}'' has a blank first entry — skipped", groupName);
                    continue;
                }

                groupCount++;
                for (String world : worlds) {
                    if (world == null || world.isBlank()) continue;
                    if (worldToKey.containsKey(world)) {
                        LOGGER.at(Level.WARNING).log("World ''{0}'' already in a group — overwriting with ''{1}''",
                                world, groupName);
                    }
                    worldToKey.put(world, key);
                }

                LOGGER.at(Level.INFO).log("Loaded group ''{0}'': {1} world(s), key={2}",
                        groupName, worlds.size(), key);
            }
        } catch (IOException e) {
            LOGGER.at(Level.SEVERE).log("Could not read groups.yml: {0}", e.getMessage());
        }
    }

    /**
     * Returns the save-file key for the given world.
     * Worlds not in any group return their own name as key.
     */
    public String getPrimaryWorld(String worldName) {
        return worldToKey.getOrDefault(worldName, worldName);
    }

    public int getGroupCount() {
        return groupCount;
    }
}
