package xy.plugins.villagermodifications;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

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
                Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));

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
            getLogger().severe("Failed to save whitelist: " + e);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onCareerChange(VillagerCareerChangeEvent event) {
		if(!event.getEntity().getProfession().equals(Villager.Profession.NONE)) {
			getLogger().info("Resetting persistent data");
			event.getEntity().getPersistentDataContainer().remove(lastCheckedBookIndex);
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
        if (!(event.getRightClicked() instanceof Villager villager)) return;

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

        int basePrice = recipe.getIngredients().getFirst().getAmount();
        int minPrice = 1;
        int bonus = event.getRecipe().getUses();

        ConfigurationSection modifyConfig = getTradeConfig(recipe, TradeType.SELLING);

        if(modifyConfig == null) {
            modifyConfig = getTradeConfig(recipe, TradeType.BUYING);
        }

        if(result.getType() == Material.ENCHANTED_BOOK && this.limitBookMinPrices) {
            int cost = recipe.getIngredients().getFirst().getAmount();
            minPrice = Math.toIntExact(Math.max(1, Math.round(0.66 * cost)));
        }

        if(modifyConfig == null && minPrice == 1) {
            return;
        }

        if(modifyConfig != null) {
            minPrice = Math.max(minPrice, modifyConfig.getInt("item1.minCost", 1));
        }

        if(bonus < 0 && (basePrice + bonus) < minPrice) {
            event.getRecipe().setUses(-(basePrice - minPrice));
        }
    }

    private ConfigurationSection getTradeConfig(MerchantRecipe recipe, TradeType tradeType) {
        String prefix = tradeType.toString().toLowerCase() + ".";
        ItemStack target = tradeType == TradeType.SELLING ? recipe.getResult() : recipe.getIngredients().getFirst();

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

        return this.config.getConfigurationSection(prefix + target.getType());
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

            ItemStack item1 = recipe.getIngredients().getFirst();
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

        getLogger().info("Last checked book index " + lastCheckedIndex);

        int pos = -1;
        for (MerchantRecipe recipe : villager.getRecipes()) {
            boolean changed = false; //Whether any enchantments were modified
            int highestLevel = 0; //Highest level of any enchantment in the result item, used for generating price
            Enchantment highestEnchantment = null; //Highest level of any enchantment in the result item, used for generating price
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

                    if(replacementLevel > highestLevel) {
                        highestEnchantment = replacement;
                        highestLevel = replacementLevel;
                    }
                } else {
                    List<Integer> allowedLevels = getEnchantmentLevelRange(enchantment, villagerLevel);

                    //Enchantment level isn't allowed, change level
                    if(!allowedLevels.contains(level)) {
                        int replacementLevel = allowedLevels.get(random.nextInt(allowedLevels.size()));

                        getLogger().info("Replaced " + enchantment + ":" + level + " with level: " + replacementLevel);
                        meta.removeStoredEnchant(enchantment);
                        meta.addStoredEnchant(enchantment, replacementLevel, false);

                        changed = true;

                        if(replacementLevel > highestLevel) {
                            highestEnchantment = enchantment;
                            highestLevel = replacementLevel;
                        }
                    } else if(level > highestLevel) {
                        highestEnchantment = enchantment;
                        highestLevel = level;
                    }

                    disallowed.add(enchantment);
                }
            }

            if((this.limitBookMaxTrades || this.limitBookMinPrices) && meta.hasStoredEnchants()) {
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
                ItemStack firstItem = ingredients.getFirst().clone();
                ItemStack secondItem = ingredients.get(1);

                //Generate new price to reflect level/type changes
                if(firstItem.getType() == Material.EMERALD) {
                    int price = getEnchantmentPrice(highestEnchantment, highestLevel);
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
        List<Enchantment> allowedEnchantments = Registry.ENCHANTMENT.stream()
                .filter((Enchantment enchantment) ->
                                !enchantment.equals(Enchantment.SOUL_SPEED) &&
                                        !enchantment.equals(Enchantment.SWIFT_SNEAK) &&
                                        !disallowed.contains(enchantment) &&
                                        minEnchantLevels.getOrDefault(enchantment, 1) <= villagerLevel)
                .toList();

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

    public int getEnchantmentPrice(Enchantment enchantment, int level) {
        int price = switch (level) {
            default -> random.nextInt(15) + 5; //5-19
            case 2 -> random.nextInt(25) + 8; //8-32
            case 3 -> random.nextInt(35) + 11; //11-45
            case 4 -> random.nextInt(45) + 14; //14-58
            case 5 -> random.nextInt(55) + 17; //17-71
        };

        if(enchantment.isTreasure() || enchantment.getMaxLevel() == level) {
            price = random.nextInt(24) + 40; //40-64
        }

        return price;
    }

    public int getEnchantmentMaxTrades(Enchantment enchantment, int level) {
        if(enchantment.isTreasure()) {
            return 2;
        }

        if(enchantment.getMaxLevel() == 1 || level == 1) {
            return 4;
        }

        if(level == enchantment.getMaxLevel()) {
            return 2;
        }

        if(level == enchantment.getMaxLevel() - 1) {
            return 3;
        }

        return 3;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equals("vmreload")) {
            if (sender instanceof Player p) {
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
            if (sender instanceof Player p) {

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
            if (sender instanceof Player p) {

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
            if (sender instanceof Player p) {

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
