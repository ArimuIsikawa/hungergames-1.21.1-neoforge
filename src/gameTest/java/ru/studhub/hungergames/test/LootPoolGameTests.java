package ru.studhub.hungergames.test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ru.studhub.hungergames.loot.LootPool;

@GameTestHolder("hungergames")
@PrefixGameTestTemplate(false)
public final class LootPoolGameTests {
    private static final String BUNDLE = """
            {"stack":{"id":"minecraft:bow","count":1},"weight":1,"minCount":1,"maxCount":1,
             "companions":[{"stack":{"id":"minecraft:arrow","count":1},"minCount":12,"maxCount":24}]}
            """;

    private static LootPool load(GameTestHelper helper, int rolls, String entry) throws IOException {
        return loadJson(helper, "{\"schemaVersion\":1,\"minRolls\":" + rolls + ",\"maxRolls\":" + rolls + ",\"entries\":[" + entry + "]}");
    }

    private static LootPool loadJson(GameTestHelper helper, String json) throws IOException {
        var path = Files.createTempFile("hg-loot-test-", ".json");
        try {
            Files.writeString(path, json);
            return LootPool.load(path, helper.getLevel().registryAccess());
        } finally { Files.deleteIfExists(path); }
    }

    @GameTest(template = "empty")
    public static void completeRewardPackage(GameTestHelper helper) throws IOException {
        String stack = "{\"stack\":{\"id\":\"minecraft:diamond\",\"count\":1},\"minCount\":2,\"maxCount\":5}";
        String group = "{\"minRolls\":1,\"maxRolls\":1,\"entries\":[" + BUNDLE + "]}";
        String json = "{\"schemaVersion\":1,\"minRolls\":1,\"maxRolls\":1,\"entries\":[],\"guaranteed\":[" + stack
                + "],\"pools\":[" + group + "]}";
        LootPool pool = loadJson(helper, json);
        helper.assertTrue(!pool.isEmpty() && pool.maxStacks() == 3, "Composite reward was treated as empty");
        for (int i = 0; i < 100; i++) {
            var reward = pool.roll(new Random(i));
            helper.assertTrue(reward.size() == 3 && reward.get(0).is(Items.DIAMOND)
                    && reward.get(1).is(Items.BOW) && reward.get(2).is(Items.ARROW), "Winner did not receive all reward categories");
        }
        boolean rejected = false;
        try { loadJson(helper, json.replace("\"pools\":[" + group + "]", "\"pools\":[" + String.join(",", java.util.Collections.nCopies(14, group)) + "]")); }
        catch (IOException expected) { rejected = true; }
        helper.assertTrue(rejected, "Accepted composite reward exceeding capacity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deployedRewardPreset(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("tacz")) { helper.succeed(); return; }
        var path = java.nio.file.Path.of("../../config-presets/hungergames/rewards.json");
        var pool = LootPool.load(path, helper.getLevel().registryAccess());
        var stackType = net.minecraft.world.item.ItemStack.class;
        var gunApi = Class.forName("com.tacz.guns.api.item.IGun");
        var ammoApi = Class.forName("com.tacz.guns.api.item.IAmmo");
        var attachmentApi = Class.forName("com.tacz.guns.api.item.IAttachment");
        var attachmentType = Class.forName("com.tacz.guns.api.item.attachment.AttachmentType");
        var gunAdapter = gunApi.getMethod("getIGunOrNull", stackType);
        var ammoAdapter = ammoApi.getMethod("getIAmmoOrNull", stackType);
        var attachmentAdapter = attachmentApi.getMethod("getIAttachmentOrNull", stackType);
        var ammoMatches = ammoApi.getMethod("isAmmoOfGun", stackType, stackType);
        var allows = gunApi.getMethod("allowAttachment", stackType, stackType);
        var allowsType = gunApi.getMethod("allowAttachmentType", stackType, attachmentType);
        helper.assertTrue(pool.maxStacks() == 21 && pool.pools().get(1).chance() == .25
                && pool.pools().getLast().chance() == .5, "Reward probability or capacity settings are wrong");
        int configuredAttachments = 0;
        for (var entry : pool.pools().getFirst().entries()) {
            var gun = gunAdapter.invoke(null, entry.stack());
            helper.assertTrue(entry.companions().size() == 4 && entry.optionalCompanions().size() == 1
                    && entry.optionalCompanions().getFirst().chance() == 1, "Reward gun must include ammo and exactly one attachment");
            for (var choice : entry.optionalCompanions().getFirst().entries()) {
                var attachment = choice.item().stack();
                var item = attachmentAdapter.invoke(null, attachment);
                var kind = attachmentApi.getMethod("getType", stackType).invoke(item, attachment);
                helper.assertTrue((boolean) allows.invoke(gun, entry.stack(), attachment)
                        && (boolean) allowsType.invoke(gun, entry.stack(), kind), "Reward attachment is incompatible");
                var copy = entry.stack().copy();
                gunApi.getMethod("installAttachment", net.minecraft.core.HolderLookup.Provider.class, stackType, stackType)
                        .invoke(gun, helper.getLevel().registryAccess(), copy, attachment.copy());
                helper.assertTrue(attachmentApi.getMethod("getAttachmentId", stackType).invoke(item, attachment)
                        .equals(gunApi.getMethod("getAttachmentId", stackType, attachmentType).invoke(gun, copy, kind)),
                        "Reward attachment cannot be installed");
                configuredAttachments++;
            }
        }
        var random = new Random(9471);
        int doubleWeapons = 0, plateRewards = 0;
        var appleCounts = new java.util.HashSet<Integer>();
        for (int i = 0; i < 5000; i++) {
            var reward = pool.roll(random);
            for (var item : List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.ENCHANTED_GOLDEN_APPLE)) {
                helper.assertTrue(reward.stream().anyMatch(s -> s.is(item)), "Missing guaranteed resource: " + item);
            }
            int guns = 0, ammo = 0, attachments = 0, armorCount = 0, plates = 0;
            var armorTypes = java.util.EnumSet.noneOf(net.minecraft.world.item.ArmorItem.Type.class);
            for (int index = 0; index < reward.size(); index++) {
                var stack = reward.get(index);
                Object gun = gunAdapter.invoke(null, stack);
                if (gun != null) {
                    guns++;
                    for (int offset = 1; offset <= 4; offset++) {
                        var companion = reward.get(index + offset);
                        Object item = ammoAdapter.invoke(null, companion);
                        helper.assertTrue(item != null && (boolean) ammoMatches.invoke(item, stack, companion),
                                "Reward weapon lost its own matching ammunition");
                    }
                    var attachment = reward.get(index + 5);
                    Object item = attachmentAdapter.invoke(null, attachment);
                    helper.assertTrue(item != null && (boolean) allows.invoke(gun, stack, attachment)
                            && (boolean) allowsType.invoke(gun, stack, attachmentApi.getMethod("getType", stackType).invoke(item, attachment)),
                            "Reward weapon lost its own compatible attachment");
                }
                if (ammoAdapter.invoke(null, stack) != null) ammo++;
                if (attachmentAdapter.invoke(null, stack) != null) attachments++;
                if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor) {
                    helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("fracturepoint"),
                            "Vanilla armor remains in the reward");
                    armorTypes.add(armor.getType()); armorCount++;
                }
                if (stack.getItem().getClass().getSimpleName().equals("ArmorPlateItem")) plates++;
                if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                    helper.assertTrue(stack.getCount() >= 1 && stack.getCount() <= 3, "Apple count outside 1-3");
                    appleCounts.add(stack.getCount());
                }
            }
            helper.assertTrue((guns == 1 || guns == 2) && ammo == guns * 4 && attachments == guns && plates <= 1,
                    "Invalid number of reward guns, ammunition, attachments or plates");
            helper.assertTrue(armorCount == 4 && armorTypes.equals(java.util.EnumSet.of(
                    net.minecraft.world.item.ArmorItem.Type.HELMET, net.minecraft.world.item.ArmorItem.Type.CHESTPLATE,
                    net.minecraft.world.item.ArmorItem.Type.LEGGINGS, net.minecraft.world.item.ArmorItem.Type.BOOTS)), "Incomplete Fracture Point armor");
            helper.assertTrue(reward.size() == 8 + guns * 6 + plates, "Unexpected items or missing reward categories");
            if (guns == 2) doubleWeapons++;
            if (plates == 1) plateRewards++;
        }
        helper.assertTrue(doubleWeapons >= 1125 && doubleWeapons <= 1375, "Second weapon probability is not 25%: " + doubleWeapons);
        helper.assertTrue(plateRewards >= 2350 && plateRewards <= 2650, "Plate probability is not 50%: " + plateRewards);
        helper.assertTrue(appleCounts.equals(java.util.Set.of(1, 2, 3)), "Apple count never varied across 1-3");
        helper.getLevel().getServer().sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "HG_WINNER_REWARDS PASS: 5000 rewards, " + doubleWeapons + " second weapons, " + plateRewards
                        + " plates, 4 Fracture Point armor slots, 1-3 apples, " + configuredAttachments + " installable attachment pairs"));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void conditionalPools(GameTestHelper helper) throws IOException {
        String emerald = "{\"stack\":{\"id\":\"minecraft:emerald\",\"count\":1},\"minCount\":1,\"maxCount\":1}";
        String diamond = emerald.replace("minecraft:emerald", "minecraft:diamond");
        String bread = "{\"minRolls\":1,\"maxRolls\":1,\"entries\":["
                + "{\"stack\":{\"id\":\"minecraft:bread\",\"count\":1},\"weight\":1,\"minCount\":1,\"maxCount\":1}]}";
        String group = "{\"chance\":0.25,\"minRolls\":1,\"maxRolls\":1,\"guaranteed\":[" + diamond
                + "],\"entries\":[" + BUNDLE + "],\"pools\":[" + bread + "]}";
        String root = "{\"schemaVersion\":1,\"minRolls\":1,\"maxRolls\":1,\"entries\":[],\"guaranteed\":[" + emerald
                + "],\"pools\":[" + group + "]}";
        var pool = loadJson(helper, root);
        helper.assertTrue(pool.maxStacks() == 5 && !pool.isEmpty(), "Conditional bundle missing from capacity budget");
        for (double sample : new double[] {0, Math.nextDown(.25), .25, .999}) {
            var loot = pool.roll(new Random(123) { @Override public double nextDouble() { return sample; } });
            helper.assertTrue(loot.size() == (sample < .25 ? 5 : 1) && loot.getFirst().is(Items.EMERALD),
                    "25% chance must include or exclude the whole bundle");
            if (sample < .25) helper.assertTrue(loot.get(1).is(Items.DIAMOND) && loot.get(2).is(Items.BOW)
                    && loot.get(3).is(Items.ARROW) && loot.get(4).is(Items.BREAD), "Nested or guaranteed bundle items were lost");
        }
        var disabled = loadJson(helper, "{\"schemaVersion\":1," + group.substring(1).replace("\"chance\":0.25", "\"chance\":0"));
        helper.assertTrue(disabled.isEmpty() && disabled.maxStacks() == 0 && disabled.roll(new Random(1)).isEmpty(),
                "Disabled pool generated guaranteed or nested items");
        var legacy = load(helper, 1, BUNDLE);
        helper.assertTrue(legacy.chance() == 1 && legacy.roll(new Random(1) {
            @Override public double nextDouble() { throw new AssertionError("Legacy pool consumed a chance roll"); }
        }).size() == 2, "Legacy pools must retain their RNG sequence");
        for (String chance : new String[] {"-1", "1.01", "\"NaN\"", "\"Infinity\""}) {
            boolean rejected = false;
            try { loadJson(helper, root.replace("\"chance\":0.25", "\"chance\":" + chance)); }
            catch (IOException expected) { rejected = true; }
            helper.assertTrue(rejected, "Invalid pool chance accepted: " + chance);
        }
        boolean overflow = false;
        String sevenGroups = String.join(",", java.util.Collections.nCopies(7, group.replace("\"chance\":0.25", "\"chance\":0.000001")));
        try { loadJson(helper, root.replace("\"pools\":[" + group + "]", "\"pools\":[" + sevenGroups + "]")); }
        catch (IOException expected) { overflow = true; }
        helper.assertTrue(overflow, "Rare conditional bundles may overflow when they all succeed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void oneWeaponPerChest(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("tacz")) { helper.succeed(); return; }
        var pool = LootPool.load(java.nio.file.Path.of("../../config-presets/hungergames/chest_loot.json"), helper.getLevel().registryAccess());
        var gunApi = Class.forName("com.tacz.guns.api.item.IGun");
        var ammoApi = Class.forName("com.tacz.guns.api.item.IAmmo");
        var attachmentApi = Class.forName("com.tacz.guns.api.item.IAttachment");
        var stackType = net.minecraft.world.item.ItemStack.class;
        var attachmentType = Class.forName("com.tacz.guns.api.item.attachment.AttachmentType");
        var adapter = gunApi.getMethod("getIGunOrNull", net.minecraft.world.item.ItemStack.class);
        var ammoAdapter = ammoApi.getMethod("getIAmmoOrNull", net.minecraft.world.item.ItemStack.class);
        var attachmentAdapter = attachmentApi.getMethod("getIAttachmentOrNull", stackType);
        var matches = ammoApi.getMethod("isAmmoOfGun", net.minecraft.world.item.ItemStack.class, net.minecraft.world.item.ItemStack.class);
        var allows = gunApi.getMethod("allowAttachment", stackType, stackType);
        var allowsType = gunApi.getMethod("allowAttachmentType", stackType, attachmentType);
        int configuredAttachments = 0;
        for (var entry : pool.pools().getFirst().entries()) {
            helper.assertTrue(entry.companions().size() == 4, "Each gun must have four ammunition stacks");
            helper.assertTrue(entry.optionalCompanions().size() == 1
                    && entry.optionalCompanions().getFirst().chance() == .5, "Attachment chance must be 50% per gun");
            var gun = adapter.invoke(null, entry.stack());
            for (var choice : entry.optionalCompanions().getFirst().entries()) {
                var attachment = choice.item().stack();
                var attachmentItem = attachmentAdapter.invoke(null, attachment);
                helper.assertTrue(attachmentItem != null, "Invalid attachment item");
                var kind = attachmentApi.getMethod("getType", stackType).invoke(attachmentItem, attachment);
                helper.assertTrue((boolean) allows.invoke(gun, entry.stack(), attachment)
                        && (boolean) allowsType.invoke(gun, entry.stack(), kind), "Incompatible configured attachment");
                var copy = entry.stack().copy();
                gunApi.getMethod("installAttachment", net.minecraft.core.HolderLookup.Provider.class, stackType, stackType)
                        .invoke(gun, helper.getLevel().registryAccess(), copy, attachment.copy());
                var installed = gunApi.getMethod("getAttachmentId", stackType, attachmentType).invoke(gun, copy, kind);
                var expected = attachmentApi.getMethod("getAttachmentId", stackType).invoke(attachmentItem, attachment);
                helper.assertTrue(expected.equals(installed), "Attachment could not be installed on its gun");
                configuredAttachments++;
            }
        }
        helper.assertTrue(configuredAttachments > 28, "Expected varied compatible attachments");
        var random = new Random(71982);
        int withAttachment = 0;
        var gearKinds = new java.util.HashSet<String>();
        for (int i = 0; i < 5000; i++) {
            var loot = pool.roll(random);
            int guns = 0;
            int attachments = 0;
            for (var stack : loot) if (adapter.invoke(null, stack) != null) {
                guns++;
                boolean hasAmmo = false;
                for (var companion : loot) {
                    Object ammo = ammoAdapter.invoke(null, companion);
                    if (ammo != null && (boolean) matches.invoke(ammo, stack, companion)) hasAmmo = true;
                }
                helper.assertTrue(hasAmmo, "Chest weapon is missing matching ammunition");
                for (var companion : loot) {
                    var item = attachmentAdapter.invoke(null, companion);
                    if (item != null) {
                        attachments++;
                        var kind = attachmentApi.getMethod("getType", stackType).invoke(item, companion);
                        helper.assertTrue((boolean) allows.invoke(adapter.invoke(null, stack), stack, companion)
                                && (boolean) allowsType.invoke(adapter.invoke(null, stack), stack, kind), "Attachment belongs to another gun");
                    }
                    var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(companion.getItem());
                    if (id.getNamespace().equals("fracturepoint")) gearKinds.add(companion.getItem().getClass().getSimpleName());
                }
            }
            helper.assertTrue(loot.stream().filter(s -> {
                try { return ammoAdapter.invoke(null, s) != null; } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }).count() == 4, "Chest must contain four matching ammunition stacks");
            helper.assertTrue(guns == 1 && attachments <= 1 && loot.size() >= 8 && loot.size() <= 12,
                    "Chest must contain one gun, doubled ammo and at most one attachment");
            withAttachment += attachments;
        }
        helper.assertTrue(withAttachment >= 2350 && withAttachment <= 2650, "Unexpected attachment frequency: " + withAttachment);
        helper.assertTrue(gearKinds.containsAll(List.of("WBArmorItem", "ArmorPlateItem", "BackpackItem")),
                "Missing Fracture Point armor, plates or backpacks: " + gearKinds);
        helper.getLevel().getServer().sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "HG_COMBAT_LOOT PASS: 5000 chests, " + configuredAttachments + " compatible/installable attachments, "
                        + withAttachment + " attachment drops, armor/plates/backpacks"));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void optionalCompanionProbabilityAndCapacity(GameTestHelper helper) throws IOException {
        String bonus = "\"optionalCompanions\":[{\"chance\":0.1,\"entries\":["
                + "{\"stack\":{\"id\":\"minecraft:diamond\",\"count\":1},\"weight\":1,\"minCount\":1,\"maxCount\":1},"
                + "{\"stack\":{\"id\":\"minecraft:emerald\",\"count\":1},\"weight\":3,\"minCount\":1,\"maxCount\":1}]}]";
        String entry = BUNDLE.strip().replace("}]}", "}]," + bonus + "}");
        var pool = load(helper, 1, entry);
        for (double probability : new double[] {0, Math.nextDown(.1), .1, .999}) {
            var loot = pool.roll(new Random(42) { @Override public double nextDouble() { return probability; } });
            helper.assertTrue(loot.size() == (probability < .1 ? 3 : 2), "Chance boundary must be strictly less than 10%");
            helper.assertTrue(loot.get(0).is(Items.BOW) && loot.get(1).is(Items.ARROW), "Optional item replaced the guaranteed bundle");
        }
        int diamonds = 0, emeralds = 0;
        var always = load(helper, 1, entry.replace("\"chance\":0.1", "\"chance\":1"));
        var random = new Random(3672);
        for (int i = 0; i < 2000; i++) {
            var loot = always.roll(random);
            helper.assertTrue(loot.size() == 3, "Exactly one optional choice must be selected");
            if (loot.get(2).is(Items.DIAMOND)) diamonds++; else if (loot.get(2).is(Items.EMERALD)) emeralds++;
        }
        helper.assertTrue(diamonds > 400 && diamonds < 600 && diamonds + emeralds == 2000, "Optional choice weights ignored");
        helper.assertTrue(load(helper, 9, entry).maxStacks() == 27, "Optional item missing from capacity budget");
        for (String invalid : new String[] {
                entry.replace("\"chance\":0.1", "\"chance\":-0.1"),
                entry.replace("\"chance\":0.1", "\"chance\":1.1"),
                entry.replace("\"chance\":0.1", "\"chance\":\"NaN\""),
                entry.replace("\"chance\":0.1", "\"chance\":\"Infinity\""),
                entry.replace("\"weight\":3", "\"weight\":0"),
                entry.replace("minecraft:emerald", "hungergames:missing_attachment"),
                entry.replace("\"minCount\":1,\"maxCount\":1", "\"minCount\":1,\"maxCount\":65"),
                BUNDLE.strip().replace("}]}", "}],\"optionalCompanions\":[{\"chance\":0.1,\"entries\":[]}]}"),
        }) {
            boolean rejected = false;
            try { load(helper, 1, invalid); } catch (IOException expected) { rejected = true; }
            helper.assertTrue(rejected, "Invalid optional pool accepted: " + invalid);
        }
        boolean overflow = false;
        try { load(helper, 10, entry); } catch (IOException expected) { overflow = true; }
        helper.assertTrue(overflow, "Rare optional drops must still fit when all chance checks succeed");
        var never = load(helper, 1, entry.replace("\"chance\":0.1", "\"chance\":0"));
        helper.assertTrue(never.roll(new Random(1)).size() == 2, "Zero chance produced an optional item");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void guaranteedCompanions(GameTestHelper helper) throws IOException {
        LootPool pool = load(helper, 3, BUNDLE);
        var random = new Random(18241);
        for (int trial = 0; trial < 100; trial++) {
            var loot = pool.roll(random);
            helper.assertTrue(loot.size() == 6, "Three bundle rolls must produce three weapons and three ammo stacks");
            for (int i = 0; i < loot.size(); i += 2) {
                helper.assertTrue(loot.get(i).is(Items.BOW) && loot.get(i).getCount() == 1, "Weapon missing from bundle");
                helper.assertTrue(loot.get(i + 1).is(Items.ARROW) && loot.get(i + 1).getCount() >= 12
                        && loot.get(i + 1).getCount() <= 24, "Guaranteed ammo missing or outside range");
            }
            loot.get(1).setCount(1);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacySingleItems(GameTestHelper helper) throws IOException {
        var pool = load(helper, 27, "{\"stack\":{\"id\":\"minecraft:bread\",\"count\":1},\"weight\":1,\"minCount\":2,\"maxCount\":2}");
        var loot = pool.roll(new Random(1));
        helper.assertTrue(loot.size() == 27 && loot.stream().allMatch(s -> s.is(Items.BREAD) && s.getCount() == 2),
                "Legacy pools without companions must still support 27 independent selections");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidBundlesAreRejected(GameTestHelper helper) throws IOException {
        try {
            load(helper, 14, BUNDLE);
            throw new IllegalStateException("Accepted bundles that could overflow a 27-slot chest");
        } catch (IOException expected) {
            helper.assertTrue(expected.getMessage().contains("27"), "Overflow error must explain the capacity limit");
        }
        for (String invalid : new String[] {
                BUNDLE.replace("\"maxCount\":24", "\"maxCount\":65"),
                BUNDLE.replace("minecraft:arrow", "hungergames:missing_ammo")
        }) {
            boolean rejected = false;
            try { load(helper, 1, invalid); } catch (IOException expected) { rejected = true; }
            helper.assertTrue(rejected, "Invalid companion stack was accepted");
        }
        helper.succeed();
    }
}
