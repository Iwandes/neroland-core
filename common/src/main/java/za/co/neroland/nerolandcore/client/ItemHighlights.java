package za.co.neroland.nerolandcore.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import za.co.neroland.nerolandcore.config.CoreConfig;

/**
 * Client-side slot highlighting for Nero ecosystem items — a subtle coloured
 * border drawn just inside an inventory slot, underneath the item, with the
 * colour keyed to <i>what the item is</i> (category), not which mod ships it.
 *
 * <p>Membership is declared purely by hand-authored {@code neroland:highlight/*}
 * item tags (see {@code data/neroland/tags/item/highlight/}), consistent with the
 * meteor {@code neroland:meteor/grindable} precedent: Core ships its own entries
 * with {@code required:false}, and every downstream Nero mod adds its items to the
 * same tags from its own datapack. Datapacks can therefore retune membership with
 * no code involved. Categories, first match wins:
 *
 * <ol>
 *   <li>{@code neroland:highlight/machines} — machines and functional blocks (amber)</li>
 *   <li>{@code neroland:highlight/tools} — tools, weapons and wearable gear (violet)</li>
 *   <li>{@code neroland:highlight/upgrades} — upgrade modules and augments (green)</li>
 *   <li>{@code neroland:highlight/materials} — crafting materials and their forms (teal)</li>
 * </ol>
 *
 * <p>Rendering is hooked from a single common client mixin at the head of
 * the per-slot draw method ({@code extractSlot} on 26.x, {@code renderSlot} on
 * 1.21.x), so the border appears in every
 * container screen (vanilla and modded) on all three loaders and is drawn before
 * — i.e. beneath — the item itself. Both knobs are local-only client config:
 * {@code itemHighlightsEnabled} and {@code itemHighlightOpacity}.
 */
public final class ItemHighlights {

    /** {@code neroland:highlight/machines} — machines and functional blocks. */
    public static final TagKey<Item> MACHINES = tag("highlight/machines");
    /** {@code neroland:highlight/tools} — tools, weapons and wearable gear. */
    public static final TagKey<Item> TOOLS = tag("highlight/tools");
    /** {@code neroland:highlight/upgrades} — upgrade modules and augments. */
    public static final TagKey<Item> UPGRADES = tag("highlight/upgrades");
    /** {@code neroland:highlight/materials} — crafting materials and their forms. */
    public static final TagKey<Item> MATERIALS = tag("highlight/materials");

    // Category colours (RGB, no alpha — alpha comes from the opacity config).
    private static final int MACHINES_RGB = 0xFFB74D;  // amber
    private static final int TOOLS_RGB = 0xB388FF;     // violet
    private static final int UPGRADES_RGB = 0x69F0AE;  // green
    private static final int MATERIALS_RGB = 0x4DD0E1; // teal

    private static final int NONE = -1;

    private ItemHighlights() {
    }

    /**
     * Draws the highlight border for one slot, if its item is in a
     * {@code neroland:highlight/*} tag. Called from the {@code extractSlot} mixin
     * before the item is drawn, so the border sits beneath the item. The border is
     * a frame just inside the 16×16 slot ({@code itemHighlightThickness} pixels),
     * drawn as concentric one-pixel rings that fade inwards — a soft glow around
     * the slot edge — and fade towards the top (bottom edge strongest).
     */
    public static void extractSlotHighlight(GuiGraphicsExtractor graphics, Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !CoreConfig.ITEM_HIGHLIGHTS_ENABLED.get()) {
            return;
        }
        int rgb = colorOf(stack);
        if (rgb == NONE) {
            return;
        }
        int opacity = CoreConfig.ITEM_HIGHLIGHT_OPACITY.get();
        if (opacity <= 0) {
            return;
        }
        int alphaBottom = Math.min(255, Math.round(opacity * 2.55F));
        int alphaTop = alphaBottom / 2;
        int thickness = CoreConfig.ITEM_HIGHLIGHT_THICKNESS.get();

        int x = slot.x;
        int y = slot.y;
        for (int i = 0; i < thickness; i++) {
            float fade = (thickness - i) / (float) thickness;
            int aTop = Math.round(alphaTop * fade);
            int aBottom = Math.round(alphaBottom * fade);
            if (aBottom <= 0) {
                break;
            }
            int top = (aTop << 24) | rgb;
            int bottom = (aBottom << 24) | rgb;
            int x0 = x + i;
            int y0 = y + i;
            int x1 = x + 16 - i;
            int y1 = y + 16 - i;
            graphics.fillGradient(x0, y0, x0 + 1, y1, top, bottom);     // left
            graphics.fillGradient(x1 - 1, y0, x1, y1, top, bottom);     // right
            graphics.fill(x0 + 1, y0, x1 - 1, y0 + 1, top);             // top
            graphics.fill(x0 + 1, y1 - 1, x1 - 1, y1, bottom);          // bottom
        }
    }

    /** Category colour for a stack, or {@link #NONE}; most specific tag wins. */
    private static int colorOf(ItemStack stack) {
        if (stack.is(MACHINES)) {
            return MACHINES_RGB;
        }
        if (stack.is(TOOLS)) {
            return TOOLS_RGB;
        }
        if (stack.is(UPGRADES)) {
            return UPGRADES_RGB;
        }
        if (stack.is(MATERIALS)) {
            return MATERIALS_RGB;
        }
        return NONE;
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("neroland", path));
    }
}
