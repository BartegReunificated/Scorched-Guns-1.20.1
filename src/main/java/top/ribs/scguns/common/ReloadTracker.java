package top.ribs.scguns.common;

import com.mrcrayfish.framework.api.network.LevelLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.ribs.scguns.Config;
import top.ribs.scguns.Reference;
import top.ribs.scguns.attributes.SCAttributes;
import top.ribs.scguns.init.ModSyncedDataKeys;
import top.ribs.scguns.item.AmmoBoxItem;
import top.ribs.scguns.item.GunItem;
import top.ribs.scguns.item.ammo_boxes.CreativeAmmoBoxItem;
import top.ribs.scguns.network.PacketHandler;
import top.ribs.scguns.network.message.S2CMessageGunSound;
import top.ribs.scguns.util.GunEnchantmentHelper;
import top.ribs.scguns.util.GunModifierHelper;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * Author: MrCrayfish
 */
@SuppressWarnings("unused")
@Mod.EventBusSubscriber(modid = Reference.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ReloadTracker {
    private static final Map<Player, ReloadTracker> RELOAD_TRACKER_MAP = new WeakHashMap<>();

    private final int startTick;
    private final int slot;
    private final ItemStack stack;
    private final Gun gun;
    private int currentBulletReloadTick = 0;
    private boolean initialReload = true;

    private ReloadTracker(Player player) {
        this.startTick = player.tickCount;
        this.slot = player.getInventory().selected;
        this.stack = player.getInventory().getSelected();
        this.gun = ((GunItem) stack.getItem()).getModifiedGun(stack);
    }

    private boolean isSameWeapon(Player player) {
        return !this.stack.isEmpty() && player.getInventory().selected == this.slot && player.getInventory().getSelected() == this.stack;
    }

    private boolean isWeaponFull() {
        CompoundTag tag = this.stack.getOrCreateTag();
        return tag.getInt("AmmoCount") >= GunModifierHelper.getModifiedAmmoCapacity(this.stack, this.gun);
    }

    private boolean isWeaponEmpty() {
        CompoundTag tag = this.stack.getOrCreateTag();
        return tag.getInt("AmmoCount") == 0;
    }

    private boolean hasNoAmmo(Player player) {
        if (gun.getReloads().getReloadType() == ReloadType.SINGLE_ITEM) {
            return Gun.findAmmo(player, this.gun.getReloads().getReloadItem()).stack().isEmpty();
        }
        return Gun.findAmmo(player, this.gun.getProjectile().getItem()).stack().isEmpty();
    }

    private boolean canReload(Player player) {
        int deltaTicks = player.tickCount - this.startTick;
        //Get the shooters RELOAD_SPEED attribute.
        double reloadSpeed = player.getAttribute(SCAttributes.RELOAD_SPEED.get()).getValue();
        if (gun.getReloads().getReloadType() == ReloadType.MANUAL) {
            if (this.initialReload) {
                this.initialReload = false;
                this.currentBulletReloadTick = GunEnchantmentHelper.getReloadInterval(this.stack);
                //Apply the reload speed modifier using ceil here so there is no 0-tick reloads.
                this.currentBulletReloadTick = (int) Math.ceil((double)currentBulletReloadTick/reloadSpeed);
                return false;
            } else if (currentBulletReloadTick <= 0) {
                currentBulletReloadTick = GunEnchantmentHelper.getReloadInterval(this.stack);
                return true;
            } else {
                currentBulletReloadTick -= 1;
                return false;
            }
        } else {
            int interval = (gun.getReloads().getReloadType() == ReloadType.MAG_FED || gun.getReloads().getReloadType() == ReloadType.SINGLE_ITEM) ?
                    (int) Math.ceil((double)GunEnchantmentHelper.getMagReloadSpeed(this.stack)/reloadSpeed) :
                    (int) Math.ceil((double)GunEnchantmentHelper.getReloadInterval(this.stack)/reloadSpeed);
            return deltaTicks >= interval;
        }
    }

    public static int ammoInInventory(ItemStack[] ammoStack) {
        int result = 0;
        for (ItemStack x : ammoStack) {
            result += x.getCount();
        }
        return result;
    }

    private void shrinkFromAmmoPool(ItemStack[] ammoStack, Player player, int shrinkAmount) {
        final int[] shrinkAmt = {shrinkAmount};
        for (ItemStack itemStack : player.getInventory().items) {
            if (itemStack.getItem() instanceof CreativeAmmoBoxItem) {
                return;
            }
        }
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            IItemHandlerModifiable curios = handler.getEquippedCurios();
            for (int i = 0; i < curios.getSlots(); i++) {
                ItemStack stack = curios.getStackInSlot(i);
                if (stack.getItem() instanceof AmmoBoxItem) {
                    List<ItemStack> contents = AmmoBoxItem.getContents(stack).collect(Collectors.toList());
                    for (ItemStack pouchAmmoStack : contents) {
                        if (!pouchAmmoStack.isEmpty() && pouchAmmoStack.getItem() == gun.getProjectile().getItem()) {
                            int max = Math.min(shrinkAmt[0], pouchAmmoStack.getCount());
                            pouchAmmoStack.shrink(max);
                            shrinkAmt[0] -= max;
                            if (shrinkAmt[0] == 0) {
                                updateAmmoPouchContents(stack, contents);
                                return;
                            }
                        }
                    }
                    updateAmmoPouchContents(stack, contents);
                }
            }
        });

        // Shrink from inventory
        for (ItemStack itemStack : player.getInventory().items) {
            if (itemStack.getItem() instanceof AmmoBoxItem) {
                List<ItemStack> contents = AmmoBoxItem.getContents(itemStack).collect(Collectors.toList());
                for (ItemStack pouchAmmoStack : contents) {
                    if (!pouchAmmoStack.isEmpty() && pouchAmmoStack.getItem() == gun.getProjectile().getItem()) {
                        int max = Math.min(shrinkAmt[0], pouchAmmoStack.getCount());
                        pouchAmmoStack.shrink(max);
                        shrinkAmt[0] -= max;
                        if (shrinkAmt[0] == 0) {
                            updateAmmoPouchContents(itemStack, contents);
                            return;
                        }
                    }
                }
                updateAmmoPouchContents(itemStack, contents);
            }
        }

        // Shrink from direct ammo stacks
        for (ItemStack x : ammoStack) {
            if (!x.isEmpty()) {
                int max = Math.min(shrinkAmt[0], x.getCount());
                x.shrink(max);
                shrinkAmt[0] -= max;
                if (shrinkAmt[0] == 0) {
                    return;
                }
            }
        }
    }

    private void updateAmmoPouchContents(ItemStack ammoPouch, List<ItemStack> contents) {
        ListTag listTag = new ListTag();
        for (ItemStack stack : contents) {
            CompoundTag itemTag = new CompoundTag();
            stack.save(itemTag);
            listTag.add(itemTag);
        }
        ammoPouch.getOrCreateTag().put(AmmoBoxItem.TAG_ITEMS, listTag);
    }

    private void increaseMagAmmo(Player player) {
        ItemStack[] ammoStack = Gun.findAmmoStack(player, this.gun.getProjectile().getItem());
        boolean hasCreativeAmmoBox = player.getInventory().items.stream()
                .anyMatch(itemStack -> itemStack.getItem() instanceof CreativeAmmoBoxItem);
        if (hasCreativeAmmoBox) {
            CompoundTag tag = this.stack.getTag();
            if (tag != null) {
                int maxAmmo = GunModifierHelper.getModifiedAmmoCapacity(this.stack, this.gun);
                tag.putInt("AmmoCount", maxAmmo);
            }

            playReloadSound(player);
            return;
        }
        if (ammoStack.length > 0) {
            CompoundTag tag = this.stack.getTag();
            int ammoAmount = Math.min(ammoInInventory(ammoStack), GunModifierHelper.getModifiedAmmoCapacity(this.stack, this.gun));
            assert tag != null;
            int currentAmmo = tag.getInt("AmmoCount");
            int maxAmmo = GunModifierHelper.getModifiedAmmoCapacity(this.stack, this.gun);
            int amount = maxAmmo - currentAmmo;
            if (ammoAmount < amount) {
                tag.putInt("AmmoCount", currentAmmo + ammoAmount);
                this.shrinkFromAmmoPool(ammoStack, player, ammoAmount);
            } else {
                tag.putInt("AmmoCount", maxAmmo);
                this.shrinkFromAmmoPool(ammoStack, player, amount);
            }
        }

        playReloadSound(player);
    }


    private void reloadItem(Player player) {
        AmmoContext context = Gun.findAmmo(player, this.gun.getReloads().getReloadItem());
        ItemStack ammo = context.stack();
        if (!ammo.isEmpty()) {
            CompoundTag tag = this.stack.getTag();
            if (tag != null) {
                int maxAmmo = GunModifierHelper.getModifiedAmmoCapacity(this.stack, this.gun);
                int currentAmmo = tag.getInt("AmmoCount");

                if (currentAmmo < maxAmmo) {
                    tag.putInt("AmmoCount", maxAmmo);
                    ammo.shrink(1);

                    Container container = context.container();
                    if (container != null) {
                        container.setChanged();
                    }
                }
            }

            playReloadSound(player);
        }
    }

    private void increaseAmmo(Player player) {
        AmmoContext context = Gun.findAmmo(player, this.gun.getProjectile().getItem());
        ItemStack ammo = context.stack();
        if (!ammo.isEmpty()) {
            int amount = Math.min(ammo.getCount(), this.gun.getReloads().getReloadAmount());
            CompoundTag tag = this.stack.getTag();
            if (tag != null) {
                int maxAmmo = GunModifierHelper.getModifiedAmmoCapacity(this.stack, this.gun);
                amount = Math.min(amount, maxAmmo - tag.getInt("AmmoCount"));
                tag.putInt("AmmoCount", tag.getInt("AmmoCount") + amount);
            }
            shrinkFromAmmoPool(Gun.findAmmoStack(player, this.gun.getProjectile().getItem()), player, amount);
        }

        playReloadSound(player);
    }

    private void playReloadSound(Player player) {
        ResourceLocation reloadSound = this.gun.getSounds().getReload();
        if (reloadSound != null) {
            double radius = Config.SERVER.reloadMaxDistance.get();
            double soundX = player.getX();
            double soundY = player.getY() + 1.0;
            double soundZ = player.getZ();
            S2CMessageGunSound message = new S2CMessageGunSound(reloadSound, SoundSource.PLAYERS, (float) soundX, (float) soundY, (float) soundZ, 1.0F, 1.0F, player.getId(), false, true);
            PacketHandler.getPlayChannel().sendToNearbyPlayers(() -> LevelLocation.create(player.level(), soundX, soundY, soundZ, radius), message);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.START && !event.player.level().isClientSide) {
            Player player = event.player;

            if (ModSyncedDataKeys.RELOADING.getValue(player)) {
                if (!RELOAD_TRACKER_MAP.containsKey(player)) {
                    if (!(player.getInventory().getSelected().getItem() instanceof GunItem)) {
                        ModSyncedDataKeys.RELOADING.setValue(player, false);
                        return;
                    }
                    ReloadTracker tracker = new ReloadTracker(player);

                    // Check if player can actually reload before playing sound
                    if (tracker.hasNoAmmo(player) || tracker.isWeaponFull()) {
                        ModSyncedDataKeys.RELOADING.setValue(player, false);
                        return;
                    }

                    RELOAD_TRACKER_MAP.put(player, tracker);

                    // Play pre-reload sound if it exists
                    ResourceLocation preReloadSound = tracker.gun.getSounds().getPreReload();
                    if (preReloadSound != null) {
                        double soundX = player.getX();
                        double soundY = player.getY() + 1.0;
                        double soundZ = player.getZ();
                        double radius = Config.SERVER.reloadMaxDistance.get();
                        S2CMessageGunSound message = new S2CMessageGunSound(preReloadSound, SoundSource.PLAYERS,
                                (float) soundX, (float) soundY, (float) soundZ, 1.0F, 1.0F, player.getId(), false, true);
                        PacketHandler.getPlayChannel().sendToNearbyPlayers(
                                () -> LevelLocation.create(player.level(), soundX, soundY, soundZ, radius), message);
                    }
                }
                ReloadTracker tracker = RELOAD_TRACKER_MAP.get(player);
                if (!tracker.isSameWeapon(player) || tracker.isWeaponFull() || tracker.hasNoAmmo(player)) {
                    RELOAD_TRACKER_MAP.remove(player);
                    ModSyncedDataKeys.RELOADING.setValue(player, false);
                    return;
                }
                if (tracker.canReload(player)) {
                    final Player finalPlayer = player;
                    final Gun gun = tracker.gun;
                    if (gun.getReloads().getReloadType() == ReloadType.MAG_FED) {
                        tracker.increaseMagAmmo(player);
                    } else if (gun.getReloads().getReloadType() == ReloadType.MANUAL) {
                        tracker.increaseAmmo(player);
                    } else if (gun.getReloads().getReloadType() == ReloadType.SINGLE_ITEM) {
                        tracker.reloadItem(player);

                        // Handle byproduct if it exists
                        Item byproduct = gun.getReloads().getReloadByproduct();
                        if (byproduct != null) {
                            ItemStack byproductStack = new ItemStack(byproduct);
                            if (!player.getInventory().add(byproductStack)) {
                                player.drop(byproductStack, false);
                            }
                        }
                    }
                    if (tracker.isWeaponFull() || tracker.hasNoAmmo(player)) {
                        RELOAD_TRACKER_MAP.remove(player);
                        ModSyncedDataKeys.RELOADING.setValue(player, false);

                        DelayedTask.runAfter(4, () -> {
                            ResourceLocation cockSound = gun.getSounds().getCock();
                            if (cockSound != null && finalPlayer.isAlive()) {
                                double soundX = finalPlayer.getX();
                                double soundY = finalPlayer.getY() + 1.0;
                                double soundZ = finalPlayer.getZ();
                                double radius = Config.SERVER.reloadMaxDistance.get();
                                S2CMessageGunSound messageSound = new S2CMessageGunSound(cockSound, SoundSource.PLAYERS,
                                        (float) soundX, (float) soundY, (float) soundZ, 1.0F, 1.0F, finalPlayer.getId(), false, true);
                                PacketHandler.getPlayChannel().sendToNearbyPlayers(
                                        () -> LevelLocation.create(finalPlayer.level(), soundX, soundY, soundZ, radius), messageSound);
                            }
                        });
                    }
                }
            } else {
                RELOAD_TRACKER_MAP.remove(player);
            }
        }
    }

}
