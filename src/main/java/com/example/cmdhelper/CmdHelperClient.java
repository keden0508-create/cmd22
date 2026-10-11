package com.example.cmdhelper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Map;

public class CmdHelperClient implements ClientModInitializer {

    /** 단순 별칭: /이름  →  서버 명령어 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("gmc", "gamemode creative"),
        Map.entry("gms", "gamemode survival"),
        Map.entry("gma", "gamemode adventure"),
        Map.entry("gmsp", "gamemode spectator"),
        Map.entry("day", "time set day"),
        Map.entry("noon", "time set noon"),
        Map.entry("night", "time set night"),
        Map.entry("sun", "weather clear"),
        Map.entry("rain", "weather rain"),
        Map.entry("keepinv", "gamerule keep_inventory true"),
        Map.entry("cll", "clear @s")
    );

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> register(d));
    }

    private void register(CommandDispatcher<FabricClientCommandSource> d) {
        // 1) 단순 별칭
        ALIASES.forEach((name, cmd) ->
            d.register(ClientCommands.literal(name).executes(c -> { run(c.getSource(), cmd); return 1; })));

        // 2) /tpu <칸>  : 위로 N칸 순간이동
        d.register(ClientCommands.literal("tpu")
            .then(ClientCommands.argument("n", IntegerArgumentType.integer())
                .executes(c -> { run(c.getSource(), "tp @s ~ ~" + IntegerArgumentType.getInteger(c, "n") + " ~"); return 1; })));

        // 3) /gi <아이템 [개수]>  : give @s ...
        d.register(ClientCommands.literal("gi")
            .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                .executes(c -> { run(c.getSource(), "give @s " + StringArgumentType.getString(c, "args")); return 1; })));

        // 4) /ke [반경]  주변 몹 제거 (플레이어 제외),  /ki [반경] 주변 아이템 제거
        d.register(ClientCommands.literal("ke")
            .executes(c -> { run(c.getSource(), "kill @e[type=!player,distance=..20]"); return 1; })
            .then(ClientCommands.argument("r", IntegerArgumentType.integer(1))
                .executes(c -> { run(c.getSource(), "kill @e[type=!player,distance=.." + IntegerArgumentType.getInteger(c, "r") + "]"); return 1; })));
        d.register(ClientCommands.literal("ki")
            .executes(c -> { run(c.getSource(), "kill @e[type=item,distance=..30]"); return 1; })
            .then(ClientCommands.argument("r", IntegerArgumentType.integer(1))
                .executes(c -> { run(c.getSource(), "kill @e[type=item,distance=.." + IntegerArgumentType.getInteger(c, "r") + "]"); return 1; })));

        // 5) /here : 현재 좌표·차원 표시
        d.register(ClientCommands.literal("here").executes(c -> {
            Player p = c.getSource().getPlayer();
            BlockPos b = p.blockPosition();
            msg(c.getSource(), "§e좌표: §f" + b.getX() + " " + b.getY() + " " + b.getZ()
                + " §7(" + p.level().dimension().identifier() + ")");
            return 1;
        }));

        // 6) /nbt ...
        d.register(ClientCommands.literal("nbt")
            .then(ClientCommands.literal("explain")
                .then(ClientCommands.argument("tag", StringArgumentType.greedyString())
                    .executes(c -> {
                        String k = StringArgumentType.getString(c, "tag");
                        String e = NbtDict.explain(k);
                        msg(c.getSource(), e == null ? "§c사전에 없는 태그: " + k : "§a" + k + " §f→ " + e);
                        return 1;
                    })))
            .then(ClientCommands.literal("list").executes(c -> {
                NbtDict.TAGS.forEach((k, v) -> msg(c.getSource(), "§7" + k + " §8- §f" + v));
                return 1;
            }))
            .then(ClientCommands.literal("comp")
                .executes(c -> { listComps(c.getSource(), ""); return 1; })
                .then(ClientCommands.argument("q", StringArgumentType.greedyString())
                    .executes(c -> { listComps(c.getSource(), StringArgumentType.getString(c, "q")); return 1; })))
            .then(ClientCommands.literal("hand").executes(c -> { explainHand(c.getSource()); return 1; }))
            .then(ClientCommands.literal("entity").executes(c -> {
                Entity e = Minecraft.getInstance().crosshairPickEntity;
                if (e == null) { msg(c.getSource(), "§c조준 중인 엔티티가 없습니다."); return 0; }
                run(c.getSource(), "data get entity " + e.getStringUUID());
                return 1;
            }))
            .then(ClientCommands.literal("block").executes(c -> {
                HitResult h = Minecraft.getInstance().hitResult;
                if (!(h instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) {
                    msg(c.getSource(), "§c조준 중인 블록이 없습니다."); return 0;
                }
                BlockPos p = bh.getBlockPos();
                run(c.getSource(), "data get block " + p.getX() + " " + p.getY() + " " + p.getZ());
                return 1;
            })));
    }

    private void explainHand(FabricClientCommandSource src) {
        Player p = src.getPlayer();
        ItemStack stack = p.getMainHandItem();
        if (stack.isEmpty()) { msg(src, "§c손에 든 아이템이 없습니다."); return; }

        var ops = p.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag tag = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        if (!(tag instanceof CompoundTag root)) { msg(src, "§c직렬화 실패"); return; }

        msg(src, "§6── 손에 든 아이템 ──");
        for (String top : root.keySet()) {
            if (top.equals("components")) continue;
            msg(src, "§a" + top + " §f= " + root.get(top) + explainSuffix(top));
        }
        root.getCompound("components").ifPresentOrElse(comps -> {
            if (comps.keySet().isEmpty()) msg(src, "§7(추가 컴포넌트 없음)");
            for (String k : comps.keySet()) {
                msg(src, "§b" + k + " §f= " + comps.get(k) + explainSuffix(k));
            }
        }, () -> msg(src, "§7(추가 컴포넌트 없음)"));
    }

    private void listComps(FabricClientCommandSource src, String q) {
        String f = q.toLowerCase();
        int[] n = {0};
        NbtDict.TAGS.forEach((k, v) -> {
            if (!k.startsWith("minecraft:")) return;
            if (!f.isEmpty() && !k.contains(f) && !v.contains(q)) return;
            n[0]++;
            msg(src, "§b" + k + " §8- §f" + v);
        });
        if (n[0] == 0) msg(src, "§c일치하는 구성요소가 없습니다: " + q);
    }

    private static String explainSuffix(String key) {
        String e = NbtDict.explain(key);
        return e == null ? "" : "\n  §8↳ §7" + e;
    }

    private static void run(FabricClientCommandSource src, String cmd) {
        src.getClient().getConnection().sendCommand(cmd);
    }

    private static void msg(FabricClientCommandSource src, String s) {
        src.sendFeedback(Component.literal(s));
    }
}
