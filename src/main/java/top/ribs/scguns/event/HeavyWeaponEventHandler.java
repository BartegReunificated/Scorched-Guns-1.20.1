package top.ribs.scguns.event;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.ribs.scguns.init.ModItems;
import top.ribs.scguns.init.ModTags;

import java.util.Objects;

@Mod.EventBusSubscriber(modid = "scguns")
public class HeavyWeaponEventHandler {

    private static final int HEAVY_WEAPON_SLOWNESS_AMPLIFIER = 0;
    private static final int HEAVY_WEAPON_SLOWNESS_DURATION = 40;

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            updateSlowness(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof Player player) {
            updateSlowness(player);
        }
    }

    private static void updateSlowness(Player player) {
        ItemStack mainHandItem = player.getMainHandItem();
        ItemStack offHandItem = player.getOffhandItem();

        boolean holdingSpecialItem = isHeavyWeapon(mainHandItem) || isHeavyWeapon(offHandItem);
        MobEffectInstance currentSlowness = player.getEffect(MobEffects.MOVEMENT_SLOWDOWN);

        if (holdingSpecialItem) {
            if (currentSlowness == null || currentSlowness.getAmplifier() < HEAVY_WEAPON_SLOWNESS_AMPLIFIER) {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, HEAVY_WEAPON_SLOWNESS_DURATION, HEAVY_WEAPON_SLOWNESS_AMPLIFIER, false, false, true));
            } else if (currentSlowness.getDuration() <= 30) {
                // Refresh the duration of the existing effect if it's about to expire
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, HEAVY_WEAPON_SLOWNESS_DURATION, currentSlowness.getAmplifier(), false, false, true));
            }
        }
    }

    private static boolean isHeavyWeapon(ItemStack itemStack) {
        return !itemStack.isEmpty() && itemStack.is(ModTags.Items.HEAVY_WEAPON);
    }
}