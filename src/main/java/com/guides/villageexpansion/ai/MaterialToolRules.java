package com.guides.villageexpansion.ai;

/**
 * Pure, registry-independent rules for vanilla tool-family compatibility.
 *
 * Item identifiers are used instead of Minecraft Item instances so these
 * rules can be unit-tested by plain JUnit without bootstrapping Minecraft.
 */
final class MaterialToolRules {
    private MaterialToolRules() { }

    static boolean isCompatibleProviderItem(String offeredId, String wantedId) {
        String offered = normalize(offeredId);
        String wanted = normalize(wantedId);
        if (offered == null || wanted == null
                || offered.equals("minecraft:air") || wanted.equals("minecraft:air")) {
            return false;
        }
        if (offered.equals(wanted)) return true;

        String wantedFamily = family(wanted);
        if (wantedFamily == null) return false;
        int wantedTier = harvestTier(wanted);
        return wantedTier >= 0 && toolTier(offered, wanted) >= wantedTier;
    }

    /** Returns the harvest tier of candidate for the family named by familyReferenceId. */
    static int toolTier(String candidateId, String familyReferenceId) {
        String candidate = normalize(candidateId);
        String reference = normalize(familyReferenceId);
        if (candidate == null || reference == null) return -1;
        String wantedFamily = family(reference);
        if (wantedFamily == null || !wantedFamily.equals(family(candidate))) return -1;
        return harvestTier(candidate);
    }

    /**
     * Required vanilla pickaxe tier for explicitly recognized mining targets.
     * Values match MaterialAcquisitionPlanner's harvesting thresholds:
     * wooden=0, stone=1, iron=3, diamond=4.
     */
    static int requiredPickaxeTierForMaterial(String itemId) {
        String id = normalize(itemId);
        if (id == null || !id.startsWith("minecraft:")) return -1;
        String path = path(id);

        if (path.equals("obsidian")) return 4;
        if (path.equals("diamond") || path.equals("emerald")
                || path.equals("diamond_ore") || path.equals("deepslate_diamond_ore")
                || path.equals("emerald_ore") || path.equals("deepslate_emerald_ore")) return 3;
        if (path.equals("raw_iron") || path.equals("raw_copper") || path.equals("raw_gold")
                || path.equals("lapis_lazuli") || path.equals("redstone")
                || path.equals("iron_ore") || path.equals("deepslate_iron_ore")
                || path.equals("copper_ore") || path.equals("deepslate_copper_ore")
                || path.equals("gold_ore") || path.equals("deepslate_gold_ore")
                || path.equals("lapis_ore") || path.equals("deepslate_lapis_ore")
                || path.equals("redstone_ore") || path.equals("deepslate_redstone_ore")) return 1;
        if (path.equals("coal") || path.equals("cobblestone") || path.equals("cobbled_deepslate")
                || path.equals("coal_ore") || path.equals("deepslate_coal_ore")) return 0;
        return -1;
    }

    private static int harvestTier(String id) {
        String path = path(id);
        if (path.startsWith("wooden_") || path.startsWith("golden_")) return 0;
        if (path.startsWith("stone_")) return 1;
        if (path.startsWith("iron_")) return 3;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("netherite_")) return 5;
        return -1;
    }

    private static String family(String id) {
        // The original planner tier table recognizes vanilla Items only.
        if (!id.startsWith("minecraft:")) return null;
        String path = path(id);
        if (path.endsWith("_pickaxe")) return "pickaxe";
        if (path.endsWith("_axe")) return "axe";
        if (path.endsWith("_shovel")) return "shovel";
        return null;
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) return null;
        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.indexOf(':') < 0 ? "minecraft:" + normalized : normalized;
    }

    private static String path(String id) {
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }
}
