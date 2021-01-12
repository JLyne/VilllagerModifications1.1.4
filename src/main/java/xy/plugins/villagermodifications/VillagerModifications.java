package xy.plugins.villagermodifications;

import com.google.common.collect.Lists;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.*;


public final class VillagerModifications extends JavaPlugin implements Listener {
    private File whitelistFile;

    private FileConfiguration config;
    private FileConfiguration whitelistConfig;

    private List<String> whitelist;

    private long begin;
    private long end;
    private long allVillagers;
    private boolean alert;

    private ItemStack customBook;

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
        String mainPath = this.getDataFolder().getPath() + "/";

        File configFile = new File(mainPath, "config.yml");
        this.whitelistFile = new File(mainPath, "whitelist.yml");

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
        this.alert = this.config.getBoolean("alert.on", false);
        this.alertmessage = this.config.getString("alert.message");

        customBook = new ItemStack(Material.BOOK, 1);
        ItemMeta customBookMeta = customBook.getItemMeta();
        customBookMeta.setLore(Collections.singletonList(this.config.getString("Book.Lore")));
        customBookMeta.setDisplayName(this.config.getString("Book.Title"));
        customBook.setItemMeta(customBookMeta);
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
                if (isTradeRestricted(villager, true)) {
                    if (alert) {
                        p.sendMessage(alertmessage);
                    }
                    event.setCancelled(true);
                }
            }
        }

        List<String> configbooks = this.config.getStringList("enchantments");
        List<String> restricteditems = this.config.getStringList("CustomItem");
        List<MerchantRecipe> recipes = Lists.newArrayList(villager.getRecipes());

        int pos = -1;

        Iterator<MerchantRecipe> recipeIterator;
        for (recipeIterator = recipes.iterator(); recipeIterator.hasNext(); ) {
            MerchantRecipe recipe = recipeIterator.next();
            pos = pos + 1;

            for (String item : restricteditems) {
                if (recipe.getResult().getType().equals(Material.matchMaterial(item))) {
                    ConfigurationSection config = this.config.getConfigurationSection("CustomItem." + item);

                    if(config == null) {
                        continue;
                    }

                    if (isTradeRestricted(villager, config.getBoolean("restricted", false))) {
                        if (alert) {
                            p.sendMessage(alertmessage);
                        }
                        event.setCancelled(true);
                    }

                    villager.setRecipe(pos, modifyRecipe(recipe, config));
                }
            }

            if (recipe.getResult().getType().equals(Material.ENCHANTED_BOOK)) {
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) recipe.getResult().getItemMeta();

                for (String book : configbooks) {
                    if (book.contains(":")) {
                        String[] book_level = book.split(":");
                        Enchantment enchantment = EnchantmentWrapper.getByKey(NamespacedKey.minecraft(book_level[0]));
                        int level = Integer.parseInt(book_level[1]);

                        if(enchantment == null) {
                            continue;
                        }

                        if (meta.hasStoredEnchant(enchantment) && meta.getStoredEnchantLevel(enchantment) == level) {
                            book = book.replace(":", "_");

                            ConfigurationSection config = this.config
                                    .getConfigurationSection("enchantments." + book);

                            if(config == null) {
                                continue;
                            }

                            if(isTradeRestricted(villager, config.getBoolean("restricted", false))) {
                                if (alert) {
                                    p.sendMessage(alertmessage);
                                }
                                event.setCancelled(true);
                            }

                            villager.setRecipe(pos, modifyRecipe(recipe, this.config
                                    .getConfigurationSection("enchantments." + book)));
                        }
                    }
                }
            }
        }
    }

    public boolean isTradeRestricted(Villager villager, boolean restrictionsEnabled) {
        if(!restrictionsEnabled) {
            return false;
        }

        long time = villager.getWorld().getTime();

        return time <= this.begin || time >= this.end;
    }

    public MerchantRecipe modifyRecipe(MerchantRecipe recipe, ConfigurationSection config) {
        if(config == null) {
            return recipe;
        }

        boolean change = config.getBoolean("change", false);
        Material newCurrency = Material.getMaterial(config.getString("material", ""));
        int cost = config.getInt("cost", 1);
        int newUses = config.getInt("uses", 1);
        boolean useCustomBook = config.getBoolean("book", false);

        if(newCurrency == null) {
            return recipe;
        }

        if (change) {
            int uses = recipe.getUses();
            float priceMultiplier = recipe.getPriceMultiplier();
            boolean xpReward = recipe.hasExperienceReward();
            int xp = recipe.getVillagerExperience();

            ItemStack currency = new ItemStack(newCurrency, cost);
            MerchantRecipe newRecipe = new MerchantRecipe(recipe.getResult(), uses);

            newRecipe.setUses(newUses);
            newRecipe.setPriceMultiplier(priceMultiplier);
            newRecipe.setExperienceReward(xpReward);
            newRecipe.setVillagerExperience(xp);
            newRecipe.addIngredient(currency);

            if(useCustomBook) {
                newRecipe.addIngredient(customBook.clone());
            } else {
                newRecipe.addIngredient(recipe.getIngredients().get(1));
            }

            return newRecipe;
        }

        return recipe;
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
                    p.getInventory().addItem(customBook.clone());
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
