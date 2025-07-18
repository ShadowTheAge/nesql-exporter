package com.github.dcysteine.nesql.exporter.plugin.thaumcraft;

import codechicken.nei.NEIServerUtils;
import com.djgiannuzz.thaumcraftneiplugin.ModItems;
import com.djgiannuzz.thaumcraftneiplugin.items.ItemAspect;
import com.github.dcysteine.nesql.exporter.main.Logger;
import com.github.dcysteine.nesql.exporter.plugin.PluginExporter;
import com.github.dcysteine.nesql.exporter.plugin.PluginHelper;
import com.github.dcysteine.nesql.exporter.plugin.base.factory.RecipeBuilder;
import com.github.dcysteine.nesql.sql.base.recipe.RecipeType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapedRecipes;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.InfusionRecipe;
import thaumcraft.api.crafting.ShapedArcaneRecipe;
import thaumcraft.api.crafting.ShapelessArcaneRecipe;
import thaumcraft.common.config.ConfigResearch;
import thaumcraft.common.lib.crafting.ShapelessNBTOreRecipe;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ThaumcraftRecipeProcessor extends PluginHelper {
    private final RecipeType shapedCrafting;
    private final RecipeType shapelessCrafting;
    private final RecipeType alchemyCrafting;
    private final RecipeType infusionCrafting;
    public ThaumcraftRecipeProcessor(PluginExporter exporter, ThaumcraftRecipeTypeHandler handler) {
        super(exporter);
        shapedCrafting = handler.getRecipeType(ThaumcraftRecipeTypeHandler.ThaumcraftRecipeType.MAGIC_CRAFTING_SHAPED);
        shapelessCrafting = handler.getRecipeType(ThaumcraftRecipeTypeHandler.ThaumcraftRecipeType.MAGIC_CRAFTING_SHAPELESS);
        alchemyCrafting = handler.getRecipeType(ThaumcraftRecipeTypeHandler.ThaumcraftRecipeType.ALCHEMY);
        infusionCrafting = handler.getRecipeType(ThaumcraftRecipeTypeHandler.ThaumcraftRecipeType.INFUSION);
    }

    public void process() {
        @SuppressWarnings("unchecked")
        List<Object> recipes = ThaumcraftApi.getCraftingRecipes();
        int total = recipes.size();
        logger.info("Processing {} thaumcraft recipes...", total);

        int count = 0;
        for (Object recipe : recipes) {
            count++;

            if (recipe instanceof ShapedArcaneRecipe) {
                processShapedRecipe((ShapedArcaneRecipe) recipe);
            } else if (recipe instanceof ShapelessArcaneRecipe) {
                processShapelessRecipe((ShapelessArcaneRecipe) recipe);
            } else if (recipe instanceof InfusionRecipe) {
                processInfusionRecipe((InfusionRecipe) recipe);
            } else if (recipe instanceof CrucibleRecipe) {
                processAlchemyRecipe((CrucibleRecipe) recipe);
            } else {
                logger.warn("Unhandled crafting recipe: {}", recipe);
            }

            if (Logger.intermittentLog(count)) {
                logger.info("Processed crafting recipe {} of {}", count, total);
            }
        }

        exporterState.flushEntityManager();
        logger.info("Finished processing crafting recipes!");
    }

    private void processShapedRecipe(ShapedArcaneRecipe recipe) {
        RecipeBuilder builder = new RecipeBuilder(exporter, shapedCrafting);
        for (Object itemInput : recipe.getInput()) {
            if (itemInput == null) {
                builder.skipItemInput();
                continue;
            }

            handleItemInput(builder, itemInput);
        }
        PrintAspects(builder, recipe.aspects, 9);
        builder.addItemOutput(recipe.getRecipeOutput()).build();
    }

    private void processShapelessRecipe(ShapelessArcaneRecipe recipe) {
        RecipeBuilder builder = new RecipeBuilder(exporter, shapelessCrafting);
        for (Object itemInput : recipe.getInput()) {
            handleItemInput(builder, itemInput);
        }
        PrintAspects(builder, recipe.aspects, 9);
        builder.addItemOutput(recipe.getRecipeOutput()).build();
    }

    private void processAlchemyRecipe(CrucibleRecipe recipe) {
        RecipeBuilder builder = new RecipeBuilder(exporter, alchemyCrafting);
        handleItemInput(builder, recipe.catalyst);
        PrintAspects(builder, recipe.aspects, 9-recipe.aspects.size());
        builder.addItemOutput(recipe.getRecipeOutput()).build();
    }

    private static final int[] componentFillOrder = new int[] {2, 22, 10, 14, 0, 24, 4, 20, 1, 23, 3, 21, 5, 19, 15, 9, 7, 17, 11, 13, 6, 18, 8, 16, 25, 26, 27, 28, 29, 30, 31, 32};

    private void processInfusionRecipe(InfusionRecipe recipe) {
        var output = recipe.getRecipeOutput() instanceof ItemStack ? (ItemStack) recipe.getRecipeOutput() : recipe.getRecipeInput();
        if (output == null || output.getItem() == null)
            return;

        RecipeBuilder builder = new RecipeBuilder(exporter, infusionCrafting);
        var fakeInputMap = new ItemStack[32];
        fakeInputMap[12] = recipe.getRecipeInput();

        var componentId = 0;
        for (var itemInput : recipe.getComponents()) {
            fakeInputMap[componentFillOrder[componentId++]] = itemInput;
        }

        for (var i=0; i<32; i++) {
            if (fakeInputMap[i] != null) {
                builder.itemInputsIndex = i;
                builder.addItemInput(fakeInputMap[i]);
            }
        }
        PrintAspects(builder, recipe.getAspects(), 25);

        builder.addItemOutput(output).build();
    }

    private void PrintAspects(RecipeBuilder builder, AspectList list, int index)
    {
        if (builder.itemInputsIndex < index)
            builder.itemInputsIndex = index;
        for (var aspect : list.getAspectsSorted())
        {
            ItemStack iconItemStack = new ItemStack(ModItems.itemAspect, list.getAmount(aspect), 0);
            ItemAspect.setAspects(iconItemStack, new AspectList().add(aspect, 2));
            builder.addItemInput(iconItemStack);
        }
    }

    private void handleItemInput(RecipeBuilder builder, Object itemInput) {
        ItemStack[] itemStacks = NEIServerUtils.extractRecipeItems(itemInput);
        if (itemStacks == null || itemStacks.length == 0) {
            builder.skipItemInput();
            return;
        }

        // For some reason, a bunch of crafting recipes have stack size > 1, even though crafting
        // recipes only ever consume one item from each slot. This is probably a bug in the recipes.
        // We'll fix this by manually setting stack sizes to 1.
        ItemStack[] fixedItemStacks = new ItemStack[itemStacks.length];
        boolean foundBadStackSize = false;
        for (int i = 0; i < itemStacks.length; i++) {
            ItemStack itemStack = itemStacks[i];

            if (itemStack.stackSize != 1) {
                foundBadStackSize = true;
                fixedItemStacks[i] = itemStack.copy();
                fixedItemStacks[i].stackSize = 1;
            } else {
                fixedItemStacks[i] = itemStack;
            }
        }

        if (foundBadStackSize) {
            logger.warn("Crafting recipe with bad stack size: {}", Arrays.toString(itemStacks));
        }

        builder.addItemGroupInput(fixedItemStacks);
    }
}
