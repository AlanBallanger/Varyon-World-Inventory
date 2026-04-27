package fr.varyon.pwi;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public class PerWorldInventoryPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static PerWorldInventoryPlugin instance;

    private InventoryManager.WorldGroupConfig worldGroupConfig;
    private InventoryManager inventoryManager;
    private ScheduledExecutorService autoSaveTask;

    public PerWorldInventoryPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        instance = this;

        worldGroupConfig = new InventoryManager.WorldGroupConfig(this.getDataDirectory());
        inventoryManager = new InventoryManager(this.getDataDirectory(), worldGroupConfig);

        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class,
                event -> inventoryManager.onAdd(event));

        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
                event -> inventoryManager.onDisconnect(event));

        registerLivingInventoryChangeListener();

        startAutoSave();

        LOGGER.at(Level.INFO).log("PerWorldInventory enabled | %d group(s) configured | data: %s",
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerLivingInventoryChangeListener() {
        final String fqcn = "com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent";
        Class<?> eventClass = null;
        ClassLoader[] loaders = new ClassLoader[] {
                JavaPlugin.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                getClass().getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                eventClass = Class.forName(fqcn, false, loader);
                break;
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (eventClass == null) {
            LOGGER.at(Level.WARNING).log("PWI: %s not found — inventory cache will only update on world change / auto-save",
                    fqcn);
            return;
        }
        try {
            Consumer<Object> handler = inventoryManager::onLivingInventoryChange;
            getEventRegistry().registerGlobal((Class) eventClass, (Consumer) handler);
        } catch (Throwable t) {
            LOGGER.at(Level.SEVERE).log("PWI: inventory change listener: %s", t.getMessage());
        }
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
                LOGGER.at(Level.SEVERE).log("Auto-save error: %s", e.getMessage());
            }
        }, 120, 120, TimeUnit.SECONDS);
    }

    public static PerWorldInventoryPlugin getInstance() {
        return instance;
    }
}
