package dev.hotpotato.mixin;

import dev.hotpotato.HotPotatoMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the Hot Potato un-droppable. This is the drop path behind both the Q key and dragging an
 * item out of the inventory GUI. Targets LivingEntity rather than Player because as of MC 1.21.9
 * the 3-arg drop(ItemStack, boolean, boolean) lives there, so @Mixin(Player.class) cannot find it;
 * the isHotPotato guard keeps that safe for non-player entities.
 */
@Mixin(LivingEntity.class)
public abstract class PlayerDropMixin {

    @Inject(
        method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hotpotato$noDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        if (HotPotatoMod.isHotPotatoStack(stack)) {
            cir.setReturnValue(null); // refuse to drop the hot potato
        }
    }
}
