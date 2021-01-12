package xy.plugins.villagermodifications;

import com.google.common.collect.Lists;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentWrapper;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;


public final class VillagerModifications extends JavaPlugin implements Listener {
    private File whitelistFile;

    private FileConfiguration config;
    private FileConfiguration whitelistConfig;

    private List<String> whitelist;

    private String mainPath;
    private long begin;
    private long end;
    private long allVillagers;
    private int alert;
    private String BookTitle;
    private String BookLore;

    private Player whitelistPlayer;
    private Player blacklistPlayer;

    private String alertmessage;


    @Override
    public void onEnable() {
        getConfig().options().copyDefaults();
        saveDefaultConfig();
        this.loadSettings();
        System.out.println("Villager Modifiers are running");
        getServer().getPluginManager().registerEvents(this, this);
    }

    public void loadSettings() {
        this.mainPath = this.getDataFolder().getPath() + "/";

        File configFile = new File(this.mainPath, "config.yml");
        this.whitelistFile = new File(this.mainPath, "whitelist.yml");

        this.config = YamlConfiguration.loadConfiguration(configFile);
        this.whitelistConfig = YamlConfiguration.loadConfiguration(this.whitelistFile);

        if (this.whitelistFile.exists()) {
            this.whitelist = this.whitelistConfig.getStringList("whitelist");
        } else {
            this.whitelist = new ArrayList<>();
            this.whitelist.add("placeholder");
            this.whitelistConfig.addDefault("whitelist", whitelist);
            this.whitelistConfig.options().copyDefaults(true);
            this.saveWhitelist();
        }

        this.begin = this.config.getInt("Work.begin");
        this.end = this.config.getInt("Work.end");
        this.allVillagers = this.config.getLong("allVillagers");
        this.alert = this.config.getInt("alert.on");
        this.alertmessage = this.config.getString("alert.message");
        this.BookLore = this.config.getString("Book.Lore");
        this.BookTitle = this.config.getString("Book.Title");
    }

    public boolean addToWhitelist(Villager villager) {
        if (this.whitelist.contains(villager.getUniqueId().toString())) {
            return false;
        } else {
            this.whitelist.add(villager.getUniqueId().toString());
            this.saveWhitelist();

            return true;
        }
    }

    public boolean removeFromWhitelist(Villager villager) {
        if (!this.whitelist.contains(villager.getUniqueId().toString())) {
            return false;
        } else {
            this.whitelist.remove(villager.getUniqueId().toString());
            this.saveWhitelist();

            return true;
        }
    }

    public void saveWhitelist() {
        this.whitelistConfig.set("whitelist", whitelist);
        try {
            this.whitelistConfig.save(this.whitelistFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save whitelist");
            e.printStackTrace();
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        if(event.getPlayer().equals(whitelistPlayer)) {
            whitelistPlayer = null;
        }

        if(event.getPlayer().equals(blacklistPlayer)) {
            blacklistPlayer = null;
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        if(event.getPlayer().equals(whitelistPlayer)) {
            whitelistPlayer = null;
        }

        if(event.getPlayer().equals(blacklistPlayer)) {
            blacklistPlayer = null;
        }
    }

    @EventHandler
    public void interact(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        if (!(event.getRightClicked() instanceof Villager)) return;
        Villager villager = (Villager) event.getRightClicked();

        if (whitelistPlayer.equals(p)) {
            if (addToWhitelist(villager)) {
                p.sendMessage("Villager has been added to the whitelist");
            } else {
                p.sendMessage("Villager is already whitelisted");
            }
        }

        if (blacklistPlayer.equals(p)) {
            if (removeFromWhitelist(villager)) {
                p.sendMessage("Villager has been removed from the whitelist");
            } else {
                p.sendMessage("Villager was not found in the whitelist");
            }
        }

        if (whitelist.contains(villager.getUniqueId().toString())) {
            return;
        }

        if (this.allVillagers == 1) {
            if (!villager.getProfession().equals(Villager.Profession.NONE)) {
                if (villager.getWorld().getTime() >= this.end) {
                    if (alert == 1){
                        p.sendMessage(alertmessage);
                    }
                    event.setCancelled(true);
                } else {
                    if (villager.getWorld().getTime() <= this.begin) {
                        if (alert == 1){
                            p.sendMessage(alertmessage);
                        }
                        event.setCancelled(true);
                    }
                }
            }
        }

        List<String> configbooks = this.config.getStringList("enchantments");
        List<String> restricteditems = this.config.getStringList("CustomItem");
        List<MerchantRecipe> recipes = Lists.newArrayList(villager.getRecipes());

        int pos = -1;
        ItemStack book_item = new ItemStack(Material.BOOK, 1);
        ItemMeta custom_book = book_item.getItemMeta();
        custom_book.setLore(Arrays.asList(BookLore));
        custom_book.setDisplayName(BookTitle);


        Iterator<MerchantRecipe> recipeIterator;
        for (recipeIterator = recipes.iterator(); recipeIterator.hasNext(); ) {
            MerchantRecipe recipe = recipeIterator.next();
            pos = pos + 1;

            for (String item : restricteditems) {
                if (recipe.getResult().getType().equals(Material.matchMaterial(item))) {
                    int vrestricted = this.config.getInt(item + ".restricted");
                    int vchange = this.config.getInt(item + ".change");
                    String vmaterial = this.config.getString(item + ".material");
                    int vcost = this.config.getInt(item + ".cost");
                    int vuses = this.config.getInt(item + ".uses");

                    if (villager.getWorld().getTime() >= this.end && vrestricted == 1) {
                        if (alert == 1){
                            p.sendMessage(alertmessage);
                        }
                        event.setCancelled(true);
                    } else {
                        if (villager.getWorld().getTime() <= this.begin && vrestricted == 1) {
                            if (alert == 1){
                                p.sendMessage(alertmessage);
                            }
                            event.setCancelled(true);
                        }
                    }
                    if (vchange == 1) {
                        int uses = recipe.getUses();
                        float priceMultipler = recipe.getPriceMultiplier();
                        boolean xpReward = recipe.hasExperienceReward();
                        int xp = recipe.getVillagerExperience();

                        ItemStack currency = new ItemStack(Material.getMaterial(vmaterial), vcost);
                        ItemStack tradeditem = new ItemStack(recipe.getResult().getType(), recipe.getResult().getAmount());

                        MerchantRecipe changedrec = new MerchantRecipe(tradeditem, vuses);

                        changedrec.setUses(uses);
                        changedrec.setPriceMultiplier(priceMultipler);
                        changedrec.setExperienceReward(xpReward);
                        changedrec.setVillagerExperience(xp);
                        changedrec.addIngredient(currency);

                        villager.setRecipe(pos, changedrec);

                    }

                }

            }

            if (recipe.getResult().getType().equals(Material.ENCHANTED_BOOK)) {
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) recipe.getResult().getItemMeta();

                for (String book : configbooks) {
                    if (book.contains(":")) {
                        String[] book_level = book.split(":");
                        Enchantment enchantment = EnchantmentWrapper.getByKey(NamespacedKey.minecraft(book_level[0]));
                        int level = Integer.parseInt(book_level[1]);
                        if (meta.hasStoredEnchant(enchantment) && meta.getStoredEnchantLevel(enchantment) == level) {
                            book = book.replace(":", "_");
                            int vrestricted = this.config.getInt(book + ".restricted");
                            int vchange = this.config.getInt(book + ".change");
                            String vmaterial = this.config.getString(book + ".material");
                            int vcost = this.config.getInt(book + ".cost");
                            int vbook = this.config.getInt(book + ".book");
                            int vuses = this.config.getInt(book + ".uses");
                            if (villager.getWorld().getTime() >= this.end && vrestricted == 1) {
                                if (alert == 1){
                                p.sendMessage(alertmessage);
                                }
                                event.setCancelled(true);
                            } else {
                                if (villager.getWorld().getTime() <= this.begin && vrestricted == 1) {
                                    if (alert == 1){
                                        p.sendMessage(alertmessage);
                                    }
                                    event.setCancelled(true);
                                }
                            }
                            if (vchange == 1) {
                                int uses = recipe.getUses();
                                float priceMultipler = recipe.getPriceMultiplier();
                                boolean xpReward = recipe.hasExperienceReward();
                                int xp = recipe.getVillagerExperience();

                                ItemStack emerald = new ItemStack(Material.getMaterial(vmaterial), vcost);
                                ItemStack enchantedbook = new ItemStack(recipe.getResult().getType(), 1);
                                if (vbook == 1) {
                                    book_item.setItemMeta(custom_book);
                                }
                                enchantedbook.setItemMeta(meta);
                                MerchantRecipe changedrec = new MerchantRecipe(enchantedbook, vuses);

                                changedrec.setUses(uses);
                                changedrec.setPriceMultiplier(priceMultipler);
                                changedrec.setExperienceReward(xpReward);
                                changedrec.setVillagerExperience(xp);
                                changedrec.addIngredient(emerald);
                                changedrec.addIngredient(book_item);

                                villager.setRecipe(pos, changedrec);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("vmreload")) {
            if (sender instanceof Player) {
                Player p = (Player) sender;
                if (p.hasPermission("VillagerModification.reload")) {
                    p.sendMessage("Plugin has been reloaded");
                    this.loadSettings();
                } else {
                    p.sendMessage("No permission");
                }
            } else {
                System.out.println("Plugin has been reloaded");
                this.loadSettings();
            }
            return true;
        }

        if (command.getName().equals("vmbook")) {
            if (sender instanceof Player) {
                Player p = (Player) sender;
                if (p.hasPermission("VillagerModification.reload")) {
                    p.sendMessage("§aBook received.");
                    ItemStack book = new ItemStack(Material.BOOK, 1);
                    ItemMeta custom_book = book.getItemMeta();
                    custom_book.setLore(Arrays.asList(BookLore));
                    custom_book.setDisplayName(BookTitle);
                    book.setItemMeta(custom_book);
                    p.getInventory().addItem(book);
                } else {
                    p.sendMessage("No permission");
                }
            } else {
                System.out.println("Cannot give book to console");
            }
        }

        if (command.getName().equals("vmwhitelist")) {
            if (sender instanceof Player) {
                Player p = (Player) sender;

                if (p.hasPermission("VillagerModification.whitelist")) {
                    if (blacklistPlayer == null) {
                        p.sendMessage("Villager whitelist mode activated");
                        p.sendMessage("Enter /vmoff to deactivate");
                        whitelistPlayer = p;
                    } else {
                        p.sendMessage("Whitelist mode has not been activated.");
                        p.sendMessage("Please enter /vmoff before activating this.");
                    }
                } else {
                    p.sendMessage("No permission");
                }
            } else {
                System.out.println("Cannot identify UUID in console");
            }

            return true;
        }

        if (command.getName().equals("vmoff")) {
            if (sender instanceof Player) {
                Player p = (Player) sender;

                if (p.hasPermission("VillagerModification.whitelist")) {
                    if (whitelistPlayer != null) {
                        p.sendMessage("Villager whitelist mode deactivated");
                        whitelistPlayer = null;
                    }
                    if (blacklistPlayer != null) {
                        p.sendMessage("Villager whitelist removing mode deactivated");
                        blacklistPlayer = null;
                    }
                } else {
                    p.sendMessage("No permission");
                }
            } else {
                System.out.println("Cannot identify UUID in console");
            }

            return true;
        }

        if (command.getName().equals("vmremove")) {
            if (sender instanceof Player) {
                Player p = (Player) sender;

                if (p.hasPermission("VillagerModification.whitelist")) {
                    if (whitelistPlayer == null) {
                        p.sendMessage("Villager whitelist removing mode activated");
                        p.sendMessage("Enter /vmoff to deactivate");
                        blacklistPlayer = p;
                    } else {
                        p.sendMessage("Removal mode has not been activated.");
                        p.sendMessage("Please enter /vmoff before activating this.");
                    }
                } else {
                    p.sendMessage("No permission");
                }
            } else {
                System.out.println("Cannot identify UUID in console");
            }

            return true;
        }

        if (command.getName().equals("vmtime")) {
            if (sender instanceof Player) {
                Player p = (Player) sender;

                if (p.hasPermission("VillagerModification.time")) {
                    p.sendMessage("Trades begin at " + begin + " ticks and ends at " + end + " ticks.");

                } else {
                    p.sendMessage("No permission");
                }
            } else {
                System.out.println("Trades begin at " + begin + " ticks and ends at " + end + " ticks.");
            }

            return true;
        }

        return false;
    }
}
