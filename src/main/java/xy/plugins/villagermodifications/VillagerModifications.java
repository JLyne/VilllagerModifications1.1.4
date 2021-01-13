package xy.plugins.villagermodifications;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
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
    private boolean allVillagers;
    private boolean alert;

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
        this.allVillagers = this.config.getBoolean("allVillagers", false);
        this.alert = this.config.getBoolean("alert.on", false);
        this.alertmessage = this.config.getString("alert.message");
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

        if (p.equals(whitelistPlayer)) {
            if (addToWhitelist(villager)) {
                p.sendMessage("Villager has been added to the whitelist");
            } else {
                p.sendMessage("Villager is already whitelisted");
            }
        }

        if (p.equals(blacklistPlayer)) {
            if (removeFromWhitelist(villager)) {
                p.sendMessage("Villager has been removed from the whitelist");
            } else {
                p.sendMessage("Villager was not found in the whitelist");
            }
        }

        if (whitelist.contains(villager.getUniqueId().toString())) {
            return;
        }

        if (this.allVillagers) {
            if (!villager.getProfession().equals(Villager.Profession.NONE)) {
                if (isTradeRestricted(villager, true)) {
                    if (alert) {
                        p.sendMessage(alertmessage);
                    }

                    event.setCancelled(true);
                }
            }
        }

        int pos = -1;
        for (MerchantRecipe recipe : villager.getRecipes()) {
            pos++;
            ConfigurationSection modifyConfig = getTradeConfig(recipe);

            if(modifyConfig == null) {
                continue;
            }

            if (isTradeRestricted(villager, config.getBoolean("restricted", false))) {
                if (alert) {
                    p.sendMessage(alertmessage);
                }

                event.setCancelled(true);
            }

            villager.setRecipe(pos, modifyRecipe(recipe, modifyConfig));
        }
    }

    @EventHandler
    public void tradeReplenish(VillagerReplenishTradeEvent event) {
        MerchantRecipe recipe = event.getRecipe();

        int basePrice = recipe.getIngredients().get(0).getAmount();
        int minPrice;
        int bonus = event.getBonus();

        ConfigurationSection modifyConfig = getTradeConfig(recipe);

        if(modifyConfig == null) {
            return;
        }

        minPrice = modifyConfig.getInt("item1.minCost", 1);

        if(bonus < 0 && (basePrice + bonus) < minPrice) {
            event.setBonus(-(basePrice - minPrice));
        }
    }

    public ConfigurationSection getTradeConfig(MerchantRecipe recipe) {
        ItemStack target = recipe.getResult();

        if (target.getType().equals(Material.ENCHANTED_BOOK)) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) target.getItemMeta();

            for (Enchantment enchantment : meta.getStoredEnchants().keySet()) {
                ConfigurationSection config = this.config.getConfigurationSection(
                        enchantment.getKey().getKey() + "_" + meta.getStoredEnchantLevel(enchantment));

                if (config != null) {
                    return config;
                }
            }

            return null;
        }

        return this.config.getConfigurationSection(target.getType().toString());
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

        if (change) {
            ConfigurationSection item1Config = config.getConfigurationSection("item1");
            ConfigurationSection item2Config = config.getConfigurationSection("item2");
            ConfigurationSection resultConfig = config.getConfigurationSection("result");

            ItemStack item1 = recipe.getIngredients().get(0);
            ItemStack item2 = recipe.getIngredients().get(1);
            ItemStack result = recipe.getResult();

            item1 = getTradeIngredient(item1Config, item1);
            item2 = getTradeIngredient(item2Config, item2);
            result = getTradeIngredient(resultConfig, result);

            MerchantRecipe newRecipe = new MerchantRecipe(result, config.getInt("uses", recipe.getUses()));

            newRecipe.setPriceMultiplier(recipe.getPriceMultiplier());
            newRecipe.setExperienceReward(recipe.hasExperienceReward());
            newRecipe.setVillagerExperience(recipe.getVillagerExperience());
            newRecipe.addIngredient(item1);
            newRecipe.addIngredient(item2);

            return newRecipe;
        }

        return recipe;
    }

    private ItemStack getTradeIngredient(ConfigurationSection config, ItemStack originalItem) {
        if(config != null) {
            Material material = Material.getMaterial(config.getString("material", ""));

            if(material != null) {
                originalItem = new ItemStack(material, config.getInt("cost", 1));
            } else {
                originalItem = originalItem.clone();
                originalItem.setAmount(config.getInt("cost", originalItem.getAmount()));
            }
        }
        return originalItem;
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
