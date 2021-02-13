package xy.plugins.villagermodifications;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class VillagerModifications extends JavaPlugin implements Listener {
    private File whitelistFile;

    private FileConfiguration config;
    private FileConfiguration whitelistConfig;

    private List<String> whitelist;

    private boolean limitBookMinPrices;
    private boolean limitBookMaxTrades;
    private Map<Enchantment, Integer> minEnchantLevels;

    private Player whitelistPlayer;
    private Player blacklistPlayer;

    private final Random random = new Random();
    private NamespacedKey lastCheckedBookIndex; //Last recipe index checked for book level changes

    @Override
    public void onEnable() {
        getConfig().options().copyDefaults();
        saveDefaultConfig();
        this.loadSettings();
        System.out.println("Villager Modifiers are running");
        getServer().getPluginManager().registerEvents(this, this);

        lastCheckedBookIndex = new NamespacedKey(this, "last-checked-book-index");
    }

    public void loadSettings() {
        String mainPath = this.getDataFolder().getPath() + "/";

        File configFile = new File(mainPath, "config.yml");
        this.whitelistFile = new File(mainPath, "whitelist.yml");

        this.config = YamlConfiguration.loadConfiguration(configFile);
        this.whitelistConfig = YamlConfiguration.loadConfiguration(this.whitelistFile);
        this.minEnchantLevels = new HashMap<>();

        ConfigurationSection minVillagerLevels = this.config.getConfigurationSection("enchantments.min-villager-levels");
        this.limitBookMinPrices = this.config.getBoolean("enchantments.limit-min-prices", false);
        this.limitBookMaxTrades = this.config.getBoolean("enchantments.limit-max-trades", false);

        if(minVillagerLevels != null) {
            minVillagerLevels.getKeys(false).forEach((String key) -> {
                Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(key));

                if(enchantment != null) {
                    this.minEnchantLevels.put(enchantment, minVillagerLevels.getInt(key, 1));
                } else {
                    getLogger().warning("Invalid enchantment " + key);
                }
            });
        }

        if (this.whitelistFile.exists()) {
            this.whitelist = this.whitelistConfig.getStringList("whitelist");
        } else {
            this.whitelist = new ArrayList<>();
            this.whitelist.add("placeholder");
            this.whitelistConfig.addDefault("whitelist", whitelist);
            this.whitelistConfig.options().copyDefaults(true);
            this.saveWhitelist();
        }
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

        checkBookTrades(villager);

        int pos = -1;
        for (MerchantRecipe recipe : villager.getRecipes()) {
            pos++;
            ConfigurationSection modifyConfig = getTradeConfig(recipe, TradeType.SELLING);

            if(modifyConfig == null) {
                modifyConfig = getTradeConfig(recipe, TradeType.BUYING);
            }

            if(modifyConfig == null) {
                continue;
            }

            villager.setRecipe(pos, modifyRecipe(recipe, modifyConfig));
        }
    }

    @EventHandler
    public void tradeReplenish(VillagerReplenishTradeEvent event) {
        MerchantRecipe recipe = event.getRecipe();
        ItemStack result = recipe.getResult();

        int basePrice = recipe.getIngredients().get(0).getAmount();
        int minPrice = 1;
        int bonus = event.getBonus();

        ConfigurationSection modifyConfig = getTradeConfig(recipe, TradeType.SELLING);

        if(modifyConfig == null) {
            modifyConfig = getTradeConfig(recipe, TradeType.BUYING);
        }

        if(result.getType() == Material.ENCHANTED_BOOK && this.limitBookMinPrices) {
            int cost = recipe.getIngredients().get(0).getAmount();
            minPrice = Math.toIntExact(Math.max(1, Math.round(0.66 * cost)));
        }

        if(modifyConfig == null && minPrice == 1) {
            return;
        }

        if(modifyConfig != null) {
            minPrice = Math.max(minPrice, modifyConfig.getInt("item1.minCost", 1));
        }

        if(bonus < 0 && (basePrice + bonus) < minPrice) {
            event.setBonus(-(basePrice - minPrice));
        }
    }

    public ConfigurationSection getTradeConfig(MerchantRecipe recipe, TradeType tradeType) {
        String prefix = tradeType.toString().toLowerCase() + ".";
        ItemStack target = tradeType == TradeType.SELLING ? recipe.getResult() : recipe.getIngredients().get(0);

        if (target.getType().equals(Material.ENCHANTED_BOOK)) {
            ConfigurationSection result = null;
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) target.getItemMeta();

            for (Enchantment enchantment : meta.getStoredEnchants().keySet()) {
                ConfigurationSection config = this.config.getConfigurationSection(
                        prefix + enchantment.getKey().getKey() + "_" + meta.getStoredEnchantLevel(enchantment));

                if (config != null) {
                    result = config;
                    break;
                }
            }

            return result;
        }

        return this.config.getConfigurationSection(prefix + target.getType().toString());
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

            newRecipe.setUses(recipe.getUses());
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

    private void checkBookTrades(Villager villager) {
        int villagerLevel = villager.getVillagerLevel();
        Set<Enchantment> disallowed = new HashSet<>(); //Tracks already present enchantments to prevent multiple offers for the same type
        int lastCheckedIndex;

        if (villager.getPersistentDataContainer().has(lastCheckedBookIndex, PersistentDataType.INTEGER)) {
            lastCheckedIndex = villager.getPersistentDataContainer().get(lastCheckedBookIndex, PersistentDataType.INTEGER);
        } else {
            lastCheckedIndex = -1;
        }

        int pos = -1;
        for (MerchantRecipe recipe : villager.getRecipes()) {
            boolean changed = false; //Whether any enchantments were modified
            int highestLevel = 1; //Highest level of any enchantment in the result item, used for generating price
            boolean isTreasure = false; //Whether any enchantments are treasure, used for generating price

            pos++;

            ItemStack result = recipe.getResult();

            if (!result.getType().equals(Material.ENCHANTED_BOOK)) {
                continue;
            }

            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) result.getItemMeta();

            if(pos <= lastCheckedIndex) {
                disallowed.addAll(meta.getStoredEnchants().keySet());
                getLogger().info("Skipping recipe " + pos + " as already checked before");
                continue;
            }

            for (Enchantment enchantment : meta.getStoredEnchants().keySet()) {
                int level = meta.getStoredEnchantLevel(enchantment);

                //Enchantment type isn't allowed, replace with another
                if(!isEnchantmentAllowed(enchantment, villagerLevel, disallowed)) {
                    meta.removeStoredEnchant(enchantment);

                    //Get allowed enchantment and level
                    Enchantment replacement = getEnchantment(villagerLevel, disallowed);
                    List<Integer> range = getEnchantmentLevelRange(replacement, villagerLevel);
                    int replacementLevel = range.get(random.nextInt(range.size()));

                    getLogger().info("Replaced " + enchantment.getKey().getKey() + ":" + level + " with: " + replacement.getKey().getKey() + ":" + replacementLevel);

                    disallowed.add(replacement);
                    meta.addStoredEnchant(replacement, replacementLevel, false);

                    changed = true;
                    isTreasure = isTreasure || replacement.isTreasure();
                    highestLevel = Math.max(highestLevel, replacementLevel);
                } else {
                    List<Integer> allowedLevels = getEnchantmentLevelRange(enchantment, villagerLevel);

                    //Enchantment level isn't allowed, change level
                    if(!allowedLevels.contains(level)) {
                        int replacementLevel = allowedLevels.get(random.nextInt(allowedLevels.size()));

                        getLogger().info("Replaced " + enchantment + ":" + level + " with level: " + replacementLevel);
                        meta.removeStoredEnchant(enchantment);
                        meta.addStoredEnchant(enchantment, replacementLevel, false);

                        changed = true;
                        highestLevel = Math.max(highestLevel, replacementLevel);
                    }

                    disallowed.add(enchantment);
                    isTreasure = isTreasure || enchantment.isTreasure();
                }
            }

            if(this.limitBookMaxTrades && meta.hasStoredEnchants()) {
                changed = true;
            }

            if(changed) {
                result.setItemMeta(meta);

                int maxUses = recipe.getMaxUses();

                if(this.limitBookMaxTrades && meta.hasStoredEnchants()) {
                    Enchantment enchantment = (Enchantment) meta.getStoredEnchants().keySet().toArray()[0];
                    int level = meta.getStoredEnchantLevel(enchantment);
                    maxUses = getEnchantmentMaxTrades(enchantment, level);
                }

                MerchantRecipe newRecipe = new MerchantRecipe(result, maxUses); //Copy recipe so we can change the result item
                List<ItemStack> ingredients = recipe.getIngredients();
                ItemStack firstItem = ingredients.get(0).clone();
                ItemStack secondItem = ingredients.get(1);

                //Generate new price to reflect level/type changes
                if(firstItem.getType() == Material.EMERALD) {
                    int price = getEnchantmentPrice(highestLevel, isTreasure);
                    getLogger().info("Changing price to: " + price);
                    firstItem.setAmount(price);
                }

                newRecipe.setUses(recipe.getUses());
                newRecipe.setPriceMultiplier(recipe.getPriceMultiplier());
                newRecipe.setExperienceReward(recipe.hasExperienceReward());
                newRecipe.setVillagerExperience(recipe.getVillagerExperience());
                newRecipe.addIngredient(firstItem);

                if(secondItem != null) {
                    newRecipe.addIngredient(secondItem);
                }

                villager.setRecipe(pos, newRecipe);
            }
        }

        villager.getPersistentDataContainer().set(lastCheckedBookIndex, PersistentDataType.INTEGER, pos);
    }

    private boolean isEnchantmentAllowed(Enchantment enchantment, int villagerLevel, Set<Enchantment> disallowed) {
        if(disallowed.contains(enchantment)) {
            return false;
        }

        return villagerLevel >= minEnchantLevels.getOrDefault(enchantment, 1);
    }

    private Enchantment getEnchantment(int villagerLevel, Set<Enchantment> disallowed) {
        List<Enchantment> allowedEnchantments = Arrays.stream(Enchantment.values())
                .filter((Enchantment enchantment) ->
                                !enchantment.equals(Enchantment.SOUL_SPEED) &&
                                        !disallowed.contains(enchantment) &&
                                        minEnchantLevels.getOrDefault(enchantment, 1) <= villagerLevel)
                .collect(Collectors.toList());

        return allowedEnchantments.get(random.nextInt(allowedEnchantments.size()));
    }

    private List <Integer> getEnchantmentLevelRange(Enchantment enchantment, int villagerLevel) {
        List <Integer> range = new ArrayList <>();
        int minVillagerLevel = config.getInt("enchantments.min-villager-levels." + enchantment.getKey().getKey(), 1);

        if(villagerLevel < minVillagerLevel) {
            return range;
        }

        if(enchantment.getMaxLevel() == 1) {
            range.add(1);
            return range;
        }

        //(number of villager levels above the min required) / (total number of villager levels allowing the enchantment)
        //Multiplied by max enchant level and rounded to get base level
        int baseLevel = Math.max(1, Math.round(enchantment.getMaxLevel() *
                                           ((villagerLevel - minVillagerLevel) / (float) (4 - minVillagerLevel))));

        range.add(baseLevel);

        //If the enchantment has less levels than required to fill all allowed villager levels
        //and current villager level is the min required for this enchantment
        //limit range to lowest level
        if(enchantment.getMaxLevel() <= (4 - minVillagerLevel) && villagerLevel == minVillagerLevel) {
            return range;
        }

        //Otherwise add levels above and below to range, if possible
        if(baseLevel > 1) {
            range.add(baseLevel - 1);
        }

        if(baseLevel < enchantment.getMaxLevel()) {
            range.add(baseLevel + 1);
        }

        return range;
    }

    public int getEnchantmentPrice(int level, boolean treasure) {
        int price;

        switch(level) {
            case 1:
            default:
                price = random.nextInt(15) + 5; //5-19
                break;
            case 2:
                price = random.nextInt(25) + 8; //8-32
                break;
            case 3:
                price = random.nextInt(35) + 11; //11-45
                break;
            case 4:
                price = random.nextInt(45) + 14; //14-58
                break;
            case 5:
                price = random.nextInt(55) + 17; //17-71
                break;

        }

        if(treasure) {
            price = price * 2;
        }

        return price;
    }

    public int getEnchantmentMaxTrades(Enchantment enchantment, int level) {
        if(enchantment.isTreasure()) {
            return 1;
        }

        if(enchantment.getMaxLevel() == 1 || level == 1) {
            return 3;
        }

        if(level == enchantment.getMaxLevel()) {
            return 1;
        }

        if(level == enchantment.getMaxLevel() - 1) {
            return 2;
        }

        return 3;
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

        return false;
    }

    private enum TradeType {
        BUYING,
        SELLING
    }
}
