package za.co.neroland.nerolandcore.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import za.co.neroland.nerolandcore.client.ItemHighlights;

/**
 * Draws the Nero item-highlight border (see {@link ItemHighlights}) at the head of the
 * per-slot draw method, i.e. before the slot's item is drawn, so the border renders
 * beneath the item in every {@code AbstractContainerScreen} on all three loaders.
 *
 * <p>The target differs by Minecraft line, so it is version-gated (a wrong name or
 * descriptor is a critical injection failure that crashes the client at startup):
 * <ul>
 *   <li>26.1+: {@code extractSlot(GuiGraphicsExtractor, Slot, int, int)}</li>
 *   <li>1.21.11: {@code renderSlot(GuiGraphics, Slot, int, int)}</li>
 *   <li>1.21.1-1.21.10: {@code renderSlot(GuiGraphics, Slot)}</li>
 * </ul>
 */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin {

    //? if >=26.1 {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void nerolandcore$extractSlotHighlight(
            GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ItemHighlights.extractSlotHighlight(graphics, slot);
    }
    //?} else if >=1.21.11 {
    /*@Inject(method = "renderSlot", at = @At("HEAD"))
    private void nerolandcore$extractSlotHighlight(
            GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ItemHighlights.extractSlotHighlight(graphics, slot);
    }
    *///?} else {
    /*@Inject(method = "renderSlot", at = @At("HEAD"))
    private void nerolandcore$extractSlotHighlight(GuiGraphicsExtractor graphics, Slot slot, CallbackInfo ci) {
        ItemHighlights.extractSlotHighlight(graphics, slot);
    }
    *///?}
}
