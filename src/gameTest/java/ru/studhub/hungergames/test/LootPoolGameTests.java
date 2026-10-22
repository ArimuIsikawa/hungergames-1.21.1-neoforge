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
    public static void deployedRewardPreset(GameTestHelper helper) throws IOException {
        if (!net.neoforged.fml.ModList.get().isLoaded("tacz")) { helper.succeed(); return; }
        var path = java.nio.file.Path.of("../../config-presets/tacz/rewards.json");
        var pool = LootPool.load(path, helper.getLevel().registryAccess());
        for (int i = 0; i < 100; i++) {
            var reward = pool.roll(new Random(i));
            helper.assertTrue(reward.size() == 13, "Complete preset must award 13 stacks with doubled ammunition");
            for (var item : List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.ENCHANTED_GOLDEN_APPLE)) {
                helper.assertTrue(reward.stream().anyMatch(s -> s.is(item)), "Missing guaranteed resource: " + item);
            }
            long guns = reward.stream().filter(s -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("tacz:modern_kinetic_gun")).count();
            long ammo = reward.stream().filter(s -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("tacz:ammo")).count();
            helper.assertTrue(guns == 1 && ammo == 4, "Missing weapon and doubled ammunition");
            for (String suffix : List.of("helmet", "chestplate", "leggings", "boots")) {
                helper.assertTrue(reward.stream().filter(s -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_" + suffix)).count() == 1,
                        "Missing armor slot: " + suffix);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void oneWeaponPerChest(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("tacz")) { helper.succeed(); return; }
        var pool = LootPool.load(java.nio.file.Path.of("../../config-presets/tacz/chest_loot.json"), helper.getLevel().registryAccess());
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
            helper.assertTrue(entry.companions().size() == 2, "Each gun must have two ammunition stacks");
            helper.assertTrue(entry.optionalCompanions().size() == 1
                    && entry.optionalCompanions().getFirst().chance() == .1, "Attachment chance must be 10% per gun");
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
            }).count() == 2, "Chest must contain two matching ammunition stacks");
            helper.assertTrue(guns == 1 && attachments <= 1 && loot.size() >= 6 && loot.size() <= 10,
                    "Chest must contain one gun, doubled ammo and at most one attachment");
            withAttachment += attachments;
        }
        helper.assertTrue(withAttachment >= 350 && withAttachment <= 650, "Unexpected attachment frequency: " + withAttachment);
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
