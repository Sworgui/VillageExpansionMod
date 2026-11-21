package com.guides.villageexpansion.ai;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Recursive, recipe-aware acquisition planner for every item requested by a
 * house blueprint. It evaluates alternative recipes and ingredient variants,
 * and only considers acquisition sources the builder controller can execute
 * without hostile-mob drops or arbitrary structure demolition.
 *
 * The recursive estimator is bounded and is invoked through the controller's
 * lazy, resumable planning scheduler. Recipe indexes are also built incrementally
 * so a large modpack never requires a full registry scan in one server tick.
 */
final class MaterialAcquisitionPlanner {
    private static final int MAX_DEPTH = 12;
    // A planner decision must never consume an unbounded part of a server tick.
    // Modpacks can register hundreds of alternatives for common item tags.
    private static final int MAX_PLANNER_STEPS = 24;
    private static final int MAX_SOURCE_SCANS = 1;
    private static final int MAX_INGREDIENT_CANDIDATES = 12;
    private static final int MAX_RECIPES_PER_OUTPUT = 12;
    private static final ThreadLocal<PlanningBudget> ACTIVE_BUDGET = new ThreadLocal<>();
    private static final double IMPOSSIBLE = 1.0e12D;
    private static long sourceCacheWindow = Long.MIN_VALUE;
    private static final Map<String, Boolean> SOURCE_AVAILABILITY_CACHE = new HashMap<>();
    private static RecipeManager indexedRecipeManager;
    private static RecipeIndexBuild pendingRecipeIndex;
    private static Map<Item, List<RecipeEntry<CraftingRecipe>>> CRAFTING_BY_OUTPUT = Map.of();
    private static Map<Item, List<RecipeEntry<SmeltingRecipe>>> SMELTING_BY_OUTPUT = Map.of();
    private static Map<Item, List<RecipeEntry<StonecuttingRecipe>>> STONECUTTING_BY_OUTPUT = Map.of();

    enum SourceKind {
        TREE,
        STRIP_LOG,
        MINE,
        SHEEP,
        CLAY_DEPOSIT,
        FLOWER,
        CROP,
        NATURAL_BLOCK,
        NONE
    }

    record CraftingChoice(RecipeEntry<CraftingRecipe> entry, Map<Item, Integer> ingredients, double score) { }
    record SmeltingChoice(RecipeEntry<SmeltingRecipe> entry, Item input, double score) { }
    record StonecuttingChoice(RecipeEntry<StonecuttingRecipe> entry, Item input, double score) { }

    private static final class PlanningBudget {
        private int steps;
        private int sourceScans;
        private int professionalScans;
        private boolean exhaustionLogged;
    }

    private static <T> T withPlanningBudget(Supplier<T> action) {
        boolean ownsBudget = ACTIVE_BUDGET.get() == null;
        if (ownsBudget) ACTIVE_BUDGET.set(new PlanningBudget());
        try {
            return action.get();
        } finally {
            if (ownsBudget) ACTIVE_BUDGET.remove();
        }
    }

    private static boolean spendPlanningStep() {
        PlanningBudget budget = ACTIVE_BUDGET.get();
        if (budget == null) return true;
        if (++budget.steps <= MAX_PLANNER_STEPS) return true;
        if (!budget.exhaustionLogged) {
            budget.exhaustionLogged = true;
            com.guides.villageexpansion.VillageExpansionMod.LOGGER.warn(
                    "Material planning reached its {}-step safety budget; using a bounded fallback for this decision",
                    MAX_PLANNER_STEPS);
        }
        return false;
    }

    private static boolean spendSourceScan() {
        PlanningBudget budget = ACTIVE_BUDGET.get();
        return budget == null || ++budget.sourceScans <= MAX_SOURCE_SCANS;
    }

    static boolean spendProfessionalProviderScan() {
        PlanningBudget budget = ACTIVE_BUDGET.get();
        return budget == null || ++budget.professionalScans <= 1;
    }

    private static <T> List<T> limited(List<T> source, int limit) {
        if (source == null || source.isEmpty()) return List.of();
        return source.size() <= limit ? source : source.subList(0, limit);
    }

    private static double boundedFallbackCost(ServerWorld world, VillagerEntity villager, Item item, int count) {
        if (world == null || villager == null || item == null || item == Items.AIR || count <= 0) return IMPOSSIBLE;
        int held = countInventory(villager, item);
        int missing = Math.max(0, count - held);
        if (missing == 0) return 0.0D;
        if (sourceFor(item) != SourceKind.NONE) return 24.0D * missing;
        // Do not recursively inspect recipes after the budget is exhausted.
        // A registered recipe is enough to keep the goal eligible for a later,
        // smaller execution-time query.
        ensureRecipeIndex(world);
        if (!CRAFTING_BY_OUTPUT.getOrDefault(item, List.of()).isEmpty()
                || !STONECUTTING_BY_OUTPUT.getOrDefault(item, List.of()).isEmpty()
                || !SMELTING_BY_OUTPUT.getOrDefault(item, List.of()).isEmpty()) {
            return 40.0D * missing;
        }
        return IMPOSSIBLE;
    }

    private MaterialAcquisitionPlanner() { }

    /**
     * Recipe indexing is incremental. Modpacks can contribute thousands of
     * recipes; indexing every output in one server tick caused long stalls
     * when the first builder requested a material plan.
     */
    static boolean isRecipeIndexReady(ServerWorld world) {
        if (world == null || world.getServer() == null) return false;
        RecipeManager manager = world.getServer().getRecipeManager();
        if (manager == indexedRecipeManager && pendingRecipeIndex == null) return true;
        beginRecipeIndexIfNeeded(manager);
        return false;
    }

    static boolean advanceRecipeIndex(ServerWorld world, int maxEntries) {
        if (world == null || world.getServer() == null) return false;
        RecipeManager manager = world.getServer().getRecipeManager();
        if (manager == indexedRecipeManager && pendingRecipeIndex == null) return true;
        beginRecipeIndexIfNeeded(manager);
        RecipeIndexBuild build = pendingRecipeIndex;
        if (build == null) return manager == indexedRecipeManager;

        // RecipeManager.keys() exposes a stream over the manager's key set. Keep
        // its iterator between ticks instead of calling listAllOfType(), which
        // materializes and filters a full list synchronously for each recipe type.
        int budget = Math.max(1, maxEntries);
        while (budget > 0 && build.recipeIds.hasNext()) {
            Identifier id = build.recipeIds.next();
            RecipeEntry<?> rawEntry = manager.get(id).orElse(null);
            if (rawEntry != null) {
                Object recipe = rawEntry.value();
                if (recipe instanceof CraftingRecipe craftingRecipe) {
                    @SuppressWarnings("unchecked")
                    RecipeEntry<CraftingRecipe> entry = (RecipeEntry<CraftingRecipe>) (RecipeEntry<?>) rawEntry;
                    ItemStack output = craftingRecipe.getResult(world.getRegistryManager());
                    if (!output.isEmpty()) build.craftingByOutput
                            .computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
                } else if (recipe instanceof SmeltingRecipe smeltingRecipe) {
                    @SuppressWarnings("unchecked")
                    RecipeEntry<SmeltingRecipe> entry = (RecipeEntry<SmeltingRecipe>) (RecipeEntry<?>) rawEntry;
                    ItemStack output = smeltingRecipe.getResult(world.getRegistryManager());
                    if (!output.isEmpty()) build.smeltingByOutput
                            .computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
                } else if (recipe instanceof StonecuttingRecipe stonecuttingRecipe) {
                    @SuppressWarnings("unchecked")
                    RecipeEntry<StonecuttingRecipe> entry = (RecipeEntry<StonecuttingRecipe>) (RecipeEntry<?>) rawEntry;
                    ItemStack output = stonecuttingRecipe.getResult(world.getRegistryManager());
                    if (!output.isEmpty()) build.stonecuttingByOutput
                            .computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
                }
            }
            budget--;
        }

        if (!build.recipeIds.hasNext()) {
            CRAFTING_BY_OUTPUT = freezeIndex(build.craftingByOutput);
            SMELTING_BY_OUTPUT = freezeIndex(build.smeltingByOutput);
            STONECUTTING_BY_OUTPUT = freezeIndex(build.stonecuttingByOutput);
            indexedRecipeManager = manager;
            build.close();
            pendingRecipeIndex = null;
            return true;
        }
        return false;
    }

    private static void beginRecipeIndexIfNeeded(RecipeManager manager) {
        if (manager == indexedRecipeManager || (pendingRecipeIndex != null && pendingRecipeIndex.manager == manager)) return;
        // A data-pack reload replaces the manager. Close the previous stream
        // and discard its partial indexes; never publish a mixed generation.
        if (pendingRecipeIndex != null) pendingRecipeIndex.close();
        CRAFTING_BY_OUTPUT = Map.of();
        SMELTING_BY_OUTPUT = Map.of();
        STONECUTTING_BY_OUTPUT = Map.of();
        pendingRecipeIndex = new RecipeIndexBuild(manager);
    }

    private static <T> Map<Item, List<T>> freezeIndex(Map<Item, List<T>> source) {
        // The builder is discarded after publication and no code retains a
        // mutable reference to it. Wrapping avoids a second O(all recipes) copy
        // on the single tick that completes a large modpack's recipe index.
        return Collections.unmodifiableMap(source);
    }

    private static final class RecipeIndexBuild {
        private final RecipeManager manager;
        private final Stream<Identifier> recipeKeyStream;
        private final Iterator<Identifier> recipeIds;
        private final Map<Item, List<RecipeEntry<CraftingRecipe>>> craftingByOutput = new HashMap<>();
        private final Map<Item, List<RecipeEntry<SmeltingRecipe>>> smeltingByOutput = new HashMap<>();
        private final Map<Item, List<RecipeEntry<StonecuttingRecipe>>> stonecuttingByOutput = new HashMap<>();

        private RecipeIndexBuild(RecipeManager manager) {
            this.manager = manager;
            this.recipeKeyStream = manager.keys();
            this.recipeIds = recipeKeyStream.iterator();
        }

        private void close() {
            recipeKeyStream.close();
        }
    }

    private static void ensureRecipeIndex(ServerWorld world) {
        if (world == null || world.getServer() == null) return;
        RecipeManager manager = world.getServer().getRecipeManager();
        if (manager != indexedRecipeManager) beginRecipeIndexIfNeeded(manager);
        // Callers that cannot wait see an empty index until the lazy scheduler
        // finishes. Execution-time choices are only requested after readiness.
    }

    private static List<RecipeEntry<CraftingRecipe>> craftingRecipesFor(ServerWorld world, Item item) {
        ensureRecipeIndex(world);
        return CRAFTING_BY_OUTPUT.getOrDefault(item, List.of());
    }

    private static List<RecipeEntry<SmeltingRecipe>> smeltingRecipesFor(ServerWorld world, Item item) {
        ensureRecipeIndex(world);
        return SMELTING_BY_OUTPUT.getOrDefault(item, List.of());
    }

    private static List<RecipeEntry<StonecuttingRecipe>> stonecuttingRecipesFor(ServerWorld world, Item item) {
        ensureRecipeIndex(world);
        return STONECUTTING_BY_OUTPUT.getOrDefault(item, List.of());
    }

    static SourceKind sourceFor(Item item) {
        if (item == null || item == Items.AIR) return SourceKind.NONE;
        if (isStrippedLog(item)) return SourceKind.STRIP_LOG;
        if (new ItemStack(item).isIn(ItemTags.LOGS)) return SourceKind.TREE;
        if (new ItemStack(item).isIn(ItemTags.WOOL)) return SourceKind.SHEEP;
        if (item == Items.CLAY_BALL) return SourceKind.CLAY_DEPOSIT;
        if (isCropYieldItem(item)) return SourceKind.CROP;
        if (isMineable(item)) return SourceKind.MINE;
        BlockState state = Registries.BLOCK.get(Registries.ITEM.getId(item)).getDefaultState();
        if (state.isIn(BlockTags.FLOWERS)) return SourceKind.FLOWER;
        if (isSafeNaturalBlock(state) && state.getBlock().asItem() == item) return SourceKind.NATURAL_BLOCK;
        if (item == Items.SUGAR_CANE || item == Items.BAMBOO || item == Items.CACTUS)
            return SourceKind.NATURAL_BLOCK;
        return SourceKind.NONE;
    }

    static boolean hasViablePlan(ServerWorld world, VillagerEntity villager, Item item, int count) {
        return world != null && villager != null && item != null
                && estimateItemCost(world, villager, item, count, 0, new HashSet<>()) < IMPOSSIBLE;
    }

    /** Exact items are preferred; an equal-or-better tool of the same family is also valid. */
    static boolean isCompatibleProviderItem(Item offered, Item wanted) {
        if (offered == null || wanted == null) return false;
        Identifier offeredId = Registries.ITEM.getId(offered);
        Identifier wantedId = Registries.ITEM.getId(wanted);
        return offeredId != null && wantedId != null
                && MaterialToolRules.isCompatibleProviderItem(offeredId.toString(), wantedId.toString());
    }

    static boolean shouldUseDirectSource(ServerWorld world, VillagerEntity villager, Item item, int count) {
        return withPlanningBudget(() -> shouldUseDirectSourceInternal(world, villager, item, count));
    }

    private static boolean shouldUseDirectSourceInternal(ServerWorld world, VillagerEntity villager, Item item, int count) {
        if (world == null || villager == null || item == null) return false;
        SourceKind source = sourceFor(item);
        if (source == SourceKind.NONE) return false;
        int held = countInventory(villager, item);
        int missing = Math.max(0, count - held);
        if (missing <= 0) return false;
        double directCost = estimateDirectSourceCost(world, villager, item, missing, 0, new HashSet<>());
        if (directCost >= IMPOSSIBLE) return false;

        double alternativeCost = IMPOSSIBLE;
        CraftingChoice crafting = chooseBestCraftingRecipe(world, villager, item, count);
        if (crafting != null) alternativeCost = Math.min(alternativeCost, crafting.score());
        StonecuttingChoice cutting = chooseBestStonecuttingRecipe(world, villager, item, count);
        if (cutting != null) alternativeCost = Math.min(alternativeCost, cutting.score());
        SmeltingChoice smelting = chooseBestSmeltingRecipe(world, villager, item, count);
        if (smelting != null) alternativeCost = Math.min(alternativeCost, smelting.score());
        return directCost <= alternativeCost;
    }


    static double estimateItemCost(ServerWorld world, VillagerEntity villager, Item item,
                                   int count, int depth, Set<Item> visiting) {
        return withPlanningBudget(() -> estimateItemCostInternal(world, villager, item, count, depth, visiting));
    }

    private static double estimateItemCostInternal(ServerWorld world, VillagerEntity villager, Item item,
                                                   int count, int depth, Set<Item> visiting) {
        if (world == null || villager == null || item == null || item == Items.AIR || count <= 0) return IMPOSSIBLE;
        if (!spendPlanningStep()) return boundedFallbackCost(world, villager, item, count);
        int held = countInventory(villager, item);
        if (isTool(item) && hasToolOrBetter(villager, item)) held = Math.max(1, held);
        int missing = Math.max(0, count - held);
        if (missing == 0) return 0.0D;
        if (depth >= MAX_DEPTH || !visiting.add(item)) return IMPOSSIBLE;

        double best = estimateDirectSourceCost(world, villager, item, missing, depth, visiting);
        // A nearby professional with an available offer is a first-class
        // acquisition source and normally beats extracting/crafting the item.
        best = Math.min(best, ProfessionalResourceExchange.estimateCost(world, villager, item, missing));

        for (RecipeEntry<CraftingRecipe> entry : limited(craftingRecipesFor(world, item), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != item) continue;
            int batches = (missing + output.getCount() - 1) / output.getCount();
            Map<Item, Integer> dependencies = chooseRecipeIngredients(world, villager,
                    entry.value().getIngredients(), batches, depth + 1, visiting);
            if (dependencies == null) continue;

            double recipeCost = requiresCraftingTable(entry.value(), item)
                    && !hasWorkstation(world, villager, Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE)
                    ? estimateItemCost(world, villager, Items.CRAFTING_TABLE, 1, depth + 1, visiting) : 0.0D;
            if (recipeCost >= IMPOSSIBLE) continue;
            boolean viable = true;
            for (Map.Entry<Item, Integer> dependency : dependencies.entrySet()) {
                double dependencyCost = estimateItemCost(world, villager, dependency.getKey(),
                        dependency.getValue(), depth + 1, visiting);
                if (dependencyCost >= IMPOSSIBLE) { viable = false; break; }
                recipeCost += dependencyCost;
            }
            if (viable) best = Math.min(best, recipeCost + 1.0D);
        }

        for (RecipeEntry<StonecuttingRecipe> entry : limited(stonecuttingRecipesFor(world, item), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != item) continue;
            int batches = (missing + output.getCount() - 1) / output.getCount();
            Ingredient inputIngredient = entry.value().getIngredients().stream()
                    .filter(candidate -> !candidate.isEmpty()).findFirst().orElse(null);
            if (inputIngredient == null) continue;
            ItemStack input = cheapestIngredient(world, villager, inputIngredient, batches,
                    depth + 1, visiting, null, Map.of());
            if (input.isEmpty()) continue;
            double inputCost = estimateItemCost(world, villager, input.getItem(), batches,
                    depth + 1, visiting);
            double cutterCost = countInventory(villager, Items.STONECUTTER) > 0 ? 0.0D
                    : estimateItemCost(world, villager, Items.STONECUTTER, 1, depth + 1, visiting);
            if (inputCost < IMPOSSIBLE && cutterCost < IMPOSSIBLE) {
                best = Math.min(best, inputCost + cutterCost + 1.5D);
            }
        }

        for (RecipeEntry<SmeltingRecipe> entry : limited(smeltingRecipesFor(world, item), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != item) continue;
            Ingredient inputIngredient = entry.value().getIngredients().stream()
                    .filter(candidate -> !candidate.isEmpty()).findFirst().orElse(null);
            if (inputIngredient == null) continue;
            int batches = (missing + output.getCount() - 1) / output.getCount();
            ItemStack input = cheapestIngredient(world, villager, inputIngredient, batches,
                    depth + 1, visiting, null, Map.of());
            if (input.isEmpty()) continue;
            double inputCost = estimateItemCost(world, villager, input.getItem(), batches, depth + 1, visiting);
            if (inputCost >= IMPOSSIBLE) continue;

            double furnaceCost = hasWorkstation(world, villager, Blocks.FURNACE, Items.FURNACE) ? 0.0D
                    : estimateItemCost(world, villager, Items.FURNACE, 1, depth + 1, visiting);
            if (furnaceCost >= IMPOSSIBLE) continue;
            int fuelNeeded = Math.max(1, (entry.value().getCookingTime() * batches + 1599) / 1600);
            int fuelHeld = countInventory(villager, Items.COAL) + countInventory(villager, Items.CHARCOAL);
            int fuelMissing = Math.max(0, fuelNeeded - fuelHeld);
            double fuelCost = fuelMissing == 0 ? 0.0D
                    : estimateItemCost(world, villager, Items.COAL, fuelMissing, depth + 1, visiting);
            if (fuelCost < IMPOSSIBLE) best = Math.min(best, inputCost + furnaceCost + fuelCost + 3.0D);
        }

        visiting.remove(item);
        return best;
    }

    static CraftingChoice chooseBestCraftingRecipe(ServerWorld world, VillagerEntity villager,
                                                    Item desired, int amount) {
        return withPlanningBudget(() -> chooseBestCraftingRecipeInternal(world, villager, desired, amount));
    }

    private static CraftingChoice chooseBestCraftingRecipeInternal(ServerWorld world, VillagerEntity villager,
                                                                    Item desired, int amount) {
        CraftingChoice best = null;
        int held = countInventory(villager, desired);
        if (isTool(desired) && hasToolOrBetter(villager, desired)) held = Math.max(1, held);
        int missing = Math.max(0, amount - held);
        if (missing <= 0) return null;
        for (RecipeEntry<CraftingRecipe> entry : limited(craftingRecipesFor(world, desired), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != desired) continue;
            int batches = (missing + output.getCount() - 1) / output.getCount();
            Map<Item, Integer> ingredients = chooseRecipeIngredients(world, villager,
                    entry.value().getIngredients(), batches, 0, new HashSet<>());
            if (ingredients == null) continue;

            double score = requiresCraftingTable(entry.value(), desired)
                    && !hasWorkstation(world, villager, Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE)
                    ? estimateItemCost(world, villager, Items.CRAFTING_TABLE, 1, 0, new HashSet<>()) : 0.0D;
            if (score >= IMPOSSIBLE) continue;
            boolean viable = true;
            for (Map.Entry<Item, Integer> ingredient : ingredients.entrySet()) {
                double cost = estimateItemCost(world, villager, ingredient.getKey(),
                        ingredient.getValue(), 0, new HashSet<>());
                if (cost >= IMPOSSIBLE) { viable = false; break; }
                score += cost;
            }
            if (viable && (best == null || score < best.score())) {
                best = new CraftingChoice(entry, Collections.unmodifiableMap(new java.util.LinkedHashMap<>(ingredients)), score);
            }
        }
        return best;
    }

    static StonecuttingChoice chooseBestStonecuttingRecipe(ServerWorld world, VillagerEntity villager,
                                                            Item desired, int amount) {
        return withPlanningBudget(() -> chooseBestStonecuttingRecipeInternal(world, villager, desired, amount));
    }

    private static StonecuttingChoice chooseBestStonecuttingRecipeInternal(ServerWorld world, VillagerEntity villager,
                                                                            Item desired, int amount) {
        StonecuttingChoice best = null;
        int held = countInventory(villager, desired);
        int missing = Math.max(0, amount - held);
        if (missing <= 0) return null;
        for (RecipeEntry<StonecuttingRecipe> entry : limited(stonecuttingRecipesFor(world, desired), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != desired) continue;
            int batches = (missing + output.getCount() - 1) / output.getCount();
            Ingredient inputIngredient = entry.value().getIngredients().stream()
                    .filter(candidate -> !candidate.isEmpty()).findFirst().orElse(null);
            if (inputIngredient == null) continue;
            ItemStack input = cheapestIngredient(world, villager, inputIngredient, batches,
                    0, new HashSet<>(), null, Map.of());
            if (input.isEmpty()) continue;
            double score = estimateItemCost(world, villager, input.getItem(), batches, 0, new HashSet<>());
            if (countInventory(villager, Items.STONECUTTER) == 0) {
                score += estimateItemCost(world, villager, Items.STONECUTTER, 1, 0, new HashSet<>());
            }
            score += 1.5D;
            if (score < IMPOSSIBLE && (best == null || score < best.score())) {
                best = new StonecuttingChoice(entry, input.getItem(), score);
            }
        }
        return best;
    }

    static SmeltingChoice chooseBestSmeltingRecipe(ServerWorld world, VillagerEntity villager,
                                                    Item desired, int amount) {
        return withPlanningBudget(() -> chooseBestSmeltingRecipeInternal(world, villager, desired, amount));
    }

    private static SmeltingChoice chooseBestSmeltingRecipeInternal(ServerWorld world, VillagerEntity villager,
                                                                    Item desired, int amount) {
        SmeltingChoice best = null;
        int held = countInventory(villager, desired);
        if (isTool(desired) && hasToolOrBetter(villager, desired)) held = Math.max(1, held);
        int missing = Math.max(0, amount - held);
        if (missing <= 0) return null;
        for (RecipeEntry<SmeltingRecipe> entry : limited(smeltingRecipesFor(world, desired), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != desired) continue;
            Ingredient inputIngredient = entry.value().getIngredients().stream()
                    .filter(candidate -> !candidate.isEmpty()).findFirst().orElse(null);
            if (inputIngredient == null) continue;
            int batches = (missing + output.getCount() - 1) / output.getCount();
            ItemStack input = cheapestIngredient(world, villager, inputIngredient, batches,
                    0, new HashSet<>(), null, Map.of());
            if (input.isEmpty()) continue;
            double score = estimateItemCost(world, villager, input.getItem(), batches, 0, new HashSet<>());
            double furnaceCost = hasWorkstation(world, villager, Blocks.FURNACE, Items.FURNACE) ? 0.0D
                    : estimateItemCost(world, villager, Items.FURNACE, 1, 0, new HashSet<>());
            int fuelCount = Math.max(1, (entry.value().getCookingTime() * batches + 1599) / 1600);
            int fuelHeld = countInventory(villager, Items.COAL) + countInventory(villager, Items.CHARCOAL);
            double fuelCost = fuelCount <= fuelHeld ? 0.0D
                    : estimateItemCost(world, villager, Items.COAL, fuelCount - fuelHeld, 0, new HashSet<>());
            score += furnaceCost + fuelCost + 3.0D;
            if (score < IMPOSSIBLE && (best == null || score < best.score())) {
                best = new SmeltingChoice(entry, input.getItem(), score);
            }
        }
        return best;
    }

    static boolean requiresCraftingTable(CraftingRecipe recipe, Item output) {
        // `fits(2, 2)` also catches sparse recipes wider/taller than the player grid,
        // which an ingredient-count heuristic misses (e.g. a 3x1 recipe).
        return isTool(output) || !recipe.fits(2, 2);
    }

    private static Map<Item, Integer> chooseRecipeIngredients(ServerWorld world, VillagerEntity villager,
                                                               List<Ingredient> recipeIngredients, int batches,
                                                               int depth, Set<Item> visiting) {
        if (!spendPlanningStep()) return null;
        Map<Item, Integer> selected = new java.util.LinkedHashMap<>();
        for (Ingredient ingredient : recipeIngredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack candidate = cheapestIngredient(world, villager, ingredient, batches,
                    depth, visiting, null, selected);
            if (candidate.isEmpty()) return null;
            selected.merge(candidate.getItem(), batches, Integer::sum);
        }
        for (Map.Entry<Item, Integer> dependency : selected.entrySet()) {
            if (estimateItemCost(world, villager, dependency.getKey(), dependency.getValue(),
                    depth + 1, new HashSet<>(visiting)) >= IMPOSSIBLE) return null;
        }
        List<Map.Entry<Item, Integer>> ordered = new ArrayList<>(selected.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<Item, Integer> entry) ->
                dependencyDepth(world, villager, entry.getKey(), 0, new HashSet<>())).reversed());
        Map<Item, Integer> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : ordered) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    /** Returns the deepest known prerequisite chain for an item, used to order sibling goals. */
    private static int dependencyDepth(ServerWorld world, VillagerEntity villager, Item item,
                                       int depth, Set<Item> visiting) {
        if (!spendPlanningStep() || item == null || item == Items.AIR || depth >= MAX_DEPTH || !visiting.add(item)) return 0;

        SourceKind source = sourceFor(item);
        if (source != SourceKind.NONE) {
            Item prerequisite = requiredTool(item);
            int sourceDepth = prerequisite != null && !hasItemOrEquipped(villager, prerequisite)
                    ? 1 + dependencyDepth(world, villager, prerequisite, depth + 1, new HashSet<>(visiting)) : 0;
            if (source == SourceKind.SHEEP && !hasToolOrBetter(villager, Items.SHEARS)) {
                sourceDepth = Math.max(sourceDepth, 1 + dependencyDepth(world, villager,
                        Items.SHEARS, depth + 1, new HashSet<>(visiting)));
            }
            visiting.remove(item);
            return sourceDepth;
        }

        int bestRecipeDepth = Integer.MAX_VALUE;
        for (RecipeEntry<CraftingRecipe> entry : limited(craftingRecipesFor(world, item), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != item) continue;
            int recipeDepth = 0;
            for (Ingredient ingredient : entry.value().getIngredients()) {
                if (ingredient.isEmpty()) continue;
                int ingredientDepth = Integer.MAX_VALUE;
                ItemStack[] alternatives = ingredient.getMatchingStacks();
                int considered = Math.min(alternatives.length, MAX_INGREDIENT_CANDIDATES);
                for (int i = 0; i < considered; i++) {
                    ItemStack candidate = alternatives[i];
                    ingredientDepth = Math.min(ingredientDepth, dependencyDepth(world, villager,
                            candidate.getItem(), depth + 1, new HashSet<>(visiting)));
                    if (ACTIVE_BUDGET.get() != null
                            && ACTIVE_BUDGET.get().steps >= MAX_PLANNER_STEPS) break;
                }
                if (ingredientDepth == Integer.MAX_VALUE) ingredientDepth = 0;
                recipeDepth = Math.max(recipeDepth, ingredientDepth + 1);
            }
            if (requiresCraftingTable(entry.value(), item)
                    && !hasWorkstation(world, villager, Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE)) {
                recipeDepth = Math.max(recipeDepth, 1 + dependencyDepth(world, villager,
                        Items.CRAFTING_TABLE, depth + 1, new HashSet<>(visiting)));
            }
            bestRecipeDepth = Math.min(bestRecipeDepth, recipeDepth);
        }
        for (RecipeEntry<StonecuttingRecipe> entry : limited(stonecuttingRecipesFor(world, item), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != item) continue;
            int recipeDepth = countInventory(villager, Items.STONECUTTER) > 0 ? 0
                    : 1 + dependencyDepth(world, villager, Items.STONECUTTER,
                    depth + 1, new HashSet<>(visiting));
            Ingredient input = entry.value().getIngredients().stream()
                    .filter(candidate -> !candidate.isEmpty()).findFirst().orElse(null);
            if (input != null) {
                int ingredientDepth = Integer.MAX_VALUE;
                ItemStack[] alternatives = input.getMatchingStacks();
                int considered = Math.min(alternatives.length, MAX_INGREDIENT_CANDIDATES);
                for (int i = 0; i < considered; i++) {
                    ItemStack candidate = alternatives[i];
                    ingredientDepth = Math.min(ingredientDepth, dependencyDepth(world, villager,
                            candidate.getItem(), depth + 1, new HashSet<>(visiting)));
                    if (ACTIVE_BUDGET.get() != null
                            && ACTIVE_BUDGET.get().steps >= MAX_PLANNER_STEPS) break;
                }
                if (ingredientDepth != Integer.MAX_VALUE) recipeDepth = Math.max(recipeDepth, ingredientDepth + 1);
            }
            bestRecipeDepth = Math.min(bestRecipeDepth, recipeDepth);
        }
        for (RecipeEntry<SmeltingRecipe> entry : limited(smeltingRecipesFor(world, item), MAX_RECIPES_PER_OUTPUT)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != item) continue;
            int recipeDepth = hasWorkstation(world, villager, Blocks.FURNACE, Items.FURNACE) ? 0
                    : 1 + dependencyDepth(world, villager, Items.FURNACE,
                    depth + 1, new HashSet<>(visiting));
            Ingredient input = entry.value().getIngredients().stream()
                    .filter(candidate -> !candidate.isEmpty()).findFirst().orElse(null);
            if (input != null) {
                int ingredientDepth = Integer.MAX_VALUE;
                ItemStack[] alternatives = input.getMatchingStacks();
                int considered = Math.min(alternatives.length, MAX_INGREDIENT_CANDIDATES);
                for (int i = 0; i < considered; i++) {
                    ItemStack candidate = alternatives[i];
                    ingredientDepth = Math.min(ingredientDepth, dependencyDepth(world, villager,
                            candidate.getItem(), depth + 1, new HashSet<>(visiting)));
                    if (ACTIVE_BUDGET.get() != null
                            && ACTIVE_BUDGET.get().steps >= MAX_PLANNER_STEPS) break;
                }
                if (ingredientDepth != Integer.MAX_VALUE) recipeDepth = Math.max(recipeDepth, ingredientDepth + 1);
            }
            if (countInventory(villager, Items.COAL) + countInventory(villager, Items.CHARCOAL) == 0) {
                recipeDepth = Math.max(recipeDepth, 1 + dependencyDepth(world, villager,
                        Items.COAL, depth + 1, new HashSet<>(visiting)));
            }
            bestRecipeDepth = Math.min(bestRecipeDepth, recipeDepth);
        }
        visiting.remove(item);
        return bestRecipeDepth == Integer.MAX_VALUE ? 0 : bestRecipeDepth;
    }

    static Item chooseIngredientItem(ServerWorld world, Ingredient ingredient, VillagerEntity villager,
                                     net.minecraft.registry.tag.TagKey<Item> preferredTag, int amount) {
        return withPlanningBudget(() -> {
            if (world == null || villager == null || ingredient == null || ingredient.isEmpty()) return null;
            ItemStack chosen = cheapestIngredient(world, villager, ingredient, Math.max(1, amount), 0,
                    new HashSet<>(), preferredTag, Map.of());
            return chosen.isEmpty() ? null : chosen.getItem();
        });
    }

    static boolean isSafeNaturalBlock(BlockState state) {
        return state.isIn(BlockTags.DIRT)
                || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.SUGAR_CANE)
                || state.isOf(Blocks.BAMBOO)
                || state.isOf(Blocks.CACTUS)
                || state.isIn(BlockTags.FLOWERS);
    }

    static double estimateDirectSourceCostForPlanning(ServerWorld world, VillagerEntity villager,
                                                       Item item, int amount) {
        return withPlanningBudget(() -> {
            if (world == null || villager == null || item == null || amount <= 0) return IMPOSSIBLE;
            return estimateDirectSourceCost(world, villager, item, amount, 0, new HashSet<>());
        });
    }

    private static double estimateDirectSourceCost(ServerWorld world, VillagerEntity villager, Item item,
                                                    int amount, int depth, Set<Item> visiting) {
        SourceKind source = sourceFor(item);
        if (source == SourceKind.NONE) return IMPOSSIBLE;
        double workCost = switch (source) {
            case TREE -> hasNearbyTreeSpecies(world, villager, item, 48) ? 2.0D : 18.0D;
            case STRIP_LOG -> hasNearbyTreeSpecies(world, villager, strippedBaseItem(item), 48) ? 4.0D : 20.0D;
            case MINE -> 10.0D;
            case SHEEP -> 8.0D;
            case CLAY_DEPOSIT -> hasNearbyNaturalBlock(world, villager, Items.CLAY, 32) ? 3.0D : 14.0D;
            case FLOWER -> hasNearbyNaturalBlock(world, villager, item, 32) ? 2.0D : 15.0D;
            case CROP -> hasNearbyNaturalBlock(world, villager, item, 32) ? 2.0D : 16.0D;
            case NATURAL_BLOCK -> hasNearbyNaturalBlock(world, villager, item, 32) ? 2.0D : 15.0D;
            default -> IMPOSSIBLE;
        };
        if (workCost >= IMPOSSIBLE) return IMPOSSIBLE;

        // Tool and workstation prerequisites are paid once for the whole batch,
        // not once per block. They are still resolved as regular item goals.
        double prerequisiteCost = 0.0D;
        Item prerequisiteTool = requiredTool(item);
        if (prerequisiteTool != null && !hasItemOrEquipped(villager, prerequisiteTool)) {
            prerequisiteCost = estimateItemCost(world, villager, prerequisiteTool, 1, depth + 1, visiting);
            if (prerequisiteCost >= IMPOSSIBLE) return IMPOSSIBLE;
        }
        if (source == SourceKind.SHEEP && !hasToolOrBetter(villager, Items.SHEARS)) {
            double shearsCost = estimateItemCost(world, villager, Items.SHEARS, 1, depth + 1, visiting);
            if (shearsCost >= IMPOSSIBLE) return IMPOSSIBLE;
            prerequisiteCost += shearsCost;
        }
        return workCost * amount + prerequisiteCost;
    }

    private static boolean hasNearbyNaturalBlock(ServerWorld world, VillagerEntity villager, Item wanted, int radius) {
        if (wanted == null || wanted == Items.AIR) return false;
        String cacheKey = sourceCacheKey(world, villager, wanted, radius, false);
        Boolean cached = SOURCE_AVAILABILITY_CACHE.get(cacheKey);
        if (cached != null) return cached;
        if (!spendSourceScan()) return false;
        boolean found = false;
        int step = 4;
        search:
        for (int x = villager.getBlockX() - radius; x <= villager.getBlockX() + radius; x += step) {
            for (int z = villager.getBlockZ() - radius; z <= villager.getBlockZ() + radius; z += step) {
                if (!world.isChunkLoaded(new net.minecraft.util.math.ChunkPos(x >> 4, z >> 4).toLong())) continue;
                int top = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z) - 1;
                for (int y = top + 2; y >= Math.max(world.getBottomY(), top - 10); y--) {
                    BlockState state = world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
                    if ((state.getBlock().asItem() == wanted
                            && (isSafeNaturalBlock(state) || state.isIn(BlockTags.FLOWERS)))
                            || isCropYieldBlock(state, wanted)) {
                        found = true;
                        break search;
                    }
                    if (wanted == Items.CLAY && state.isOf(Blocks.CLAY)) {
                        found = true;
                        break search;
                    }
                }
            }
        }
        SOURCE_AVAILABILITY_CACHE.put(cacheKey, found);
        return found;
    }

    private static boolean hasNearbyTreeSpecies(ServerWorld world, VillagerEntity villager, Item logItem, int radius) {
        if (logItem == null || logItem == Items.AIR) return false;
        String cacheKey = sourceCacheKey(world, villager, logItem, radius, true);
        Boolean cached = SOURCE_AVAILABILITY_CACHE.get(cacheKey);
        if (cached != null) return cached;
        if (!spendSourceScan()) return false;
        boolean found = false;
        int step = 4;
        search:
        for (int x = villager.getBlockX() - radius; x <= villager.getBlockX() + radius; x += step) {
            for (int z = villager.getBlockZ() - radius; z <= villager.getBlockZ() + radius; z += step) {
                if (!world.isChunkLoaded(new net.minecraft.util.math.ChunkPos(x >> 4, z >> 4).toLong())) continue;
                int top = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z) - 1;
                for (int y = top; y >= Math.max(world.getBottomY(), top - 8); y--) {
                    net.minecraft.util.math.BlockPos ground = new net.minecraft.util.math.BlockPos(x, y, z);
                    if (!world.getBlockState(ground).isIn(BlockTags.DIRT)) continue;
                    net.minecraft.util.math.BlockPos root = ground.up();
                    BlockState trunk = world.getBlockState(root);
                    if (!trunk.isIn(BlockTags.LOGS) || trunk.getBlock().asItem() != logItem) continue;
                    for (int dy = 3; dy <= 8; dy++) {
                        if (world.getBlockState(root.up(dy)).isIn(BlockTags.LEAVES)) {
                            found = true;
                            break search;
                        }
                    }
                }
            }
        }
        SOURCE_AVAILABILITY_CACHE.put(cacheKey, found);
        return found;
    }

    private static String sourceCacheKey(ServerWorld world, VillagerEntity villager, Item item,
                                         int radius, boolean tree) {
        long window = world.getTime() / 40L;
        if (window != sourceCacheWindow) {
            SOURCE_CACHE_WINDOW_SET(window);
        }
        return world.getRegistryKey().getValue() + ":" + Registries.ITEM.getId(item)
                + ":" + (villager.getBlockX() >> 4) + ":" + (villager.getBlockZ() >> 4)
                + ":" + radius + ":" + tree;
    }

    private static void SOURCE_CACHE_WINDOW_SET(long window) {
        sourceCacheWindow = window;
        SOURCE_AVAILABILITY_CACHE.clear();
    }

    private static Item strippedBaseItem(Item strippedItem) {
        if (strippedItem == null || strippedItem == Items.AIR) return null;
        String path = Registries.ITEM.getId(strippedItem).getPath();
        if (!isStrippedLog(strippedItem)) return null;
        Identifier rawId = Identifier.of(Registries.ITEM.getId(strippedItem).getNamespace(),
                path.substring("stripped_".length()));
        Item raw = Registries.ITEM.getOrEmpty(rawId).orElse(Items.AIR);
        return raw == Items.AIR ? null : raw;
    }

    private static ItemStack cheapestIngredient(ServerWorld world, VillagerEntity villager, Ingredient ingredient,
                                                  int amount, int depth, Set<Item> visiting) {
        return cheapestIngredient(world, villager, ingredient, amount, depth, visiting, null, Map.of());
    }

    private static ItemStack cheapestIngredient(ServerWorld world, VillagerEntity villager, Ingredient ingredient,
                                                  int amount, int depth, Set<Item> visiting,
                                                  net.minecraft.registry.tag.TagKey<Item> preferredTag,
                                                  Map<Item, Integer> alreadySelected) {
        ItemStack[] candidates = ingredient.getMatchingStacks();
        if (candidates.length == 0) return ItemStack.EMPTY;
        // Tag alternatives can expand to hundreds of modded items. Keep a small,
        // representative candidate set and prioritize resources already held,
        // preferred-tag variants, and sources this executor actually supports.
        LinkedHashMap<Item, ItemStack> shortlisted = new LinkedHashMap<>();
        for (ItemStack candidate : candidates) {
            if (countInventory(villager, candidate.getItem()) > 0) shortlisted.putIfAbsent(candidate.getItem(), candidate);
        }
        for (ItemStack candidate : candidates) {
            if (preferredTag != null && candidate.isIn(preferredTag)) shortlisted.putIfAbsent(candidate.getItem(), candidate);
        }
        for (ItemStack candidate : candidates) {
            if (sourceFor(candidate.getItem()) != SourceKind.NONE) shortlisted.putIfAbsent(candidate.getItem(), candidate);
        }
        for (ItemStack candidate : candidates) {
            if (shortlisted.size() >= MAX_INGREDIENT_CANDIDATES) break;
            shortlisted.putIfAbsent(candidate.getItem(), candidate);
        }
        List<ItemStack> ordered = new ArrayList<>(shortlisted.values());
        if (ordered.size() > MAX_INGREDIENT_CANDIDATES) {
            ordered = new ArrayList<>(ordered.subList(0, MAX_INGREDIENT_CANDIDATES));
        }
        ordered.sort(Comparator.comparingDouble(stack -> {
            Item candidate = stack.getItem();
            int reserved = alreadySelected.getOrDefault(candidate, 0);
            double before = reserved == 0 ? 0.0D : estimateItemCost(world, villager, candidate,
                    reserved, depth + 1, new HashSet<>(visiting));
            double after = estimateItemCost(world, villager, candidate, reserved + amount,
                    depth + 1, new HashSet<>(visiting));
            double incremental = after >= IMPOSSIBLE ? IMPOSSIBLE
                    : Math.max(0.0D, after - (before >= IMPOSSIBLE ? 0.0D : before));
            if (preferredTag != null && !stack.isIn(preferredTag)) incremental += 0.25D;
            return incremental;
        }));
        for (ItemStack candidate : ordered) {
            int reserved = alreadySelected.getOrDefault(candidate.getItem(), 0);
            double cost = estimateItemCost(world, villager, candidate.getItem(), reserved + amount,
                    depth + 1, new HashSet<>(visiting));
            if (cost < IMPOSSIBLE) return candidate;
        }
        return ItemStack.EMPTY;
    }

    private static boolean hasWorkstation(ServerWorld world, VillagerEntity villager, Block block, Item item) {
        return countInventory(villager, item) > 0
                || WorkstationLocator.findNearby(world, villager.getBlockPos(), block, 6, 3) != null;
    }

    private static int countInventory(VillagerEntity villager, Item item) {
        if (villager == null || item == null || item == Items.AIR) return 0;
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private static boolean hasItemOrEquipped(VillagerEntity villager, Item item) {
        return countInventory(villager, item) > 0 || hasToolOrBetter(villager, item);
    }

    static boolean hasToolOrBetter(VillagerEntity villager, Item required) {
        if (villager == null || required == null || required == Items.AIR) return false;
        if (required == Items.SHEARS) {
            if (villager.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.SHEARS)) return true;
            for (int slot = 0; slot < villager.getInventory().size(); slot++) {
                if (villager.getInventory().getStack(slot).isOf(Items.SHEARS)) return true;
            }
            return false;
        }
        int requiredTier = toolTier(required, required);
        if (requiredTier < 0) return false;
        if (toolTier(villager.getEquippedStack(EquipmentSlot.MAINHAND).getItem(), required) >= requiredTier) return true;
        if (toolTier(villager.getEquippedStack(EquipmentSlot.OFFHAND).getItem(), required) >= requiredTier) return true;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            if (toolTier(villager.getInventory().getStack(slot).getItem(), required) >= requiredTier) return true;
        }
        return false;
    }

    static int toolTier(Item candidate, Item familyReference) {
        if (candidate == null || familyReference == null) return -1;
        Identifier candidateId = Registries.ITEM.getId(candidate);
        Identifier familyId = Registries.ITEM.getId(familyReference);
        if (candidateId == null || familyId == null) return -1;
        return MaterialToolRules.toolTier(candidateId.toString(), familyId.toString());
    }

    private static boolean isTool(Item item) {
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE || item == Items.IRON_PICKAXE
                || item == Items.GOLDEN_PICKAXE || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE
                || item == Items.WOODEN_AXE || item == Items.STONE_AXE || item == Items.IRON_AXE
                || item == Items.GOLDEN_AXE || item == Items.DIAMOND_AXE || item == Items.NETHERITE_AXE
                || item == Items.WOODEN_SHOVEL || item == Items.STONE_SHOVEL || item == Items.IRON_SHOVEL
                || item == Items.GOLDEN_SHOVEL || item == Items.DIAMOND_SHOVEL || item == Items.NETHERITE_SHOVEL
                || item == Items.SHEARS;
    }

    static Item requiredToolFor(Item item) {
        return requiredTool(item);
    }

    private static Item requiredTool(Item item) {
        if (item == null || item == Items.AIR) return null;
        if (isStrippedLog(item)) return Items.WOODEN_AXE;

        Identifier itemId = Registries.ITEM.getId(item);
        if (itemId == null) return null;
        int explicitPickaxeTier = MaterialToolRules.requiredPickaxeTierForMaterial(itemId.toString());
        if (explicitPickaxeTier >= 0) {
            return switch (explicitPickaxeTier) {
                case 0 -> Items.WOODEN_PICKAXE;
                case 1 -> Items.STONE_PICKAXE;
                case 3 -> Items.IRON_PICKAXE;
                case 4 -> Items.DIAMOND_PICKAXE;
                default -> null;
            };
        }
        BlockState blockState = Registries.BLOCK.get(itemId).getDefaultState();
        if (new ItemStack(item).isIn(ItemTags.STONE_TOOL_MATERIALS)
                || (blockState.getBlock().asItem() == item
                && blockState.isIn(BlockTags.BASE_STONE_OVERWORLD)
                && item != Items.STONE && item != Items.DEEPSLATE)) {
            return Items.WOODEN_PICKAXE;
        }
        return null;
    }

    private static boolean isMineable(Item item) {
        if (item == null || item == Items.AIR) return false;
        if (item != Items.STONE && item != Items.DEEPSLATE
                && new ItemStack(item).isIn(ItemTags.STONE_TOOL_MATERIALS)) return true;
        BlockState blockState = Registries.BLOCK.get(Registries.ITEM.getId(item)).getDefaultState();
        if (item != Items.STONE && item != Items.DEEPSLATE
                && blockState.getBlock().asItem() == item
                && blockState.isIn(BlockTags.BASE_STONE_OVERWORLD)) return true;
        String path = Registries.ITEM.getId(item).getPath();
        return switch (path) {
            // These items are the actual vanilla drops from mining. Ore block
            // items are intentionally excluded because they require Silk Touch.
            case "cobblestone", "cobbled_deepslate", "coal", "raw_iron", "raw_copper",
                    "raw_gold", "redstone", "lapis_lazuli", "diamond", "emerald", "obsidian" -> true;
            default -> false;
        };
    }

    private static boolean isCropYieldItem(Item item) {
        return item == Items.WHEAT || item == Items.WHEAT_SEEDS || item == Items.CARROT || item == Items.POTATO
                || item == Items.BEETROOT || item == Items.BEETROOT_SEEDS || item == Items.PUMPKIN
                || item == Items.MELON_SLICE;
    }

    static boolean isCropYieldBlock(BlockState state, Item wanted) {
        if (wanted == Items.PUMPKIN) return state.isOf(Blocks.PUMPKIN);
        if (wanted == Items.MELON_SLICE) return state.isOf(Blocks.MELON);
        if (!(state.getBlock() instanceof CropBlock crop) || !state.contains(CropBlock.AGE)
                || state.get(CropBlock.AGE) < crop.getMaxAge()) return false;
        if (wanted == Items.WHEAT || wanted == Items.WHEAT_SEEDS) return state.isOf(Blocks.WHEAT);
        if (wanted == Items.CARROT) return state.isOf(Blocks.CARROTS);
        if (wanted == Items.POTATO) return state.isOf(Blocks.POTATOES);
        if (wanted == Items.BEETROOT || wanted == Items.BEETROOT_SEEDS) return state.isOf(Blocks.BEETROOTS);
        return false;
    }

    private static boolean isStrippedLog(Item item) {
        String path = Registries.ITEM.getId(item).getPath();
        return path.startsWith("stripped_") && (path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae"));
    }
}
