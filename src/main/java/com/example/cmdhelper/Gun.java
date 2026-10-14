package com.example.cmdhelper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/** AKM: 네더라이트 괭이(이름 "AKM")를 들고 우클릭 누르고 있으면 연사. 맞으면 한 방. */
public final class Gun {
    private static final Random RNG = new Random();
    private static int cooldown = 0;
    private static int rate = 2;            // 발사 간격(틱). 2틱 = 초당 10발
    private static boolean publicMode = false;  // 다른 사람도 보이게 서버 particle 명령 사용
    private static boolean killMode = false;    // false: damage 명령, true: kill 명령
    private static boolean recoil = true;

    private Gun() {}

    static boolean isAkm(ItemStack s) {
        return s != null && s.is(Items.NETHERITE_HOE) && s.getHoverName().getString().equals("AKM");
    }

    static void init() {
        // 땅 갈아엎기(괭이 기본 동작) 방지
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
            isAkm(player.getItemInHand(hand)) ? InteractionResult.FAIL : InteractionResult.PASS);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (cooldown > 0) cooldown--;
            if (mc.player == null || mc.level == null || mc.screen != null) return;
            if (!mc.options.keyUse.isDown()) return;
            if (!isAkm(mc.player.getMainHandItem())) return;
            if (cooldown > 0) return;
            cooldown = publicMode ? Math.max(rate, 4) : rate;
            fire(mc);
        });
    }

    private static void fire(Minecraft mc) {
        Player p = mc.player;
        Level lv = mc.level;
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double range = 120;
        Vec3 far = eye.add(look.scale(range));

        // 1) 블록에 막히는 거리
        BlockHitResult bhr = lv.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double maxDist = bhr.getType() == HitResult.Type.MISS ? range : bhr.getLocation().distanceTo(eye);
        Vec3 stop = eye.add(look.scale(maxDist));

        // 2) 그 안에서 맞는 가장 가까운 생물
        Entity target = null;
        double best = maxDist;
        for (Entity e : lv.getEntities(p, new AABB(eye, stop).inflate(1.0),
                x -> x instanceof LivingEntity && x.isAlive() && !x.isSpectator())) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.1).clip(eye, stop);
            if (hit.isPresent()) {
                double dd = hit.get().distanceTo(eye);
                if (dd < best) { best = dd; target = e; }
            }
        }
        double dist = target != null ? best : maxDist;
        Vec3 end = eye.add(look.scale(dist));

        // 3) 총알 궤적 파티클 (총구 → 도착점)
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 muzzle = eye.add(look.scale(1.0)).add(right.scale(0.25)).add(0, -0.2, 0);
        Vec3 dir = end.subtract(muzzle);
        double len = dir.length();
        if (len > 0.1) {
            Vec3 step = dir.normalize();
            int n = (int) Math.min(150, len / 0.5);
            for (int i = 0; i <= n; i++) {
                Vec3 q = muzzle.add(step.scale(i * 0.5));
                lv.addParticle(ParticleTypes.END_ROD, q.x, q.y, q.z, 0, 0, 0);
            }
            if (publicMode) {
                int k = 8;
                for (int i = 0; i <= k; i++) {
                    Vec3 q = muzzle.add(dir.scale(i / (double) k));
                    send(mc, String.format(Locale.ROOT, "particle minecraft:end_rod %.2f %.2f %.2f 0 0 0 0 1 force", q.x, q.y, q.z));
                }
            }
        }
        // 착탄 불꽃
        for (int i = 0; i < 6; i++) {
            lv.addParticle(ParticleTypes.CRIT, end.x, end.y, end.z,
                (RNG.nextDouble() - 0.5) * 0.4, (RNG.nextDouble() - 0.5) * 0.4, (RNG.nextDouble() - 0.5) * 0.4);
        }

        // 4) 소리 + 반동
        lv.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.6f, 1.8f, false);
        if (recoil) {
            p.setXRot(p.getXRot() - 0.7f);
            p.setYRot(p.getYRot() + (RNG.nextFloat() - 0.5f) * 0.4f);
        }

        // 5) 한 방: 서버 명령으로 처리 (OP 필요)
        if (target != null) {
            String id = target.getStringUUID();
            send(mc, killMode ? "kill " + id
                              : "damage " + id + " 1000000 minecraft:player_attack by @s");
            lv.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.5f, false);
        }
    }

    private static void send(Minecraft mc, String cmd) {
        if (mc.getConnection() != null) mc.getConnection().sendCommand(cmd);
    }

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommands.literal("akm")
            .executes(c -> { status(c.getSource()); return 1; })
            .then(ClientCommands.literal("give").executes(c -> {
                Extras.run(c.getSource(), "give @s netherite_hoe["
                    + "custom_name={text:'AKM',color:'gold',bold:true,italic:false},"
                    + "lore=[{text:'우클릭 꾹 누르면 연사 · 한 방 즉사',color:'gray',italic:false},"
                    + "{text:'cmdhelper 모드 필요',color:'dark_gray',italic:false}],"
                    + "unbreakable={},enchantment_glint_override=true]");
                Extras.msg(c.getSource(), "§aAKM 지급! 들고 우클릭을 꾹 누르세요.");
                return 1;
            }))
            .then(ClientCommands.literal("public").executes(c -> {
                publicMode = !publicMode;
                Extras.msg(c.getSource(), publicMode
                    ? "§a공개 모드 ON: 다른 사람에게도 총알 파티클이 보입니다 (OP 필요, 연사 속도 제한)"
                    : "§7공개 모드 OFF: 나한테만 보입니다");
                return 1;
            }))
            .then(ClientCommands.literal("mode")
                .then(ClientCommands.literal("damage").executes(c -> { killMode = false; Extras.msg(c.getSource(), "§e피해 모드: damage 명령 (크리에이티브 대상은 무적)"); return 1; }))
                .then(ClientCommands.literal("kill").executes(c -> { killMode = true; Extras.msg(c.getSource(), "§e즉사 모드: kill 명령 (크리에이티브 대상도 처치)"); return 1; })))
            .then(ClientCommands.literal("rate")
                .then(ClientCommands.argument("t", IntegerArgumentType.integer(1, 20)).executes(c -> {
                    rate = IntegerArgumentType.getInteger(c, "t");
                    Extras.msg(c.getSource(), "§e발사 간격 " + rate + "틱 (초당 " + String.format(Locale.ROOT, "%.1f", 20.0 / rate) + "발)");
                    return 1;
                })))
            .then(ClientCommands.literal("recoil").executes(c -> {
                recoil = !recoil;
                Extras.msg(c.getSource(), recoil ? "§a반동 ON" : "§7반동 OFF");
                return 1;
            })));
    }

    private static void status(FabricClientCommandSource s) {
        Extras.msg(s, "§6[AKM] §f/akm give : 총 받기");
        Extras.msg(s, "§7공개모드 " + (publicMode ? "ON" : "OFF") + " · 판정 " + (killMode ? "kill" : "damage")
            + " · 간격 " + rate + "틱 · 반동 " + (recoil ? "ON" : "OFF"));
        Extras.msg(s, "§7/akm public · /akm mode kill|damage · /akm rate 1~20 · /akm recoil");
    }
}
