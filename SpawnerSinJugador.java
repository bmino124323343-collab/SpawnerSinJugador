package cl.servidor.spawnersinjugador;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SpawnerSinJugador 1.5.0
 * Compatible con Paper 1.21.x y 26.x (un solo .jar). No compatible con Folia.
 */
public final class SpawnerSinJugador extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final String GUI_TITLE = "§1SpawnerSinJugador | Ajustes";

    private final Set<String> playerSpawners = new HashSet<>();
    // Chunks que ESTE plugin marcó como force-loaded. Se persisten para limpiar tras un crash.
    private final Set<String> ownedForcedChunks = new HashSet<>();
    private final Set<UUID> managedMobs = new HashSet<>();
    // Tick (Bukkit.getCurrentTick) a partir del cual cada spawner puede volver a generar.
    private final Map<String, Integer> nextSpawnTick = new HashMap<>();
    private NamespacedKey managedMobKey;
    private BukkitTask task;
    private int nextSpawnerIndex = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        managedMobKey = new NamespacedKey(this, "managed-mob");
        releasePreviouslyOwnedChunks();
        loadSpawners();
        rebuildManagedMobIndex();
        getServer().getPluginManager().registerEvents(this, this);
        forceLoadRegisteredChunks();
        long interval = Math.max(20L, getConfig().getLong("interval-ticks", 20L));
        task = Bukkit.getScheduler().runTaskTimer(this, this::spawnCycle, interval, interval);
        getLogger().info("Activado. Spawners registrados: " + playerSpawners.size());
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        saveSpawners();
        releaseOwnedChunks();
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler(ignoreCancelled = true)
    public void onSpawnerPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.SPAWNER) return;
        String key = key(event.getBlockPlaced().getLocation());
        int max = Math.max(0, getConfig().getInt("max-player-spawners", 20));
        if (!playerSpawners.contains(key) && playerSpawners.size() >= max) {
            event.setCancelled(true);
            msg(event.getPlayer(), "§cSe alcanzó el límite de spawners automáticos del servidor.");
            return;
        }
        Chunk placedChunk = event.getBlockPlaced().getChunk();
        String placedChunkKey = chunkKey(placedChunk.getWorld(), placedChunk.getX(), placedChunk.getZ());
        boolean alreadyForced = placedChunk.isForceLoaded();
        int maxForced = Math.max(1, getConfig().getInt("max-forced-chunks", 8));
        if (!alreadyForced && !ownedForcedChunks.contains(placedChunkKey) && ownedForcedChunks.size() >= maxForced) {
            event.setCancelled(true);
            msg(event.getPlayer(), "§cSe alcanzó el límite de chunks activos para spawners. Contacta a un administrador.");
            return;
        }
        playerSpawners.add(key);
        saveSpawners();
        forceLoad(placedChunk);
        if (getConfig().getBoolean("verbose-log", false)) getLogger().info("Spawner de jugador registrado en " + key);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawnerBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) return;
        String key = key(event.getBlock().getLocation());
        if (!playerSpawners.remove(key)) return;
        forgetSpawner(key);
        saveSpawners();
        Chunk chunk = event.getBlock().getChunk();
        if (!hasRegisteredSpawnerInChunk(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            unforceChunkIfOwned(chunk.getWorld(), chunk.getX(), chunk.getZ());
        }
        if (getConfig().getBoolean("verbose-log", false)) getLogger().info("Spawner de jugador eliminado en " + key);
    }

    @EventHandler
    public void onManagedMobDeath(EntityDeathEvent event) {
        managedMobs.remove(event.getEntity().getUniqueId());
    }

    // EntitiesLoadEvent: en versiones modernas las entidades se cargan aparte del chunk,
    // por eso ChunkLoadEvent#getChunk().getEntities() puede venir vacío.
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity.getPersistentDataContainer().has(managedMobKey, PersistentDataType.BYTE)) {
                managedMobs.add(entity.getUniqueId());
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de generación

    private void spawnCycle() {
        if (playerSpawners.isEmpty()) return;

        List<String> entries = new ArrayList<>(playerSpawners);
        int maxPerCycle = Math.max(1, getConfig().getInt("max-spawners-per-cycle", 20));
        int maxSpawnsPerCycle = Math.max(1, getConfig().getInt("max-total-spawns-per-cycle", 20));
        int maxManagedMobs = Math.max(1, getConfig().getInt("max-managed-mobs", 1200));
        pruneManagedMobs();
        if (managedMobs.size() >= maxManagedMobs) return;
        int processed = Math.min(maxPerCycle, entries.size());
        int totalSpawned = 0;
        boolean changed = false;
        int now = Bukkit.getCurrentTick();

        for (int offset = 0; offset < processed && totalSpawned < maxSpawnsPerCycle; offset++) {
            int index = Math.floorMod(nextSpawnerIndex + offset, entries.size());
            String entry = entries.get(index);
            SpawnerPos pos = parse(entry);
            if (pos == null) {
                playerSpawners.remove(entry);
                forgetSpawner(entry);
                changed = true;
                continue;
            }
            World world = Bukkit.getWorld(pos.worldId());
            if (world == null) continue;
            int chunkX = pos.x() >> 4;
            int chunkZ = pos.z() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) continue; // nunca cargar chunks desde el ciclo

            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (block.getType() != Material.SPAWNER) {
                playerSpawners.remove(entry);
                forgetSpawner(entry);
                changed = true;
                if (!hasRegisteredSpawnerInChunk(world, chunkX, chunkZ)) {
                    unforceChunkIfOwned(world, chunkX, chunkZ);
                }
                continue;
            }
            BlockState state = block.getState();
            if (!(state instanceof CreatureSpawner spawner)) continue;
            EntityType type = spawner.getSpawnedType();
            if (type == null || !type.isAlive() || type == EntityType.PLAYER) continue;

            String path = "spawner-settings." + entry + ".";
            if (!getConfig().getBoolean(path + "enabled", true)) continue;

            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            if (hasNearbyPlayer(world, center, 16.0)) continue; // con jugadores cerca actúa el spawner vanilla

            // Intervalo por spawner (relativo al tick actual, no a un múltiplo del reloj del servidor).
            int seconds = Math.max(1, Math.min(10, getConfig().getInt(path + "interval-seconds", 1)));
            Integer due = nextSpawnTick.get(entry);
            if (due != null && now < due) continue;
            nextSpawnTick.put(entry, now + seconds * 20);

            int defaultCap = getConfig().getInt("nearby-mob-cap", 60);
            int cap = Math.max(1, Math.min(60, getConfig().getInt(path + "cap", defaultCap)));
            int nearby = countNearby(world, center, type);
            if (nearby >= cap) continue;

            int attempts = Math.max(1, Math.min(10, getConfig().getInt("spawn-attempts", 1)));
            int range = Math.max(1, Math.min(8, getConfig().getInt("spawn-range", 4)));
            for (int i = 0; i < attempts && nearby < cap && totalSpawned < maxSpawnsPerCycle
                    && managedMobs.size() < maxManagedMobs; i++) {
                Location spawnAt = chooseSpawnLocation(world, pos, range);
                if (!validSpawn(spawnAt, type)) continue;
                try {
                    Entity spawned = world.spawnEntity(spawnAt, type);
                    if (spawned instanceof LivingEntity living) living.setRemoveWhenFarAway(false);
                    spawned.getPersistentDataContainer().set(managedMobKey, PersistentDataType.BYTE, (byte) 1);
                    managedMobs.add(spawned.getUniqueId());
                    nearby++;
                    totalSpawned++;
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    getLogger().fine("No se pudo generar " + type + " en " + entry + ": " + ex.getMessage());
                }
            }
        }
        nextSpawnerIndex = Math.floorMod(nextSpawnerIndex + processed, Math.max(1, entries.size()));
        if (changed) saveSpawners();
    }

    private void rebuildManagedMobIndex() {
        managedMobs.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(managedMobKey, PersistentDataType.BYTE)) {
                    managedMobs.add(entity.getUniqueId());
                }
            }
        }
        getLogger().info("Criaturas del plugin detectadas en chunks cargados: " + managedMobs.size());
    }

    private void pruneManagedMobs() {
        // No quitar UUIDs ausentes: pueden pertenecer a entidades en chunks descargados.
        managedMobs.removeIf(uuid -> {
            Entity entity = Bukkit.getEntity(uuid);
            return entity != null && !entity.isValid();
        });
    }

    private boolean hasNearbyPlayer(World world, Location center, double radius) {
        double radiusSquared = radius * radius;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= radiusSquared) return true;
        }
        return false;
    }

    private int countNearby(World world, Location center, EntityType type) {
        int count = 0;
        for (Entity entity : world.getNearbyEntities(center, 16, 16, 16)) {
            if (entity.getType() == type) count++;
        }
        return count;
    }

    private Location chooseSpawnLocation(World world, SpawnerPos pos, int range) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int dx = random.nextInt(-range, range + 1);
        int dz = random.nextInt(-range, range + 1);
        int dy = random.nextInt(-1, 2);
        return new Location(world, pos.x() + 0.5 + dx, pos.y() + dy, pos.z() + 0.5 + dz);
    }

    private boolean validSpawn(Location location, EntityType type) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        if (!feet.isPassable() || !head.isPassable() || !floor.getType().isSolid()) return false;
        // Orientado a criaturas terrestres; las acuáticas no se simulan.
        return type != EntityType.COD && type != EntityType.SALMON
                && type != EntityType.TROPICAL_FISH && type != EntityType.PUFFERFISH
                && type != EntityType.SQUID && type != EntityType.GLOW_SQUID
                && type != EntityType.DOLPHIN && type != EntityType.AXOLOTL
                && type != EntityType.TADPOLE;
    }

    // ------------------------------------------------------------------ chunks forzados

    private void forceLoadRegisteredChunks() {
        int maxForced = Math.max(1, getConfig().getInt("max-forced-chunks", 8));
        for (String entry : new ArrayList<>(playerSpawners)) {
            if (ownedForcedChunks.size() >= maxForced) {
                getLogger().warning("Se alcanzó max-forced-chunks; algunos spawners quedarán inactivos hasta liberar un chunk.");
                break;
            }
            SpawnerPos pos = parse(entry);
            if (pos == null) {
                playerSpawners.remove(entry);
                continue;
            }
            World world = Bukkit.getWorld(pos.worldId());
            if (world != null) forceLoad(world.getChunkAt(pos.x() >> 4, pos.z() >> 4));
        }
        saveSpawners();
    }

    private void forceLoad(Chunk chunk) {
        World world = chunk.getWorld();
        String key = chunkKey(world, chunk.getX(), chunk.getZ());
        // No apropiarse de chunks que ya estaban forzados por otro sistema.
        if (chunk.isForceLoaded()) return;
        chunk.setForceLoaded(true);
        ownedForcedChunks.add(key);
        saveOwnedChunks();
    }

    private void unforceChunkIfOwned(World world, int x, int z) {
        String key = chunkKey(world, x, z);
        if (!ownedForcedChunks.remove(key)) return;
        if (world.isChunkLoaded(x, z)) {
            Chunk chunk = world.getChunkAt(x, z);
            if (chunk.isForceLoaded()) chunk.setForceLoaded(false);
        }
        saveOwnedChunks();
    }

    private void releasePreviouslyOwnedChunks() {
        for (String entry : getConfig().getStringList("plugin-forced-chunks")) {
            releaseChunkEntry(entry);
        }
        ownedForcedChunks.clear();
        saveOwnedChunks();
    }

    private void releaseOwnedChunks() {
        for (String entry : new ArrayList<>(ownedForcedChunks)) {
            releaseChunkEntry(entry);
        }
        ownedForcedChunks.clear();
        saveOwnedChunks();
    }

    private void releaseChunkEntry(String entry) {
        String[] parts = entry.split(":");
        if (parts.length != 3) return;
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            int x = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            if (world != null && world.isChunkLoaded(x, z)) {
                Chunk chunk = world.getChunkAt(x, z);
                if (chunk.isForceLoaded()) chunk.setForceLoaded(false);
            }
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Entrada de chunk forzado inválida: " + entry);
        }
    }

    private boolean hasRegisteredSpawnerInChunk(World world, int chunkX, int chunkZ) {
        for (String entry : playerSpawners) {
            SpawnerPos pos = parse(entry);
            if (pos != null && pos.worldId().equals(world.getUID())
                    && (pos.x() >> 4) == chunkX && (pos.z() >> 4) == chunkZ) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ persistencia

    private String key(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    private String chunkKey(World world, int x, int z) {
        return world.getUID() + ":" + x + ":" + z;
    }

    private SpawnerPos parse(String value) {
        String[] parts = value.split(":");
        if (parts.length != 4) return null;
        try {
            return new SpawnerPos(UUID.fromString(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void forgetSpawner(String entry) {
        nextSpawnTick.remove(entry);
        getConfig().set("spawner-settings." + entry, null);
    }

    private void loadSpawners() {
        playerSpawners.clear();
        playerSpawners.addAll(getConfig().getStringList("player-spawners"));
    }

    private void saveSpawners() {
        getConfig().set("player-spawners", new ArrayList<>(playerSpawners));
        saveConfig();
    }

    private void saveOwnedChunks() {
        getConfig().set("plugin-forced-chunks", new ArrayList<>(ownedForcedChunks));
        saveConfig();
    }

    // ------------------------------------------------------------------ comando y panel

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("spawnersj")) return false;
        if (!(sender instanceof Player player)) {
            msg(sender, "§eSpawnerSinJugador: registrados " + playerSpawners.size());
            return true;
        }
        if (!player.hasPermission("spawnersj.admin")) {
            msg(player, "§cSolo administradores pueden modificar los spawners.");
            return true;
        }
        String nearest = findNearestSpawner(player, 6.0);
        if (nearest == null) {
            msg(player, "§cPárate a menos de 6 bloques de un spawner colocado por un jugador y ejecuta /spawnersj.");
            msg(player, "§7Los spawners naturales no pueden configurarse con este panel.");
            return true;
        }
        openSpawnerMenu(player, nearest);
        return true;
    }

    private String findNearestSpawner(Player player, double maxDistance) {
        String nearest = null;
        double best = maxDistance * maxDistance;
        for (String entry : playerSpawners) {
            SpawnerPos pos = parse(entry);
            if (pos == null || !pos.worldId().equals(player.getWorld().getUID())) continue;
            Location loc = new Location(player.getWorld(), pos.x() + .5, pos.y() + .5, pos.z() + .5);
            double distance = loc.distanceSquared(player.getLocation());
            if (distance <= best) { best = distance; nearest = entry; }
        }
        return nearest;
    }

    /** Holder propio: identifica el panel sin depender del título (que cambió de API entre versiones). */
    private static final class MenuHolder implements InventoryHolder {
        private final String entry;
        private Inventory inventory;
        MenuHolder(String entry) { this.entry = entry; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private void openSpawnerMenu(Player player, String entry) {
        MenuHolder holder = new MenuHolder(entry);
        Inventory inv = Bukkit.createInventory(holder, 27, LEGACY.deserialize(GUI_TITLE));
        holder.inventory = inv;
        String path = "spawner-settings." + entry + ".";
        boolean enabled = getConfig().getBoolean(path + "enabled", true);
        int cap = Math.max(1, Math.min(60, getConfig().getInt(path + "cap", getConfig().getInt("nearby-mob-cap", 60))));
        int seconds = Math.max(1, Math.min(10, getConfig().getInt(path + "interval-seconds", 1)));
        inv.setItem(10, menuItem(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "§aGeneración: " + (enabled ? "§lACTIVADA" : "§lDESACTIVADA"),
                "§7Pulsa para activar/desactivar.", "§8Solo afecta a este spawner."));
        inv.setItem(12, menuItem(Material.SPAWNER, "§eLímite de criaturas: §f" + cap,
                "§7Pulsa para cambiar el límite.", "§7Máximo: 60 criaturas del mismo tipo", "§7en un radio de 16 bloques."));
        inv.setItem(14, menuItem(Material.CLOCK, "§bIntervalo: §f" + seconds + " s",
                "§7Pulsa para cambiar la velocidad.", "§7Mínimo: 1 segundo. Máximo: 10 segundos.", "§7Intervalos menores generan más carga."));
        inv.setItem(16, menuItem(Material.BOOK, "§f¿Cómo se utiliza?",
                "§7Acércate al spawner colocado por un jugador.", "§7Pulsa los botones para cambiar los valores.",
                "§7Los cambios se guardan automáticamente.", "§7Los spawners naturales no se modifican."));
        inv.setItem(22, menuItem(Material.BARRIER, "§cCerrar panel", "§7Cierra este menú sin cambiar nada."));
        player.openInventory(inv);
    }

    private ItemStack menuItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plain(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(plain(line));
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;
        if (!player.hasPermission("spawnersj.admin")) { player.closeInventory(); return; }

        String entry = holder.entry;
        String path = "spawner-settings." + entry + ".";
        switch (event.getRawSlot()) {
            case 10 -> getConfig().set(path + "enabled", !getConfig().getBoolean(path + "enabled", true));
            case 12 -> {
                int cap = getConfig().getInt(path + "cap", getConfig().getInt("nearby-mob-cap", 60));
                getConfig().set(path + "cap", cap >= 60 ? 10 : Math.min(60, cap + 10));
            }
            case 14 -> {
                int seconds = getConfig().getInt(path + "interval-seconds", 1);
                getConfig().set(path + "interval-seconds", seconds >= 10 ? 1 : seconds + 1);
                nextSpawnTick.remove(entry);
            }
            case 22 -> { player.closeInventory(); return; }
            case 16 -> {
                msg(player, "§6§lAyuda SpawnerSinJugador");
                msg(player, "§eGeneración: §7activa o pausa este spawner.");
                msg(player, "§eLímite: §7máximo de criaturas del mismo tipo cerca.");
                msg(player, "§eIntervalo: §7segundos entre ciclos de este spawner.");
                msg(player, "§eRendimiento: §7usa límites bajos para evitar exceso de entidades.");
                return;
            }
            default -> { return; }
        }
        saveConfig();
        openSpawnerMenu(player, entry);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof MenuHolder) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ utilidades de texto

    private static Component plain(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    private static void msg(CommandSender target, String legacy) {
        target.sendMessage(LEGACY.deserialize(legacy));
    }

    private record SpawnerPos(UUID worldId, int x, int y, int z) { }
}
