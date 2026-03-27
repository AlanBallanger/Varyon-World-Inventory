package fr.varyon.pwi;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class PerWorldInventoryPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static PerWorldInventoryPlugin instance;

    private WorldGroupConfig worldGroupConfig;
    private InventoryManager inventoryManager;
    private ScheduledExecutorService autoSaveTask;

    public PerWorldInventoryPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        instance = this;

        worldGroupConfig = new WorldGroupConfig(this.getDataDirectory());
        inventoryManager = new InventoryManager(this.getDataDirectory(), worldGroupConfig);

        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class,
                event -> inventoryManager.onAdd(event));

        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
                event -> inventoryManager.onDisconnect(event));

        startAutoSave();

        LOGGER.at(Level.INFO).log("PerWorldInventory enabled | {0} group(s) configured | data: {1}",
                worldGroupConfig.getGroupCount(), this.getDataDirectory());
    }

    @Override
    protected void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.shutdown();
            try {
                autoSaveTask.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                autoSaveTask.shutdownNow();
            }
        }
        if (inventoryManager != null) {
            inventoryManager.saveAll();
        }
        LOGGER.at(Level.INFO).log("PerWorldInventory disabled — all inventories saved");
    }

    private void startAutoSave() {
        autoSaveTask = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PWI-AutoSave");
            t.setDaemon(true);
            return t;
        });
        autoSaveTask.scheduleAtFixedRate(() -> {
            try {
                inventoryManager.saveAll();
            } catch (Exception e) {
                LOGGER.at(Level.SEVERE).log("Auto-save error: {0}", e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    public static PerWorldInventoryPlugin getInstance() {
        return instance;
    }
}
