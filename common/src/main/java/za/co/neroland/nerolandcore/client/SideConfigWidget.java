package za.co.neroland.nerolandcore.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.network.SideConfigIntentPayload;
import za.co.neroland.nerolandcore.platform.Services;
import za.co.neroland.nerolandcore.sideconfig.Channel;
import za.co.neroland.nerolandcore.sideconfig.ChannelConfig;
import za.co.neroland.nerolandcore.sideconfig.ClientSideConfig;
import za.co.neroland.nerolandcore.sideconfig.RelativeFace;
import za.co.neroland.nerolandcore.sideconfig.SideConfig;
import za.co.neroland.nerolandcore.sideconfig.SideConfigClipboard;
import za.co.neroland.nerolandcore.sideconfig.SideMode;
import za.co.neroland.nerolandcore.sideconfig.SideModeColors;

/**
 * The reusable Side Config tab: a labelled, flattened cube/net of the six relative
 * faces a player clicks to route each channel, with per-channel sub-tabs, a mode
 * legend (which doubles as the mode palette), and word-labelled auto-eject /
 * auto-input / reset / copy / paste controls. Colour-coded by {@link SideModeColors}.
 *
 * <p>Self-contained and composable: a downstream {@code AbstractContainerScreen}
 * constructs one (it sends a snapshot request on open), forwards {@link #render}
 * from its draw pass and {@link #mouseClicked} from its handler. Server-authoritative:
 * clicks send {@link SideConfigIntentPayload} intents; the authoritative snapshot
 * streams back via {@link ClientSideConfig} and is applied here.
 *
 * <p>Interaction on a face: left-click cycles its mode, right-click disables it,
 * middle-click opens a mode palette (shown in the legend column).
 */
public final class SideConfigWidget {

    private static final int CELL = 16;
    private static final int STEP = 18;
    private static final int HEADER_H = 14;
    private static final int PANEL_W = 142;

    private static final int PANEL = 0xF018181E;
    private static final int HEADER = 0xFF2C2C36;
    private static final int BORDER = 0xFF000000;
    private static final int OUTLINE = 0xFF54545E;
    private static final int TEXT = 0xFFE6E6F0;
    private static final int SUBTLE = 0xFF9090A0;
    private static final int TAB_CLOSED = 0xFF3CB043;
    private static final int BTN = 0xFF3A3A46;
    private static final int BTN_ON = 0xFF3CB043;

    private final BlockPos pos;
    private final SideConfig config;
    private final String typeKey;
    private final int anchorX;
    private final int anchorY;

    private boolean open;
    private Channel activeChannel;
    @Nullable
    private RelativeFace paletteFace;

    public SideConfigWidget(BlockPos pos, SideConfig config, String typeKey, int anchorX, int anchorY) {
        this.pos = pos.immutable();
        this.config = config;
        this.typeKey = typeKey;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.activeChannel = config.channels().keySet().iterator().hasNext()
                ? config.channels().keySet().iterator().next()
                : Channel.ENERGY;
        Services.NETWORK.sendToServer(SideConfigIntentPayload.request(this.pos));
    }

    private void applyPending() {
        Map<Channel, Integer> packed = ClientSideConfig.poll(pos);
        if (packed != null) {
            config.applyPacked(packed);
        }
    }

    public boolean isOpen() {
        return open;
    }

    // --- layout (shared by render + click so they never drift) --------------

    private Layout layout(int guiLeft, int guiTop) {
        Layout l = new Layout();
        l.px = guiLeft + anchorX;
        l.py = guiTop + anchorY;
        l.channels = new ArrayList<>(config.channels().keySet());
        if (!open) {
            return l;
        }
        int contentTop = l.py + HEADER_H + 3;
        l.tabsY = contentTop;
        boolean multi = config.isMultiChannel();
        l.netX = l.px + 6;
        l.netY = (multi ? contentTop + 14 + 3 : contentTop) + 2;
        l.colX = l.netX + 4 * STEP + 8;
        int netBottom = l.netY + 3 * STEP;
        l.ctrlY1 = netBottom + 5;
        l.ctrlY2 = l.ctrlY1 + 16;
        l.hintY = l.ctrlY2 + 16;
        l.panelBottom = l.hintY + 11;
        return l;
    }

    private static final class Layout {
        int px;
        int py;
        List<Channel> channels;
        int tabsY;
        int netX;
        int netY;
        int colX;
        int ctrlY1;
        int ctrlY2;
        int hintY;
        int panelBottom;
    }

    // --- rendering ----------------------------------------------------------

    public void render(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY) {
        applyPending();
        Font font = Minecraft.getInstance().font;
        Layout l = layout(guiLeft, guiTop);

        if (!open) {
            button(g, l.px, l.py, CELL, CELL, TAB_CLOSED);
            center(g, font, Component.translatable("gui.nerolandcore.side_config.tab"), l.px + CELL / 2, l.py + 4, 0xFF0A0A0A);
            return;
        }

        // Panel + header.
        g.fill(l.px, l.py, l.px + PANEL_W, l.panelBottom, PANEL);
        outline(g, l.px, l.py, PANEL_W, l.panelBottom - l.py, OUTLINE);
        g.fill(l.px, l.py, l.px + PANEL_W, l.py + HEADER_H, HEADER);
        g.text(font, Component.translatable("gui.nerolandcore.side_config.title"), l.px + 5, l.py + 3, TEXT, false);
        Component chName = channelLabel(activeChannel);
        g.text(font, chName, l.px + PANEL_W - font.width(chName) - 5, l.py + 3, channelColor(activeChannel), false);

        // Channel sub-tabs.
        if (config.isMultiChannel()) {
            int tx = l.px + 5;
            for (Channel channel : l.channels) {
                int w = 32;
                button(g, tx, l.tabsY, w, 13, channel == activeChannel ? BTN_ON : BTN);
                center(g, font, channelLabel(channel), tx + w / 2, l.tabsY + 3, channel == activeChannel ? 0xFF0A0A0A : TEXT);
                tx += w + 2;
            }
        }

        // The flattened cube net with face letters.
        ChannelConfig cfg = config.get(activeChannel);
        for (RelativeFace face : RelativeFace.VALUES) {
            int[] xy = faceCell(l.netX, l.netY, face);
            SideMode mode = cfg == null ? SideMode.DISABLED : cfg.mode(face);
            button(g, xy[0], xy[1], CELL, CELL, BORDER);
            g.fill(xy[0] + 1, xy[1] + 1, xy[0] + CELL - 1, xy[1] + CELL - 1, SideModeColors.of(mode));
            center(g, font, faceLabel(face), xy[0] + CELL / 2, xy[1] + 4, 0xFF0A0A0A);
        }

        // Right column: mode palette while a face is selected, otherwise the legend.
        if (paletteFace != null && cfg != null) {
            g.text(font, Component.translatable("gui.nerolandcore.side_config.set_face", faceLabel(paletteFace)), l.colX, l.netY - 1, SUBTLE, false);
            int sy = l.netY + 10;
            for (SideMode mode : SideMode.VALUES) {
                if (!cfg.isAllowed(mode)) {
                    continue;
                }
                swatch(g, font, l.colX, sy, mode);
                sy += 11;
            }
        } else {
            int sy = l.netY;
            for (SideMode mode : SideMode.VALUES) {
                if (cfg != null && !cfg.isAllowed(mode)) {
                    continue;
                }
                swatch(g, font, l.colX, sy, mode);
                sy += 11;
            }
        }

        // Controls — auto-eject / auto-input toggles, then reset / copy / paste.
        boolean autoEject = cfg != null && cfg.autoEject();
        boolean autoInput = cfg != null && cfg.autoInput();
        labelledButton(g, font, l.px + 5, l.ctrlY1, 64, Component.translatable("gui.nerolandcore.side_config.auto_eject"), autoEject ? BTN_ON : BTN, autoEject ? 0xFF0A0A0A : TEXT);
        labelledButton(g, font, l.px + 73, l.ctrlY1, 64, Component.translatable("gui.nerolandcore.side_config.auto_input"), autoInput ? BTN_ON : BTN, autoInput ? 0xFF0A0A0A : TEXT);
        labelledButton(g, font, l.px + 5, l.ctrlY2, 42, Component.translatable("gui.nerolandcore.side_config.reset"), BTN, TEXT);
        labelledButton(g, font, l.px + 49, l.ctrlY2, 42, Component.translatable("gui.nerolandcore.side_config.copy"), BTN, TEXT);
        labelledButton(g, font, l.px + 93, l.ctrlY2, 44,
                Component.translatable("gui.nerolandcore.side_config.paste"), SideConfigClipboard.hasFor(typeKey) ? BTN_ON : BTN,
                SideConfigClipboard.hasFor(typeKey) ? 0xFF0A0A0A : SUBTLE);

        g.text(font, Component.translatable("gui.nerolandcore.side_config.hint"), l.px + 5, l.hintY, SUBTLE, false);
    }

    // --- input --------------------------------------------------------------

    public boolean mouseClicked(double mx, double my, int button, int guiLeft, int guiTop) {
        Layout l = layout(guiLeft, guiTop);

        if (in(mx, my, l.px, l.py, open ? PANEL_W : CELL, HEADER_H)) {
            open = !open;
            paletteFace = null;
            return true;
        }
        if (!open) {
            return false;
        }

        // Channel tabs.
        if (config.isMultiChannel()) {
            int tx = l.px + 5;
            for (Channel channel : l.channels) {
                if (in(mx, my, tx, l.tabsY, 32, 13)) {
                    activeChannel = channel;
                    paletteFace = null;
                    return true;
                }
                tx += 34;
            }
        }

        ChannelConfig cfg = config.get(activeChannel);

        // Palette swatches (only while a face is selected).
        if (paletteFace != null && cfg != null) {
            int sy = l.netY + 10;
            for (SideMode mode : SideMode.VALUES) {
                if (!cfg.isAllowed(mode)) {
                    continue;
                }
                if (in(mx, my, l.colX, sy, 60, 9)) {
                    send(SideConfigIntentPayload.setMode(pos, activeChannel.ordinal(), paletteFace.index(), mode.ordinal()));
                    cfg.setMode(paletteFace, mode);
                    paletteFace = null;
                    return true;
                }
                sy += 11;
            }
            paletteFace = null;
        }

        // Faces.
        for (RelativeFace face : RelativeFace.VALUES) {
            int[] xy = faceCell(l.netX, l.netY, face);
            if (in(mx, my, xy[0], xy[1], CELL, CELL)) {
                if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_MIDDLE) {
                    paletteFace = face;
                } else if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) {
                    send(SideConfigIntentPayload.setMode(pos, activeChannel.ordinal(), face.index(), SideMode.DISABLED.ordinal()));
                    if (cfg != null) {
                        cfg.setMode(face, SideMode.DISABLED);
                    }
                } else {
                    send(SideConfigIntentPayload.cycle(pos, activeChannel.ordinal(), face.index()));
                    if (cfg != null) {
                        cfg.cycle(face);
                    }
                }
                return true;
            }
        }

        // Controls.
        if (in(mx, my, l.px + 5, l.ctrlY1, 64, 14)) {
            send(SideConfigIntentPayload.autoEject(pos, activeChannel.ordinal(), !(cfg != null && cfg.autoEject())));
            return true;
        }
        if (in(mx, my, l.px + 73, l.ctrlY1, 64, 14)) {
            send(SideConfigIntentPayload.autoInput(pos, activeChannel.ordinal(), !(cfg != null && cfg.autoInput())));
            return true;
        }
        if (in(mx, my, l.px + 5, l.ctrlY2, 42, 14)) {
            send(SideConfigIntentPayload.reset(pos));
            return true;
        }
        if (in(mx, my, l.px + 49, l.ctrlY2, 42, 14)) {
            SideConfigClipboard.copy(typeKey, config.packAll());
            return true;
        }
        if (in(mx, my, l.px + 93, l.ctrlY2, 44, 14)) {
            if (SideConfigClipboard.hasFor(typeKey)) {
                send(SideConfigIntentPayload.paste(pos, SideConfigClipboard.packed()));
            }
            return true;
        }

        // Swallow any other click inside the open panel so it doesn't reach the slots behind it.
        return in(mx, my, l.px, l.py, PANEL_W, l.panelBottom - l.py);
    }

    // --- helpers ------------------------------------------------------------

    private static void send(SideConfigIntentPayload payload) {
        Services.NETWORK.sendToServer(payload);
    }

    private void swatch(GuiGraphicsExtractor g, Font font, int x, int y, SideMode mode) {
        g.fill(x, y, x + 8, y + 8, BORDER);
        g.fill(x + 1, y + 1, x + 7, y + 7, SideModeColors.of(mode));
        g.text(font, modeLabel(mode), x + 11, y, TEXT, false);
    }

    private static int[] faceCell(int ox, int oy, RelativeFace face) {
        int col;
        int row;
        switch (face) {
            case TOP -> {
                col = 1;
                row = 0;
            }
            case LEFT -> {
                col = 0;
                row = 1;
            }
            case FRONT -> {
                col = 1;
                row = 1;
            }
            case RIGHT -> {
                col = 2;
                row = 1;
            }
            case BACK -> {
                col = 3;
                row = 1;
            }
            default -> {
                col = 1;
                row = 2;
            }
        }
        return new int[] { ox + col * STEP, oy + row * STEP };
    }

    private static Component faceLabel(RelativeFace face) {
        return Component.translatable(switch (face) {
            case FRONT -> "gui.nerolandcore.side_config.face.front";
            case BACK -> "gui.nerolandcore.side_config.face.back";
            case LEFT -> "gui.nerolandcore.side_config.face.left";
            case RIGHT -> "gui.nerolandcore.side_config.face.right";
            case TOP -> "gui.nerolandcore.side_config.face.top";
            case BOTTOM -> "gui.nerolandcore.side_config.face.bottom";
        });
    }

    private static Component modeLabel(SideMode mode) {
        return Component.translatable(switch (mode) {
            case DISABLED -> "gui.nerolandcore.side_config.mode.off";
            case INPUT -> "gui.nerolandcore.side_config.mode.input";
            case OUTPUT -> "gui.nerolandcore.side_config.mode.output";
            case IO -> "gui.nerolandcore.side_config.mode.io";
            case PUSH -> "gui.nerolandcore.side_config.mode.push";
        });
    }

    private static Component channelLabel(Channel channel) {
        return Component.translatable(switch (channel) {
            case ITEM -> "gui.nerolandcore.side_config.channel.items";
            case FLUID -> "gui.nerolandcore.side_config.channel.fluid";
            case GAS -> "gui.nerolandcore.side_config.channel.gas";
            case ENERGY -> "gui.nerolandcore.side_config.channel.power";
        });
    }

    private static int channelColor(Channel channel) {
        return switch (channel) {
            case ITEM -> 0xFFE8E8F4;
            case FLUID -> 0xFF3C78F0;
            case GAS -> 0xFF78D2F0;
            case ENERGY -> 0xFFE0C020;
        };
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    private static void labelledButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, Component label, int bg, int fg) {
        g.fill(x, y, x + w, y + 14, bg);
        outline(g, x, y, w, 14, BORDER);
        center(g, font, label, x + w / 2, y + 3, fg);
    }

    private static void center(GuiGraphicsExtractor g, Font font, Component s, int cx, int topY, int color) {
        g.text(font, s, cx - font.width(s) / 2, topY, color, false);
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }
}
