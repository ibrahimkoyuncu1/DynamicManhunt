package com.kinglocal.manhunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class DynamicManhunt extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private UUID runnerUUID = null;
    private final Set<UUID> hunters = new HashSet<>();
    private final Map<UUID, Integer> individualTiers = new HashMap<>();
    private final Map<UUID, Map<String, Location>> portalLocationsByWorld = new HashMap<>();

    private boolean gameStarted = false;
    private static final int MAX_TIER = 5;
    private static final int TARGET_DURATION = 25 * 60;
    private int timeRemaining;

    private BossBar timerBar;
    private BukkitRunnable gameTask = null;
    private NamespacedKey manhuntItemKey;

    @Override
    public void onEnable() {
        manhuntItemKey = new NamespacedKey(this, "manhunt_item");
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("runner")).setExecutor(this);
        Objects.requireNonNull(getCommand("runner")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("hunter")).setExecutor(this);
        Objects.requireNonNull(getCommand("hunter")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("manhunt")).setExecutor(this);
        Objects.requireNonNull(getCommand("manhunt")).setTabCompleter(this);

        timerBar = Bukkit.createBossBar("§6Manhunt Bekleniyor", BarColor.PURPLE, BarStyle.SOLID);
        timerBar.setVisible(false);
        getLogger().info("DynamicManhunt v2.3 aktif!");
    }

    @Override
    public void onDisable() {
        stopGame();
        if (timerBar != null) timerBar.removeAll();
        getLogger().info("DynamicManhunt devre dışı.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, String[] args) {
        if (!sender.hasPermission("manhunt.admin")) {
            sender.sendMessage(Component.text("Bu komutu kullanmak için yetkiniz yok!", NamedTextColor.RED));
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "runner"  -> { return handleRunnerCommand(sender, args); }
            case "hunter"  -> { return handleHunterCommand(sender, args); }
            case "manhunt" -> { return handleManhuntCommand(sender, args); }
        }
        return false;
    }

    private boolean handleRunnerCommand(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Hata: Oyuncu bulunamadı veya çevrimdışı!", NamedTextColor.RED));
            return true;
        }
        if (runnerUUID != null) {
            Player oldRunner = Bukkit.getPlayer(runnerUUID);
            if (oldRunner != null) {
                hunters.add(oldRunner.getUniqueId());
                individualTiers.put(oldRunner.getUniqueId(), 0);
                if (gameStarted) { giveArmor(oldRunner, 0); giveCompass(oldRunner); }
                sender.sendMessage(Component.text("Eski Runner (" + oldRunner.getName() + ") Avcı takımına aktarıldı.", NamedTextColor.YELLOW));
            }
        }
        hunters.remove(target.getUniqueId());
        individualTiers.remove(target.getUniqueId());
        if (gameStarted) removeManhuntArmor(target);
        runnerUUID = target.getUniqueId();
        sender.sendMessage(Component.text("Yeni Runner: " + target.getName(), NamedTextColor.GREEN));
        if (gameStarted) timerBar.addPlayer(target);
        return true;
    }

    private boolean handleHunterCommand(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Hata: Oyuncu bulunamadı veya çevrimdışı!", NamedTextColor.RED));
            return true;
        }
        if (runnerUUID != null && runnerUUID.equals(target.getUniqueId())) runnerUUID = null;
        hunters.add(target.getUniqueId());
        individualTiers.putIfAbsent(target.getUniqueId(), 0);
        sender.sendMessage(Component.text("Hunter Listesine Eklendi: " + target.getName(), NamedTextColor.RED));
        if (gameStarted) {
            giveArmor(target, individualTiers.get(target.getUniqueId()));
            giveCompass(target);
            timerBar.addPlayer(target);
        }
        return true;
    }

    private boolean handleManhuntCommand(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        if (args[0].equalsIgnoreCase("start")) {
            if (runnerUUID == null) {
                sender.sendMessage(Component.text("Hata: Önce bir Runner belirlemelisin!", NamedTextColor.RED));
                return true;
            }
            if (hunters.isEmpty()) {
                sender.sendMessage(Component.text("Hata: En az bir Hunter gerekli!", NamedTextColor.RED));
                return true;
            }
            startNewGame();
            sender.sendMessage(Component.text("Manhunt Başlatıldı!", NamedTextColor.GREEN));
        } else if (args[0].equalsIgnoreCase("stop")) {
            stopGame();
            sender.sendMessage(Component.text("Manhunt Durduruldu!", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, String[] args) {
        if (!sender.hasPermission("manhunt.admin")) return Collections.emptyList();
        if (args.length == 1) {
            if (cmd.getName().equalsIgnoreCase("manhunt")) {
                return Arrays.asList("start", "stop").stream()
                        .filter(c -> c.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            } else {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private void startNewGame() {
        if (gameTask != null) { gameTask.cancel(); gameTask = null; }
        gameStarted = true;
        individualTiers.clear();
        portalLocationsByWorld.clear();
        timeRemaining = TARGET_DURATION;
        timerBar.removeAll();
        updateTimerBarVisibility();
        timerBar.setVisible(true);
        timerBar.setProgress(1.0);
        for (UUID uuid : hunters) {
            individualTiers.put(uuid, 0);
            Player hunter = Bukkit.getPlayer(uuid);
            if (hunter != null) { removeManhuntArmor(hunter); giveCompass(hunter); }
        }
        runEvolutionClock();
    }

    private void stopGame() {
        gameStarted = false;
        if (gameTask != null) { gameTask.cancel(); gameTask = null; }
        if (timerBar != null) { timerBar.removeAll(); timerBar.setVisible(false); }
        for (UUID uuid : hunters) {
            Player hunter = Bukkit.getPlayer(uuid);
            if (hunter != null) removeManhuntArmor(hunter);
        }
        hunters.clear();
        individualTiers.clear();
        portalLocationsByWorld.clear();
        runnerUUID = null;
    }

    private void updateTimerBarVisibility() {
        timerBar.removeAll();
        if (runnerUUID != null) {
            Player p = Bukkit.getPlayer(runnerUUID);
            if (p != null) timerBar.addPlayer(p);
        }
        for (UUID uuid : hunters) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) timerBar.addPlayer(p);
        }
    }

    private void runEvolutionClock() {
        if (gameTask != null) return;
        gameTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!gameStarted) { cancel(); gameTask = null; return; }
                if (timeRemaining <= 0) {
                    boolean everyoneMax = hunters.stream()
                            .allMatch(uuid -> individualTiers.getOrDefault(uuid, 0) >= MAX_TIER);
                    if (everyoneMax) {
                        timerBar.setTitle("§d§lTüm Avcılar Maksimum Seviyede!");
                        cancel(); gameTask = null; return;
                    }
                    for (UUID uuid : hunters) {
                        int pTier = individualTiers.getOrDefault(uuid, 0);
                        if (pTier < MAX_TIER) {
                            pTier++;
                            individualTiers.put(uuid, pTier);
                            Player hunter = Bukkit.getPlayer(uuid);
                            if (hunter != null) {
                                giveArmor(hunter, pTier);
                                playTierSound(hunter, pTier);
                                hunter.sendMessage(Component.text(
                                        "Zırh seviyeniz yükseltildi: " + getTierName(pTier),
                                        NamedTextColor.GOLD, TextDecoration.BOLD));
                            }
                        }
                    }
                    timeRemaining = TARGET_DURATION;
                } else {
                    timeRemaining--;
                    double progress = (double) timeRemaining / TARGET_DURATION;
                    timerBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                    int mins = timeRemaining / 60;
                    int secs = timeRemaining % 60;
                    int lowestTier = hunters.stream()
                            .mapToInt(uuid -> individualTiers.getOrDefault(uuid, 0))
                            .min().orElse(0);
                    String nextTierName = lowestTier < MAX_TIER ? getTierName(lowestTier + 1) : "Maksimum Seviye";
                    timerBar.setTitle(String.format("§d§lSonraki Gelişim: §f%02d:%02d §7-> §e%s", mins, secs, nextTierName));
                }
            }
        };
        gameTask.runTaskTimer(this, 0L, 20L);
    }

    private void giveArmor(Player player, int tier) {
        removeManhuntArmor(player);
        if (tier == 0) return;
        Material[] materials = switch (tier) {
            case 1 -> new Material[]{Material.LEATHER_BOOTS, Material.LEATHER_LEGGINGS, Material.LEATHER_CHESTPLATE, Material.LEATHER_HELMET};
            case 2 -> new Material[]{Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET};
            case 3 -> new Material[]{Material.IRON_BOOTS, Material.IRON_LEGGINGS, Material.IRON_CHESTPLATE, Material.IRON_HELMET};
            case 4 -> new Material[]{Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_HELMET};
            case 5 -> new Material[]{Material.NETHERITE_BOOTS, Material.NETHERITE_LEGGINGS, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_HELMET};
            default -> null;
        };
        if (materials == null) return;
        ItemStack[] armorItems = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            ItemStack item = new ItemStack(materials[i]);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setUnbreakable(true);
                meta.getPersistentDataContainer().set(manhuntItemKey, PersistentDataType.BYTE, (byte) 1);
                if (meta instanceof ArmorMeta armorMeta) {
                    TrimPattern pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("host"));
                    TrimMaterial trimMat = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft("amethyst"));
                    if (pattern != null && trimMat != null) armorMeta.setTrim(new ArmorTrim(trimMat, pattern));
                }
                item.setItemMeta(meta);
            }
            armorItems[i] = item;
        }
        player.getInventory().setBoots(armorItems[0]);
        player.getInventory().setLeggings(armorItems[1]);
        player.getInventory().setChestplate(armorItems[2]);
        player.getInventory().setHelmet(armorItems[3]);
    }

    private void removeManhuntArmor(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (isManhuntItem(armor[i])) armor[i] = null;
        }
        player.getInventory().setArmorContents(armor);
    }

    private void giveCompass(Player player) {
        boolean hasManhuntCompass = Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull).anyMatch(this::isManhuntItem);
        if (!hasManhuntCompass) {
            ItemStack compass = new ItemStack(Material.COMPASS);
            ItemMeta meta = compass.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(manhuntItemKey, PersistentDataType.BYTE, (byte) 1);
                compass.setItemMeta(meta);
            }
            player.getInventory().addItem(compass);
        }
    }

    private void playTierSound(Player player, int tier) {
        Sound sound = switch (tier) {
            case 1 -> Sound.ITEM_ARMOR_EQUIP_LEATHER;
            case 2 -> Sound.ITEM_ARMOR_EQUIP_GOLD;
            case 3 -> Sound.ITEM_ARMOR_EQUIP_IRON;
            case 4 -> Sound.ENTITY_WIND_CHARGE_WIND_BURST;
            case 5 -> Sound.BLOCK_BEACON_ACTIVATE;
            default -> null;
        };
        if (sound != null) player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private String getTierName(int tier) {
        return switch (tier) {
            case 1 -> "Deri Zırh";
            case 2 -> "Altın Zırh";
            case 3 -> "Demir Zırh";
            case 4 -> "Elmas Zırh";
            case 5 -> "Netherite Zırh";
            default -> "Maksimum Seviye";
        };
    }

    private void broadcastWin(boolean runnerWon) {
        Component broadcast;
        Title title;
        if (runnerWon) {
            broadcast = Component.text("RUNNER KAZANDI! ", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .append(Component.text("Ender Dragon katledildi.", NamedTextColor.GOLD));
            title = Title.title(Component.text("RUNNER KAZANDI!", NamedTextColor.GREEN),
                    Component.text("Ejderha kesildi.", NamedTextColor.YELLOW));
        } else {
            broadcast = Component.text("AVCI TAKIMI KAZANDI! ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text("Runner elendi.", NamedTextColor.GOLD));
            title = Title.title(Component.text("AVCILER KAZANDI!", NamedTextColor.RED),
                    Component.text("Runner katledildi.", NamedTextColor.YELLOW));
        }
        Bukkit.broadcast(broadcast);
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(title);
    }

    private boolean isManhuntItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(manhuntItemKey, PersistentDataType.BYTE);
    }

    private void clearManhuntItemsFromInventory(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            if (isManhuntItem(player.getInventory().getItem(i))) player.getInventory().setItem(i, null);
        }
        removeManhuntArmor(player);
        if (isManhuntItem(player.getInventory().getItemInOffHand())) player.getInventory().setItemInOffHand(null);
    }

    @EventHandler
    public void onArmorClick(InventoryClickEvent e) {
        if (!gameStarted || !hunters.contains(e.getWhoClicked().getUniqueId())) return;
        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        if (!isManhuntItem(current) && !isManhuntItem(cursor)) return;
        boolean isArmorSlot   = e.getSlotType() == InventoryType.SlotType.ARMOR;
        boolean isShiftClick  = e.getClick().isShiftClick();
        boolean isHotbarSwap  = e.getClick() == ClickType.NUMBER_KEY;
        boolean isDrop        = e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP;
        boolean isSwapOffhand = e.getClick() == ClickType.SWAP_OFFHAND;
        boolean isCreative    = e.getClick() == ClickType.CREATIVE;
        boolean isMiddle      = e.getClick() == ClickType.MIDDLE;
        if (isArmorSlot || isShiftClick || isHotbarSwap || isDrop || isSwapOffhand || isCreative || isMiddle)
            e.setCancelled(true);
    }

    @EventHandler
    public void onOffhandSwap(PlayerSwapHandItemsEvent e) {
        if (!gameStarted || !hunters.contains(e.getPlayer().getUniqueId())) return;
        if (isManhuntItem(e.getMainHandItem()) || isManhuntItem(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler
    public void onArmorDrop(PlayerDropItemEvent e) {
        if (!gameStarted || !hunters.contains(e.getPlayer().getUniqueId())) return;
        if (e.getItemDrop() != null && isManhuntItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID pUUID = e.getPlayer().getUniqueId();
        if (timerBar != null) timerBar.removePlayer(e.getPlayer());
        if (gameStarted && runnerUUID != null && runnerUUID.equals(pUUID)) {
            Bukkit.broadcast(Component.text("Runner oyundan ayrıldığı için Manhunt iptal edildi!", NamedTextColor.RED, TextDecoration.BOLD));
            stopGame();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if (!gameStarted) return;
        if (hunters.contains(player.getUniqueId())) {
            timerBar.addPlayer(player);
            giveArmor(player, individualTiers.getOrDefault(player.getUniqueId(), 0));
            giveCompass(player);
        } else if (runnerUUID != null && player.getUniqueId().equals(runnerUUID)) {
            timerBar.addPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (!gameStarted || runnerUUID == null) return;
        if (!e.getPlayer().getUniqueId().equals(runnerUUID)) return;
        portalLocationsByWorld
                .computeIfAbsent(runnerUUID, k -> new HashMap<>())
                .put(e.getFrom().getWorld().getName(), e.getFrom().clone());
    }

    @EventHandler
    public void onCompassInteract(PlayerInteractEvent e) {
        Player hunter = e.getPlayer();
        if (!gameStarted || !hunters.contains(hunter.getUniqueId()) || runnerUUID == null) return;
        ItemStack item = e.getItem();
        if (!isManhuntItem(item) || item.getType() != Material.COMPASS) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!(item.getItemMeta() instanceof CompassMeta meta)) return;
        Player runner = Bukkit.getPlayer(runnerUUID);
        if (runner == null) return;
        Location targetLoc = null;
        if (hunter.getWorld().equals(runner.getWorld())) {
            targetLoc = runner.getLocation();
        } else {
            Map<String, Location> portals = portalLocationsByWorld.get(runnerUUID);
            if (portals != null) targetLoc = portals.get(hunter.getWorld().getName());
        }
        if (targetLoc != null) {
            meta.setLodestoneTracked(false);
            meta.setLodestone(targetLoc);
            item.setItemMeta(meta);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!gameStarted) return;
        Player victim = e.getEntity();
        UUID vUUID = victim.getUniqueId();
        if (runnerUUID != null && vUUID.equals(runnerUUID)) {
            broadcastWin(false);
            stopGame();
            return;
        }
        if (hunters.contains(vUUID)) {
            clearManhuntItemsFromInventory(victim);
            try {
                e.getDrops().removeIf(this::isManhuntItem);
            } catch (UnsupportedOperationException ex) {
                getLogger().warning("Death drop listesi düzenlenemedi: " + ex.getMessage());
            }
            int pTier = individualTiers.getOrDefault(vUUID, 0);
            if (pTier == MAX_TIER) {
                individualTiers.put(vUUID, MAX_TIER - 1);
                if (gameTask == null) {
                    timeRemaining = TARGET_DURATION;
                    updateTimerBarVisibility();
                    timerBar.setVisible(true);
                    runEvolutionClock();
                }
            }
        }
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent e) {
        if (!gameStarted) return;
        if (e.getEntityType() != EntityType.ENDER_DRAGON) return;
        if (e.getEntity().getWorld().getEnvironment() != World.Environment.THE_END) return;
        broadcastWin(true);
        stopGame();
    }

    @EventHandler
    public void onHunterRespawn(PlayerRespawnEvent e) {
        Player hunter = e.getPlayer();
        if (!gameStarted || !hunters.contains(hunter.getUniqueId())) return;
        removeManhuntArmor(hunter);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (hunter.isOnline() && gameStarted) {
                    giveArmor(hunter, individualTiers.getOrDefault(hunter.getUniqueId(), 0));
                    giveCompass(hunter);
                }
            }
        }.runTaskLater(this, 10L);
    }
}
