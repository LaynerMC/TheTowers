package com.thetowers.config;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.thetowers.arena.Arena;
import com.thetowers.Main;
import com.thetowers.compat.TTMaterial;
import com.thetowers.game.GeneratorType;
import com.thetowers.lang.Menus;
import com.thetowers.lang.Messages;
import com.thetowers.util.MenuItems;

/**
 * Graphical configuration menu of the arenas: list, arena edition, team
 * settings, generators and deletion confirmation.
 *
 * <p>Inventories marked by a {@link ConfigHolder} (never recognized by their
 * title), icons built via {@link TTMaterial} (the 1.12 data values resolve
 * natively and ViaVersion translates the items for clients of other
 * versions), no call to any InventoryView method. Positions and zones are not
 * set in the menu but in "placement mode" (see {@link Placements}).
 */
public class ConfigMenu {

    // ---- Sizes ----
    private static final int LIST_SIZE = 54;
    private static final int EDIT_SIZE = 54;
    private static final int TEAM_SIZE = 27;
    private static final int GEN_SIZE = 54;
    private static final int DELETE_SIZE = 27;

    // ---- Slots of the list ----
    private static final int L_CREATE = 53;

    // ---- Slots of the edit menu ----
    private static final int E_BACK = 0;
    private static final int E_INFO = 4;
    private static final int E_TEAMCOUNT = 19;
    private static final int E_PERTEAM = 20;
    private static final int E_MIN = 21;
    private static final int E_MAX = 22;
    private static final int E_POINTS = 23;
    private static final int E_DURATION = 24;
    private static final int E_BOWS = 25;
    private static final int E_SPECTATORS = 34;
    private static final int E_LOBBY = 29;
    private static final int E_GENERATORS = 31;
    private static final int E_REGEN = 33;
    private static final int[] E_TEAMS = { 37, 39, 41, 43 };
    private static final int E_DELETE = 45;
    private static final int E_SAVE = 49;

    // ---- Slots of the team menu ----
    private static final int T_BACK = 0;
    private static final int T_INFO = 4;
    private static final int T_SPAWN = 10;
    private static final int T_POOL = 12;
    private static final int T_SPAWN_ZONE = 14;
    private static final int T_CHEST_ZONE = 16;

    // ---- Slots of the generators menu ----
    private static final int G_BACK = 0;
    private static final int G_INFO = 4;
    private static final int G_CLEAR_ALL = 8;
    private static final int G_FIRST = 18;

    // ---- Slots of the delete menu ----
    private static final int D_INFO = 4;
    private static final int D_CONFIRM = 11;
    private static final int D_CANCEL = 15;

    private final Main main;

    public ConfigMenu(Main main) {
        this.main = main;
    }

    // ================= Opening the menus =================

    /** List of all the arenas. */
    public void openList(Player player) {
        ConfigHolder holder = new ConfigHolder(ConfigHolder.Type.LIST, null, null);
        Inventory inv = Bukkit.createInventory(holder, LIST_SIZE, Menus.tr("config-menu.title.list"));
        holder.setInventory(inv);
        fill(inv);

        List<Arena> arenas = arenas();
        for (int i = 0; i < arenas.size() && i < 45; i++) {
            Arena arena = arenas.get(i);
            boolean ready = arena.isConfigured();
            String name = (ready ? ChatColor.GREEN : ChatColor.RED) + arena.getName();
            List<String> lore = new ArrayList<String>();
            lore.add(Menus.tr("config-menu.list.state", "state", arena.getState().name()));
            lore.add(Menus.tr("config-menu.list.players",
                    "current", arena.getPlayerCount(), "max", arena.getMaxPlayers()));
            lore.add(ready ? Menus.tr("config-menu.list.ready")
                    : Menus.tr("config-menu.list.incomplete", "problem", arena.validate()));
            lore.add(Menus.tr("config-menu.list.click_edit"));
            inv.setItem(i, icon(TTMaterial.BEACON, name, lore));
        }

        inv.setItem(L_CREATE, icon(TTMaterial.PAPER, Menus.tr("config-menu.list.create_name"),
                one(Menus.tr("config-menu.list.create_lore"))));

        player.openInventory(inv);
    }

    /** Edition of an arena. */
    public void openEdit(Player player, String arenaName) {
        Arena arena = main.getArenaManager().getArena(arenaName);
        if (arena == null) {
            openList(player);
            return;
        }
        ConfigHolder holder = new ConfigHolder(ConfigHolder.Type.EDIT, arena.getName(), null);
        Inventory inv = Bukkit.createInventory(holder, EDIT_SIZE, Menus.tr("config-menu.title.edit"));
        holder.setInventory(inv);
        fill(inv);

        inv.setItem(E_BACK, icon(TTMaterial.ARROW, Menus.tr("config-menu.edit.back"), null));

        String problem = arena.validate();
        inv.setItem(E_INFO, icon(TTMaterial.BEACON,
                Menus.tr("config-menu.edit.info_name", "arena", arena.getName()),
                one(problem == null ? Menus.tr("config-menu.edit.info_ready")
                        : Menus.tr("config-menu.edit.info_incomplete", "problem", problem))));

        List<String> plusMinus = one(Menus.tr("config-menu.edit.plus_minus"));
        inv.setItem(E_TEAMCOUNT, icon(TTMaterial.PAPER,
                Menus.tr("config-menu.edit.teamcount_name", "value", arena.getTeamCount()), plusMinus));
        inv.setItem(E_PERTEAM, icon(TTMaterial.IRON_SWORD,
                Menus.tr("config-menu.edit.perteam_name", "value", arena.getPlayersPerTeam()), plusMinus));
        inv.setItem(E_MIN, icon(TTMaterial.LEATHER_BOOTS,
                Menus.tr("config-menu.edit.min_name", "value", arena.getMinPlayers()), plusMinus));
        inv.setItem(E_MAX, icon(TTMaterial.CHAINMAIL_CHESTPLATE,
                Menus.tr("config-menu.edit.max_name", "value", arena.getMaxPlayers()), plusMinus));
        inv.setItem(E_POINTS, icon(TTMaterial.GOLD_INGOT,
                Menus.tr("config-menu.edit.points_name", "value", arena.getPointsToWin()), plusMinus));
        inv.setItem(E_DURATION, icon(TTMaterial.CLOCK,
                Menus.tr("config-menu.edit.duration_name", "value", arena.getDuration() / 60),
                one(Menus.tr("config-menu.edit.duration_lore"))));
        inv.setItem(E_BOWS, icon(TTMaterial.BOW,
                Menus.tr("config-menu.edit.bows_name", "state", state(arena.isAllowBows())),
                one(Menus.tr("config-menu.edit.toggle_click"))));

        List<String> spectatorLore = new ArrayList<String>();
        spectatorLore.add(Menus.tr("config-menu.edit.spectators_lore"));
        spectatorLore.add(Menus.tr("config-menu.edit.toggle_click"));
        inv.setItem(E_SPECTATORS, icon(TTMaterial.COMPASS,
                Menus.tr("config-menu.edit.spectators_name", "state", state(arena.isAllowSpectators())),
                spectatorLore));

        boolean lobbySet = arena.getLobby() != null;
        List<String> lobbyLore = new ArrayList<String>();
        lobbyLore.add(lobbySet ? Menus.tr("config-menu.edit.set") : Menus.tr("config-menu.edit.unset"));
        lobbyLore.add(Menus.tr("config-menu.edit.place_click"));
        inv.setItem(E_LOBBY, icon(lobbySet ? TTMaterial.GREEN_STAINED_GLASS_PANE
                : TTMaterial.RED_STAINED_GLASS_PANE, Menus.tr("config-menu.edit.lobby_name"), lobbyLore));

        inv.setItem(E_GENERATORS, icon(TTMaterial.HOPPER,
                Menus.tr("config-menu.edit.generators_name", "count", arena.getGenerators().size()),
                one(Menus.tr("config-menu.edit.open_click"))));

        List<String> regenLore = new ArrayList<String>();
        regenLore.add(Menus.tr("config-menu.edit.regen_lore"));
        regenLore.add(Menus.tr("config-menu.edit.toggle_click"));
        inv.setItem(E_REGEN, icon(TTMaterial.TNT, Menus.tr("config-menu.edit.regen_name",
                "state", state(main.isMapRegenerationEnabled())), regenLore));

        for (int i = 0; i < E_TEAMS.length; i++) {
            String team = Arena.TEAM_NAMES[i];
            if (arena.isTeamActive(team)) {
                List<String> lore = new ArrayList<String>();
                lore.add(status(arena.getSpawns().containsKey(team), "config-menu.team.spawn_short"));
                lore.add(status(arena.getPools().containsKey(team), "config-menu.team.pool_short"));
                lore.add(status(arena.getSpawnZones().containsKey(team), "config-menu.team.spawn_zone_short"));
                lore.add(status(arena.getChestZones().containsKey(team), "config-menu.team.chest_zone_short"));
                lore.add(Menus.tr("config-menu.edit.team_click"));
                inv.setItem(E_TEAMS[i], icon(Arena.woolOf(team),
                        Arena.colorOf(team) + Menus.tr("config-menu.edit.team_name", "team", Arena.displayName(team)), lore));
            } else {
                inv.setItem(E_TEAMS[i], icon(TTMaterial.GRAY_STAINED_GLASS_PANE,
                        ChatColor.DARK_GRAY + Arena.displayName(team), one(Menus.tr("config-menu.edit.team_inactive"))));
            }
        }

        inv.setItem(E_DELETE, icon(TTMaterial.BARRIER, Menus.tr("config-menu.edit.delete_name"), null));
        inv.setItem(E_SAVE, icon(TTMaterial.NETHER_STAR, Menus.tr("config-menu.edit.save_name"), null));

        player.openInventory(inv);
    }

    /** Team settings: spawn, pool, spawn zone, chest zone. */
    public void openTeam(Player player, String arenaName, String team) {
        Arena arena = main.getArenaManager().getArena(arenaName);
        if (arena == null) {
            openList(player);
            return;
        }
        ConfigHolder holder = new ConfigHolder(ConfigHolder.Type.TEAM, arena.getName(), team);
        Inventory inv = Bukkit.createInventory(holder, TEAM_SIZE, Menus.tr("config-menu.title.team"));
        holder.setInventory(inv);
        fill(inv);

        inv.setItem(T_BACK, icon(TTMaterial.ARROW, Menus.tr("config-menu.team.back"), null));
        inv.setItem(T_INFO, icon(Arena.woolOf(team),
                Arena.colorOf(team) + Menus.tr("config-menu.edit.team_name", "team", Arena.displayName(team)), null));

        List<String> spawnLore = new ArrayList<String>();
        spawnLore.add(status(arena.getSpawns().containsKey(team), "config-menu.team.spawn_short"));
        spawnLore.add(Menus.tr("config-menu.edit.place_click"));
        inv.setItem(T_SPAWN, icon(TTMaterial.NETHER_STAR, Menus.tr("config-menu.team.spawn_name"), spawnLore));

        inv.setItem(T_POOL, icon(TTMaterial.WATER_BUCKET, Menus.tr("config-menu.team.pool_name"),
                zoneLore(arena.getPools().containsKey(team), "config-menu.team.pool_short",
                        "config-menu.team.pool_hint", true)));
        inv.setItem(T_SPAWN_ZONE, icon(TTMaterial.IRON_BARS, Menus.tr("config-menu.team.spawn_zone_name"),
                zoneLore(arena.getSpawnZones().containsKey(team), "config-menu.team.spawn_zone_short",
                        "config-menu.team.spawn_zone_hint", false)));
        inv.setItem(T_CHEST_ZONE, icon(TTMaterial.CHEST, Menus.tr("config-menu.team.chest_zone_name"),
                zoneLore(arena.getChestZones().containsKey(team), "config-menu.team.chest_zone_short",
                        "config-menu.team.chest_zone_hint", false)));

        player.openInventory(inv);
    }

    /** Generators: one button per config.yml type. */
    public void openGenerators(Player player, String arenaName) {
        Arena arena = main.getArenaManager().getArena(arenaName);
        if (arena == null) {
            openList(player);
            return;
        }
        ConfigHolder holder = new ConfigHolder(ConfigHolder.Type.GENERATORS, arena.getName(), null);
        Inventory inv = Bukkit.createInventory(holder, GEN_SIZE, Menus.tr("config-menu.title.generators"));
        holder.setInventory(inv);
        fill(inv);

        inv.setItem(G_BACK, icon(TTMaterial.ARROW, Menus.tr("config-menu.edit.back_arena"), null));
        inv.setItem(G_INFO, icon(TTMaterial.HOPPER,
                Menus.tr("config-menu.edit.generators_name", "count", arena.getGenerators().size()),
                one(Menus.tr("config-menu.generators.info"))));
        inv.setItem(G_CLEAR_ALL, icon(TTMaterial.BARRIER, Menus.tr("config-menu.generators.clear_all"), null));

        List<GeneratorType> types = generatorTypes();
        for (int i = 0; i < types.size() && G_FIRST + i < GEN_SIZE; i++) {
            GeneratorType type = types.get(i);
            List<String> lore = new ArrayList<String>();
            lore.add(Menus.tr("config-menu.generators.count", "count", arena.countGenerators(type.getKey())));
            lore.add(Menus.tr("config-menu.generators.interval",
                    "seconds", String.format(java.util.Locale.US, "%.1f", type.getIntervalTicks() / 20d)));
            lore.add(Menus.tr("config-menu.generators.add"));
            lore.add(Menus.tr("config-menu.generators.clear"));
            inv.setItem(G_FIRST + i, icon(type.createItem(),
                    Menus.tr("config-menu.generators.type_name", "type", type.getKey()), lore));
        }

        player.openInventory(inv);
    }

    /** Deletion confirmation. */
    public void openDelete(Player player, String arenaName) {
        Arena arena = main.getArenaManager().getArena(arenaName);
        if (arena == null) {
            openList(player);
            return;
        }
        ConfigHolder holder = new ConfigHolder(ConfigHolder.Type.DELETE, arena.getName(), null);
        Inventory inv = Bukkit.createInventory(holder, DELETE_SIZE, Menus.tr("config-menu.title.delete"));
        holder.setInventory(inv);
        fill(inv);

        inv.setItem(D_INFO, icon(TTMaterial.BARRIER,
                Menus.tr("config-menu.delete.info_name", "arena", arena.getName()),
                one(Menus.tr("config-menu.delete.info_lore"))));
        inv.setItem(D_CONFIRM, icon(TTMaterial.GREEN_STAINED_GLASS_PANE,
                Menus.tr("config-menu.delete.confirm"), null));
        inv.setItem(D_CANCEL, icon(TTMaterial.RED_STAINED_GLASS_PANE,
                Menus.tr("config-menu.delete.cancel"), null));

        player.openInventory(inv);
    }

    // ================= Clicks =================

    /**
     * Routes a click on a config menu. {@code rightClick} tells the right
     * click apart (decrement a number, clear a zone or generators).
     */
    public void handleClick(Player player, ConfigHolder holder, int slot, boolean rightClick) {
        switch (holder.getType()) {
            case LIST:
                clickList(player, slot);
                break;
            case EDIT:
                clickEdit(player, holder.getArena(), slot, rightClick);
                break;
            case TEAM:
                clickTeam(player, holder.getArena(), holder.getTeam(), slot, rightClick);
                break;
            case GENERATORS:
                clickGenerators(player, holder.getArena(), slot, rightClick);
                break;
            case DELETE:
                clickDelete(player, holder.getArena(), slot);
                break;
            default:
                break;
        }
    }

    private void clickList(Player player, int slot) {
        if (slot == L_CREATE) {
            main.getArenaNaming().start(player);
            return;
        }
        List<Arena> arenas = arenas();
        if (slot >= 0 && slot < arenas.size() && slot < 45) {
            openEdit(player, arenas.get(slot).getName());
        }
    }

    private void clickEdit(Player player, String arenaName, int slot, boolean rightClick) {
        Arena arena = main.getArenaManager().getArena(arenaName);
        if (arena == null) {
            openList(player);
            return;
        }
        int delta = rightClick ? -1 : 1;
        switch (slot) {
            case E_BACK:
                openList(player);
                return;
            case E_TEAMCOUNT:
                arena.setTeamCount(clamp(arena.getTeamCount() + delta, 2, 4));
                normalize(arena);
                break;
            case E_PERTEAM:
                arena.setPlayersPerTeam(clamp(arena.getPlayersPerTeam() + delta, 1, 16));
                normalize(arena);
                break;
            case E_MIN:
                arena.setMinPlayers(clamp(arena.getMinPlayers() + delta, 2, arena.getMaxPlayers()));
                break;
            case E_MAX:
                arena.setMaxPlayers(clamp(arena.getMaxPlayers() + delta, 2,
                        arena.getTeamCount() * arena.getPlayersPerTeam()));
                normalize(arena);
                break;
            case E_POINTS:
                arena.setPointsToWin(clamp(arena.getPointsToWin() + delta, 1, 100));
                break;
            case E_DURATION:
                // Set in 5-minute steps (stored in seconds), bounded 5 to 120.
                arena.setDuration(clamp(arena.getDuration() / 60 + delta * 5, 5, 120) * 60);
                break;
            case E_BOWS:
                arena.setAllowBows(!arena.isAllowBows());
                break;
            case E_SPECTATORS:
                arena.setAllowSpectators(!arena.isAllowSpectators());
                break;
            case E_LOBBY:
                main.getPlacements().start(player, new PlacementSession(arenaName,
                        PlacementSession.Kind.LOBBY, null, null));
                return;
            case E_GENERATORS:
                openGenerators(player, arenaName);
                return;
            case E_REGEN:
                main.getConfig().set("map-regeneration", !main.isMapRegenerationEnabled());
                main.saveConfig();
                break;
            case E_DELETE:
                openDelete(player, arenaName);
                return;
            case E_SAVE:
                save(player, arena);
                openEdit(player, arenaName);
                return;
            default:
                int teamIndex = indexOf(E_TEAMS, slot);
                if (teamIndex >= 0) {
                    String team = Arena.TEAM_NAMES[teamIndex];
                    if (arena.isTeamActive(team)) {
                        openTeam(player, arenaName, team);
                    }
                }
                return;
        }
        main.getArenaManager().save();
        openEdit(player, arenaName);
    }

    private void clickTeam(Player player, String arenaName, String team, int slot, boolean rightClick) {
        Arena arena = main.getArenaManager().getArena(arenaName);
        if (arena == null) {
            openList(player);
            return;
        }
        PlacementSession.Kind kind;
        switch (slot) {
            case T_BACK:
                openEdit(player, arenaName);
                return;
            case T_SPAWN:
                kind = PlacementSession.Kind.SPAWN;
                break;
            case T_POOL:
                kind = PlacementSession.Kind.POOL;
                break;
            case T_SPAWN_ZONE:
                kind = PlacementSession.Kind.SPAWN_ZONE;
                break;
            case T_CHEST_ZONE:
                kind = PlacementSession.Kind.CHEST_ZONE;
                break;
            default:
                return;
        }
        // Right click on an optional zone: it is cleared. The pool is
        // mandatory: it is not cleared by a click mistake.
        if (rightClick && (kind == PlacementSession.Kind.SPAWN_ZONE || kind == PlacementSession.Kind.CHEST_ZONE)) {
            if (kind == PlacementSession.Kind.SPAWN_ZONE) {
                arena.setSpawnZone(team, null);
            } else {
                arena.setChestZone(team, null);
            }
            main.getArenaManager().save();
            player.sendMessage(Main.PREFIX + Messages.tr("config.team.zone_cleared"));
            openTeam(player, arenaName, team);
            return;
        }
        main.getPlacements().start(player, new PlacementSession(arenaName, kind, team, null));
    }

    private void clickGenerators(Player player, String arenaName, int slot, boolean rightClick) {
        Arena arena = main.getArenaManager().getArena(arenaName);
        if (arena == null) {
            openList(player);
            return;
        }
        if (slot == G_BACK) {
            openEdit(player, arenaName);
            return;
        }
        if (slot == G_CLEAR_ALL) {
            int removed = arena.clearGenerators(null);
            main.getArenaManager().save();
            player.sendMessage(Main.PREFIX + Messages.tr("command.generators_cleared", "count", removed));
            openGenerators(player, arenaName);
            return;
        }
        List<GeneratorType> types = generatorTypes();
        int index = slot - G_FIRST;
        if (index < 0 || index >= types.size()) {
            return;
        }
        GeneratorType type = types.get(index);
        if (rightClick) {
            int removed = arena.clearGenerators(type.getKey());
            main.getArenaManager().save();
            player.sendMessage(Main.PREFIX + Messages.tr("command.generators_cleared", "count", removed));
            openGenerators(player, arenaName);
            return;
        }
        main.getPlacements().start(player, new PlacementSession(arenaName,
                PlacementSession.Kind.GENERATOR, null, type.getKey()));
    }

    private void clickDelete(Player player, String arenaName, int slot) {
        if (slot == D_CONFIRM) {
            main.getArenaManager().deleteArena(arenaName);
            player.sendMessage(Main.PREFIX + Messages.tr("command.arena_deleted", "name", arenaName));
            openList(player);
        } else if (slot == D_CANCEL) {
            openEdit(player, arenaName);
        }
    }

    private void save(Player player, Arena arena) {
        String problem = arena.validate();
        if (problem != null) {
            player.sendMessage(Main.PREFIX + Messages.tr("command.arena_incomplete", "problem", problem));
        } else {
            main.getArenaManager().save();
            player.sendMessage(Main.PREFIX + Messages.tr("command.arena_saved", "arena", arena.getName()));
        }
    }

    // ================= Utilities =================

    private List<Arena> arenas() {
        return new ArrayList<Arena>(main.getArenaManager().getArenas());
    }

    private List<GeneratorType> generatorTypes() {
        return new ArrayList<GeneratorType>(main.getGeneratorTypes().values());
    }

    /** Brings min/max back into coherent bounds after a format change. */
    private void normalize(Arena arena) {
        int capacity = arena.getTeamCount() * arena.getPlayersPerTeam();
        if (arena.getMaxPlayers() > capacity) {
            arena.setMaxPlayers(capacity);
        }
        if (arena.getMaxPlayers() < 2) {
            arena.setMaxPlayers(2);
        }
        if (arena.getMinPlayers() > arena.getMaxPlayers()) {
            arena.setMinPlayers(arena.getMaxPlayers());
        }
        if (arena.getMinPlayers() < 2) {
            arena.setMinPlayers(2);
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** The "check Pool" / "cross Pool" line, depending on whether the element is set. */
    private String status(boolean defined, String labelKey) {
        return Menus.tr(defined ? "config-menu.status.ok" : "config-menu.status.missing",
                "what", Menus.tr(labelKey));
    }

    private List<String> zoneLore(boolean defined, String labelKey, String hintKey, boolean mandatory) {
        List<String> lore = new ArrayList<String>();
        lore.add(status(defined, labelKey));
        lore.add(Menus.tr(hintKey));
        lore.add(Menus.tr("config-menu.team.zone_click"));
        if (!mandatory) {
            lore.add(Menus.tr("config-menu.team.zone_clear_click"));
        }
        return lore;
    }

    private String state(boolean on) {
        return on ? Menus.tr("config-menu.state.on") : Menus.tr("config-menu.state.off");
    }

    private List<String> one(String line) {
        List<String> list = new ArrayList<String>();
        list.add(line);
        return list;
    }

    /** Fills the whole inventory with gray panes (the empty slots stay gray). */
    private void fill(Inventory inv) {
        ItemStack pane = TTMaterial.GRAY_STAINED_GLASS_PANE.item();
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            // A space as name: the slot looks empty but stays decorative.
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        for (int slot = 0; slot < inv.getSize(); slot++) {
            inv.setItem(slot, pane.clone());
        }
    }

    private ItemStack icon(TTMaterial material, String name, List<String> lore) {
        return icon(material.item(), name, lore);
    }

    private ItemStack icon(ItemStack item, String name, List<String> lore) {
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            MenuItems.hideVanillaInfo(meta);
            item.setItemMeta(meta);
        }
        return item;
    }
}
