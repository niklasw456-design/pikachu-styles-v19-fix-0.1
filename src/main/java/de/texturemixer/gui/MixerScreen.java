package de.texturemixer.gui;

import de.texturemixer.Category;
import de.texturemixer.CrosshairHud;
import de.texturemixer.CustomCrosshair;
import de.texturemixer.MixerConfig;
import de.texturemixer.NameIcon;
import de.texturemixer.PackApplier;
import de.texturemixer.PackScanner;
import de.texturemixer.SoundPreview;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Eigenes dunkles Menü (kein Minecraft-Look): Reiter pro Gegenstand, oben ein Umschalter
 * "Platziert / In der Hand" (nur bei Blöcken und Crystals), darunter ein Raster mit Vorschaubildern.
 * Klicks laufen über unsichtbare Buttons, das Aussehen wird komplett selbst darübergezeichnet.
 *
 * Animationen: Einblenden/Ausblenden, gestaffeltes Erscheinen der Karten (beim Blättern von der Seite),
 * weiches Hover mit Anheben, pulsierende Auswahl, gleitende Auswahl (Reiter-Unterstrich, Blockliste),
 * weiches Scrollen, Aufblitzen bei Auswahl, Ladeschimmer, Überblenden der Hover-Zeile, Klangbalken im Totem-Sound.
 * Alle Übergänge sind bildratenunabhängig (exponentielle Glättung). Pro Bild wird möglichst nichts neu berechnet.
 *
 * Reiter "Blöcke": Texturen eines Blocks (oben/seite/unten ...) sind zu einer Zeile zusammengefasst
 * (siehe BlockGroups). Über die drei Punkte (⋯) in der Zeile öffnet sich die Seitenwahl.
 * Die Suchleiste im Kopf filtert Reiter und Blockliste.
 */
public class MixerScreen extends Screen {
    private static final Map<Integer, String> KEY_NAMES = buildKeyNames();
    private static final int CW = 76;
    private static final int CH = 72;
    /** Karten in der Body-Ansicht sind höher (Figur 64 px). */
    private static final int CH_BODY = 92;
    private static final int GAP = 8;
    private static final int LIST_W = 156;
    private static final int ROW_H = 14;
    private static final int DOTS_W = 22;
    private static final int CHIPS_H = 24;
    private static final long OPEN_MS = 220;
    private static final long CLOSE_MS = 150;
    private static int lastTab = 0;

    /** Pseudo-Pack "Eigenes" im Reiter Fadenkreuz: der Name ist der Wert, der in der Auswahl gespeichert wird. */
    private static final PackScanner.PackInfo CUSTOM =
            new PackScanner.PackInfo(CustomCrosshair.ID, null, Set.of(), Set.of());

    /** Karte mit vorberechneten Animations-Schlüsseln (kein String-Basteln pro Bild). */
    private record Card(int x, int y, PackScanner.PackInfo pack, String key, String kh, String ks, String kp) {
        Card(int x, int y, PackScanner.PackInfo pack) {
            this(x, y, pack, cardKey(pack), cardKey(pack) + ":h", cardKey(pack) + ":s", cardKey(pack) + ":p");
        }

        private static String cardKey(PackScanner.PackInfo pack) {
            return "card:" + (pack == null ? "" : pack.name());
        }
    }

    private record Ctl(int x, int y, int w, int h, Supplier<String> label,
                       BooleanSupplier selected, BooleanSupplier enabled, BooleanSupplier danger,
                       String key, String keyS, String keyD) {
        Ctl(int x, int y, int w, int h, Supplier<String> label,
            BooleanSupplier selected, BooleanSupplier enabled, BooleanSupplier danger) {
            this(x, y, w, h, label, selected, enabled, danger,
                    "ctl:" + x + ":" + y, "ctl:" + x + ":" + y + "s", "ctl:" + x + ":" + y + "d");
        }
    }

    private final Screen parent;
    private List<PackScanner.PackInfo> packs;
    private final Map<String, ItemStack> icons = new HashMap<>();
    private final Map<String, ItemStack[]> armorSets = new HashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Ctl> ctls = new ArrayList<>();
    private final List<Ctl> popCtls = new ArrayList<>();

    // Profile
    private boolean profOpen = false;
    private TextFieldWidget nameField;
    private boolean focusNameNext = false;
    private String deleteArmed = null;
    private List<String> profNames = new ArrayList<>();
    private int popX, popY, popW, popH, nameFieldY;

    // Zurücksetzen mit Bestätigung
    private boolean resetArmed = false;
    private long resetAt = 0;

    private int tab = lastTab;
    private int variantIdx = 0;
    private int page = 0;
    private int pages = 1;
    private boolean listening = false;
    private boolean dirty = false;

    // Blöcke-Reiter: Liste links, ein Eintrag pro Block (Texturen zusammengefasst)
    private List<BlockGroups.Group> groups = new ArrayList<>();
    private List<BlockGroups.Group> visible = new ArrayList<>();
    private BlockGroups.Group group = null;
    /** null = "Alle Seiten", sonst der Name einer einzelnen Textur. */
    private String face = null;
    private boolean facesOpen = false;
    private int listX, listY, listH;
    private float scrollPx = 0, scrollTarget = 0, selHi = -1;

    // Suche
    private TextFieldWidget search;
    private boolean needsRebuild = false;
    private boolean refocusSearch = false;
    private boolean focusSearchNext = false;
    private boolean[] tabMatch = new boolean[0];

    private int px0, py0, px1, py1;
    private int sfx, sfy, sfw;
    private int tabSize = 22;
    private int tabStartX = 0;
    private int tabY = 0;
    private int tabsPerRow = 1;
    private int gridX = 12;
    private int subY = 0;
    /** Breite der Reihe oben rechts (Material, Platziert / In der Hand), damit der Titel nicht darunter läuft. */
    private int topRowW = 0;
    private int loadBudget = 2;

    // Animation
    private final long openStart = nowMs();
    private long contentStart = nowMs();
    private long closeStart = 0;
    private boolean closing = false;
    private boolean replay = true;
    private long lastFrameNs = System.nanoTime();
    private float dt = 0.016f;
    private float gA = 0f;
    private float indX = -1, indY = 0;
    private int lastMx, lastMy;
    private final Map<String, Float> anim = new HashMap<>();
    private final Map<String, Long> flashes = new HashMap<>();
    /** Richtung des Blätterns (+1 weiter, -1 zurück, 0 = keine): die neuen Karten gleiten von dieser Seite herein. */
    private int slideDir = 0;
    /** Anzahl der Einträge der aktuellen Ansicht (beim Aufbau berechnet, nicht pro Bild). */
    private int entryCount = 0;
    private String[] kTab = new String[0];
    private String[] kTabSel = new String[0];
    private String[] kTabDim = new String[0];
    private String shownLine2 = "";
    private long line2At = 0;
    private int applyX, applyY, applyW;
    /** Dark/Light-Schalter in der Kopfzeile. */
    private static final int THEME_W = 32;
    private int themeX;
    private long themeFlashAt = -10_000;

    public MixerScreen(Screen parent) {
        super(Text.literal("Pikachu Styles"));
        this.parent = parent;
        this.packs = PackScanner.scan();
        rebuildGroups();
        for (Category c : Category.ALL) {
            icons.put(c.id(), makeIcon(c));
            for (Category.Variant v : c.variants()) {
                if (v.isArmorItems()) armorSets.put(c.id() + ":" + v.material(), makeArmorSet(v.material()));
            }
        }
    }

    private void rebuildGroups() {
        Set<String> names = new LinkedHashSet<>();
        for (PackScanner.PackInfo p : packs) names.addAll(p.blocks());
        groups = BlockGroups.build(names);
        if (group != null) {
            String base = group.base();
            group = null;
            for (BlockGroups.Group g : groups) {
                if (g.base().equals(base)) group = g;
            }
            if (group == null) {
                face = null;
                facesOpen = false;
            }
        }
    }

    private static ItemStack[] makeArmorSet(String material) {
        String[] parts = {"helmet", "chestplate", "leggings", "boots"};
        ItemStack[] set = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            set[i] = new ItemStack(Registries.ITEM.get(Identifier.of("minecraft", material + "_" + parts[i])));
        }
        return set;
    }

    /** Eigenes Symbol (Herzen, Hunger, Hotbar, ...), sonst das Item der Kategorie. */
    private void drawIcon(DrawContext ctx, String id, int x, int y) {
        if (!TabIcons.draw(ctx, id, x, y)) {
            ctx.drawItem(icons.get(id), x, y);
        }
    }

    private static ItemStack makeIcon(Category c) {
        ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.of("minecraft", c.icon())));
        if (c.potionIcon() != null) {
            Registries.POTION.getEntry(Identifier.of("minecraft", c.potionIcon()))
                    .ifPresent(e -> stack.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(e)));
        }
        return stack;
    }

    private Category cat() {
        return Category.ALL.get(tab);
    }

    private boolean isBlocks() {
        return cat().id().equals("blocks");
    }

    private boolean isCrosshair() {
        return cat().id().equals(CustomCrosshair.KEY);
    }

    /** Anzeigename einer Auswahl: Pack ohne ".zip", das eigene Fadenkreuz als "Eigenes". */
    private static String label(String name) {
        return CustomCrosshair.ID.equals(name) ? "Eigenes" : stripZip(name);
    }

    private Category.Variant variant() {
        Category c = cat();
        if (isBlocks()) {
            String tex = face != null ? face : group != null ? group.rep(null) : "none";
            return Category.blockVariant(tex);
        }
        return c.variants().get(Math.min(variantIdx, c.variants().size() - 1));
    }

    private static String pretty(String name) {
        return BlockGroups.pretty(name);
    }

    /** Kartenhöhe: in der Body-Ansicht höher als sonst. */
    private int ch() {
        return variant().isBody() ? CH_BODY : CH;
    }

    // ---------- Blöcke: Auswahl ----------

    /** Texturen, auf die sich die aktuelle Auswahl bezieht: eine Seite oder alle des Blocks. */
    private Set<String> targets() {
        Set<String> t = new LinkedHashSet<>();
        if (group == null) return t;
        if (face != null) {
            t.add(face);
        } else {
            for (BlockGroups.Face f : group.faces()) t.add(f.tex());
        }
        return t;
    }

    private boolean packHasTarget(PackScanner.PackInfo p) {
        for (String t : targets()) {
            if (p.blocks().contains(t)) return true;
        }
        return false;
    }

    private void selectBlock(PackScanner.PackInfo p) {
        MixerConfig cfg = MixerConfig.get();
        for (String t : targets()) {
            if (p != null && p.blocks().contains(t)) cfg.selections.put("block:" + t, p.name());
            else cfg.selections.remove("block:" + t);
        }
        cfg.save();
        dirty = true;
    }

    private boolean blockSelected(PackScanner.PackInfo p) {
        MixerConfig cfg = MixerConfig.get();
        Set<String> t = targets();
        if (p == null) {
            for (String s : t) {
                if (cfg.selections.containsKey("block:" + s)) return false;
            }
            return true;
        }
        boolean any = false;
        for (String s : t) {
            if (!p.blocks().contains(s)) continue;
            if (!p.name().equals(cfg.selections.get("block:" + s))) return false;
            any = true;
        }
        return any;
    }

    /** Name für die Zeile "Aktiv: ..." bei Blöcken. */
    private String blockActive() {
        Set<String> used = new LinkedHashSet<>();
        for (String s : targets()) {
            String sel = MixerConfig.get().selections.get("block:" + s);
            if (sel != null) used.add(sel);
        }
        if (used.isEmpty()) return null;
        return used.size() == 1 ? used.iterator().next() : "Gemischt";
    }

    private boolean groupChosen(BlockGroups.Group g) {
        for (BlockGroups.Face f : g.faces()) {
            if (MixerConfig.get().selections.containsKey("block:" + f.tex())) return true;
        }
        return false;
    }

    private List<PackScanner.PackInfo> entries() {
        List<PackScanner.PackInfo> l = new ArrayList<>();
        if (isBlocks()) {
            if (group == null) return l;
            l.add(null);
            for (PackScanner.PackInfo p : packs) {
                if (packHasTarget(p)) l.add(p);
            }
            return l;
        }
        String id = cat().fullId(variant());
        l.add(null); // null = nichts überschreiben
        if (isCrosshair()) l.add(CUSTOM); // selbst gezeichnet
        for (PackScanner.PackInfo p : packs) {
            if (p.categories().contains(id)) l.add(p);
        }
        return l;
    }

    private String standardLabel() {
        return (!isBlocks() && cat().dual() && variant().hand() != null) ? "Wie platziert" : "Standard";
    }

    // ---------- Suche ----------

    private String query() {
        return search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
    }

    private void computeTabMatches() {
        String q = query();
        int n = Category.ALL.size();
        tabMatch = new boolean[n];
        for (int i = 0; i < n; i++) {
            Category c = Category.ALL.get(i);
            boolean m = q.isEmpty()
                    || c.name().toLowerCase(Locale.ROOT).contains(q)
                    || c.id().contains(q.replace(' ', '_'));
            if (!m) {
                for (String g : c.groups()) {
                    if (g.toLowerCase(Locale.ROOT).contains(q)) {
                        m = true;
                        break;
                    }
                }
            }
            if (!m && c.id().equals("blocks")) {
                for (BlockGroups.Group g : groups) {
                    if (g.matches(q)) {
                        m = true;
                        break;
                    }
                }
            }
            tabMatch[i] = m;
        }
    }

    private void onSearch(String text) {
        computeTabMatches();
        if (tab >= tabMatch.length || !tabMatch[tab]) {
            for (int i = 0; i < tabMatch.length; i++) {
                if (tabMatch[i]) {
                    tab = i;
                    lastTab = i;
                    variantIdx = 0;
                    page = 0;
                    contentStart = nowMs();
                    break;
                }
            }
        }
        scrollTarget = 0;
        needsRebuild = true;
        refocusSearch = true;
    }

    // ---------- Aufbau ----------

    private ButtonWidget hit(int x, int y, int w, int h, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(Text.empty(), btn -> {
            if (!closing) action.run();
        }).dimensions(x, y, w, h).build();
        b.setAlpha(0f); // Klickfläche, gezeichnet wird alles selbst
        return this.addDrawableChild(b);
    }

    private void addCtl(int x, int y, int w, int h, Supplier<String> label,
                        BooleanSupplier selected, BooleanSupplier enabled, Runnable action) {
        ctls.add(new Ctl(x, y, w, h, label, selected, enabled, () -> false));
        hit(x, y, w, h, action).active = enabled.getAsBoolean();
    }

    /** Bedienelement im Profil-Fenster (wird über dem Fenster gezeichnet). danger = roter Bestätigen-Zustand. */
    private void addPopCtl(int x, int y, int w, int h, Supplier<String> label, BooleanSupplier enabled,
                           BooleanSupplier danger, Runnable action) {
        popCtls.add(new Ctl(x, y, w, h, label, () -> false, enabled, danger));
        hit(x, y, w, h, action).active = enabled.getAsBoolean();
    }

    private boolean interactive() {
        return !closing && !profOpen;
    }

    /** Neu aufbauen, Karten-Animation neu starten. */
    private void refresh() {
        slideDir = 0;
        replay = true;
        this.clearAndInit();
    }

    /** Wie refresh(), die neuen Karten gleiten aber von der Seite herein (Blättern). */
    private void refresh(int dir) {
        refresh();
        slideDir = dir;
    }

    @Override
    protected void init() {
        cards.clear();
        ctls.clear();
        int n = Category.ALL.size();
        if (kTab.length != n) {
            kTab = new String[n];
            kTabSel = new String[n];
            kTabDim = new String[n];
            for (int i = 0; i < n; i++) {
                kTab[i] = "tab" + i;
                kTabSel[i] = "tabsel" + i;
                kTabDim[i] = "tabdim" + i;
            }
        }
        tab = Math.max(0, Math.min(tab, n - 1));
        variantIdx = Math.max(0, Math.min(variantIdx, cat().variants().size() - 1));
        if (replay) {
            contentStart = nowMs();
            replay = false;
        }

        px0 = 8;
        py0 = 8;
        px1 = this.width - 8;
        py1 = this.height - 8;

        popCtls.clear();
        if (profOpen) buildPopup(); // zuerst, damit es Klicks vor allem anderen bekommt

        // Kopfzeile: Taste zum Öffnen
        int keyW = 130;
        int keyX = px1 - 8 - keyW;
        addCtl(keyX, py0 + 6, keyW, 16,
                () -> listening ? "Taste drücken..." : "Taste: " + keyName(MixerConfig.get().openKey),
                () -> listening, () -> true, () -> listening = true);

        // Kopfzeile: Suchleiste
        int titleW = tw("PIKACHU STYLES");
        int left = px0 + 12 + titleW + 14;
        int iconW = 118;
        int iconX = keyX - 6 - iconW;
        addCtl(iconX, py0 + 6, iconW, 16,
                () -> NameIcon.label(MixerConfig.get().nameIcon),
                () -> !NameIcon.OFF.equals(MixerConfig.get().nameIcon), () -> true, () -> {
                    MixerConfig.get().nameIcon = NameIcon.next(MixerConfig.get().nameIcon);
                    MixerConfig.get().save();
                });
        themeX = iconX - 6 - THEME_W;
        hit(themeX, py0 + 6, THEME_W, 16, () -> {
            Theme.toggle();
            themeFlashAt = nowMs();
        });
        sfw = Math.max(70, Math.min(210, themeX - 8 - left));
        sfx = themeX - 8 - sfw;
        sfy = py0 + 5;
        if (search == null) {
            search = new TextFieldWidget(this.textRenderer, 0, 0, 10, 10, Text.literal("Suche"));
            search.setDrawsBackground(false);
            search.setMaxLength(40);
            search.setEditableColor(Theme.text());
            search.setPlaceholder(Text.literal("Suchen...").setStyle(Style.EMPTY.withColor(0x9A9684)));
            search.setChangedListener(this::onSearch);
        }
        boolean hasText = !search.getText().isEmpty();
        int clearW = hasText ? 16 : 0;
        search.setX(sfx + 8);
        search.setY(sfy + 5);
        search.setWidth(sfw - 12 - clearW);
        search.setHeight(10);
        hit(sfx, sfy, sfw - clearW, 18, () -> focusSearchNext = true);
        this.addSelectableChild(search);
        if (hasText) {
            addCtl(sfx + sfw - 16, sfy, 16, 18, () -> "x", () -> false, () -> true, () -> {
                search.setText("");
                focusSearchNext = true;
            });
        }
        computeTabMatches();

        // Reiter (bei Platzmangel in mehreren Reihen)
        int avail = px1 - px0 - 24;
        tabSize = 20;
        for (int s = 24; s >= 18; s--) {
            if (n * (s + 2) - 2 <= avail) {
                tabSize = s;
                break;
            }
        }
        tabsPerRow = Math.max(1, (avail + 2) / (tabSize + 2));
        int rowW = Math.min(n, tabsPerRow) * (tabSize + 2) - 2;
        tabStartX = (this.width - rowW) / 2;
        tabY = py0 + 34;
        int tabRows = (n + tabsPerRow - 1) / tabsPerRow;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            int x = tabStartX + (i % tabsPerRow) * (tabSize + 2);
            int y = tabY + (i / tabsPerRow) * (tabSize + 2);
            hit(x, y, tabSize, tabSize, () -> {
                if (tab != idx) {
                    SoundPreview.stop();
                    tab = idx;
                    lastTab = idx;
                    variantIdx = 0;
                    page = 0;
                    refresh();
                }
            });
        }
        subY = tabY + tabRows * (tabSize + 2) + 6;

        // Obere Reihe rechts: Material (Schwert, Axt, Rüstung ...) bzw. Platziert / In der Hand
        Category cat = cat();
        topRowW = 0;
        boolean sub = false;
        if (cat.dual() && !isBlocks()) {
            List<String> labels = new ArrayList<>();
            List<Integer> targets = new ArrayList<>();
            if (cat.hasGroups()) {
                Category.Variant cur = cat.variants().get(variantIdx);
                for (String g : cat.groups()) {
                    int target = -1;
                    for (int i = 0; i < cat.variants().size(); i++) {
                        Category.Variant cv = cat.variants().get(i);
                        if (!g.equals(cv.group())) continue;
                        if (target < 0) target = i;
                        if (cv.label().equals(cur.label())) target = i; // gleiche Unterauswahl (Item/Body) behalten
                    }
                    labels.add(g);
                    targets.add(target);
                }
                sub = true;
            } else {
                for (int i = 0; i < cat.variants().size(); i++) {
                    labels.add(cat.variants().get(i).label());
                    targets.add(i);
                }
            }
            int total = -4;
            for (String l : labels) total += tw(l) + 18;
            topRowW = total;
            int cx = px1 - 12 - total;
            for (int i = 0; i < labels.size(); i++) {
                final int target = targets.get(i);
                final String label = labels.get(i);
                int w = tw(label) + 14;
                addCtl(cx, subY, w, 18, () -> label, () -> chipActive(target), () -> true, () -> {
                    if (variantIdx != target) {
                        SoundPreview.stop();
                        variantIdx = target;
                        page = 0;
                        refresh();
                    }
                });
                cx += w + 4;
            }
        }

        // Fadenkreuz: Button zum Zeichnen des eigenen Fadenkreuzes
        if (isCrosshair()) {
            String label = "Eigenes zeichnen";
            int w = tw(label) + 18;
            topRowW = w;
            addCtl(px1 - 12 - w, subY, w, 18, () -> label, CustomCrosshair::active, () -> true,
                    this::openCrosshairEditor);
        }

        int gridTop = subY + 26;
        boolean blocks = isBlocks();

        // Blöcke: Liste links, ein Eintrag pro Block
        int leftW = 0;
        if (blocks) {
            String q = query();
            visible = new ArrayList<>();
            for (BlockGroups.Group g : groups) {
                if (g.matches(q)) visible.add(g);
            }
            boolean stillThere = false;
            if (group != null) {
                for (BlockGroups.Group g : visible) {
                    if (g.base().equals(group.base())) {
                        group = g;
                        stillThere = true;
                        break;
                    }
                }
            }
            if (!stillThere) {
                group = visible.isEmpty() ? null : visible.get(0);
                face = null;
                facesOpen = false;
                contentStart = nowMs();
            }
            if (face != null && (group == null || group.faces().stream().noneMatch(f -> f.tex().equals(face)))) {
                face = null;
            }

            leftW = LIST_W + 10;
            listX = px0 + 12;
            listY = gridTop;
            listH = Math.max(3 * ROW_H, (py1 - 32) - gridTop);
            scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
            hit(listX, listY, LIST_W, listH, this::clickList);
        }

        // Seiten-Auswahl (Alle / Oben / Seite / Unten ...) unter den drei Punkten
        int gridTop2 = gridTop;
        gridX = blocks ? px0 + 12 + leftW : 0; // Breite folgt unten
        if (blocks && group != null && group.multi() && facesOpen) {
            int cx = px0 + 12 + leftW;
            List<String> labels = new ArrayList<>();
            List<String> texs = new ArrayList<>();
            labels.add("Alle Seiten");
            texs.add(null);
            for (BlockGroups.Face f : group.faces()) {
                labels.add(f.label());
                texs.add(f.tex());
            }
            for (int i = 0; i < labels.size(); i++) {
                int w = tw(labels.get(i)) + 16;
                if (cx + w > px1 - 12) break;
                final String tex = texs.get(i);
                final String label = labels.get(i);
                addCtl(cx, gridTop, w, 18, () -> label,
                        () -> java.util.Objects.equals(face, tex), () -> true, () -> {
                            if (!java.util.Objects.equals(face, tex)) {
                                face = tex;
                                page = 0;
                                refresh();
                            }
                        });
                cx += w + 4;
            }
            gridTop2 = gridTop + CHIPS_H;
        }

        if (sub) gridTop2 += CHIPS_H;

        // Raster
        Category.Variant v = variant();
        List<PackScanner.PackInfo> entries = entries();
        entryCount = entries.size();
        int availW = (px1 - px0 - 24) - leftW;
        int cols = Math.max(1, Math.min(8, (availW + GAP) / (CW + GAP)));
        int availH = (py1 - 32) - gridTop2;
        int rows = Math.max(1, (availH + GAP) / (ch() + GAP));
        int perPage = cols * rows;
        pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        int gridW = cols * CW + (cols - 1) * GAP;
        gridX = blocks ? px0 + 12 + leftW : (this.width - gridW) / 2;
        // Untere Reihe (z. B. Item / Body bei der Rüstung) links über dem Raster
        if (sub) {
            String curGroup = cat.variants().get(variantIdx).group();
            int cx = gridX;
            for (int i = 0; i < cat.variants().size(); i++) {
                Category.Variant cv = cat.variants().get(i);
                if (!curGroup.equals(cv.group())) continue;
                final int target = i;
                String label = cv.label();
                int w = tw(label) + 20;
                addCtl(cx, gridTop, w, 18, () -> label, () -> variantIdx == target, () -> true, () -> {
                    if (variantIdx != target) {
                        SoundPreview.stop();
                        variantIdx = target;
                        page = 0;
                        refresh();
                    }
                });
                cx += w + 4;
            }
        }

        int start = page * perPage;
        int end = Math.min(entries.size(), start + perPage);
        for (int i = start; i < end; i++) {
            int k = i - start;
            int x = gridX + (k % cols) * (CW + GAP);
            int y = gridTop2 + (k / cols) * (ch() + GAP);
            PackScanner.PackInfo p = entries.get(i);
            cards.add(new Card(x, y, p));
            hit(x, y, CW, ch(), () -> select(cat, v, p));
        }

        // Fußzeile
        int fy = py1 - 24;
        addCtl(px0 + 12, fy, 20, 18, () -> "<", () -> false, () -> page > 0, () -> {
            page--;
            refresh(-1);
        });
        addCtl(px0 + 12 + 20 + 60, fy, 20, 18, () -> ">", () -> false, () -> page < pages - 1, () -> {
            page++;
            refresh(1);
        });
        // Profil-Auswahl
        addCtl(px0 + 12 + 20 + 60 + 20 + 12, fy, 150, 18,
                () -> fit("Profil: " + MixerConfig.get().activeProfile, 144),
                () -> profOpen, () -> true, () -> {
                    profOpen = true;
                    deleteArmed = null;
                    resetArmed = false;
                    anim.put("pop", 0f);
                    refresh();
                });

        int rx = px1 - 12;
        addCtl(rx - 56, fy, 56, 18, () -> "Fertig", () -> false, () -> true, this::close);
        applyX = rx - 56 - 4 - 64;
        applyY = fy;
        applyW = 64;
        addCtl(rx - 56 - 4 - 64, fy, 64, 18, () -> "Anwenden", () -> dirty, () -> true, () -> {
            PackApplier.apply();
            dirty = false;
        });
        int scanX = rx - 56 - 4 - 64 - 4 - 78;
        addCtl(scanX, fy, 78, 18, () -> "Neu scannen", () -> false, () -> true, () -> {
            packs = PackScanner.scan(true);
            rebuildGroups();
            refresh();
        });

        // Zurücksetzen: erst "Bestätigen" / "Abbrechen", damit nichts aus Versehen verloren geht
        if (!resetArmed) {
            addCtl(scanX - 4 - 84, fy, 84, 18, () -> "Zurücksetzen", () -> false, () -> true, () -> {
                resetArmed = true;
                resetAt = nowMs();
                refresh();
            });
        } else {
            int cx = scanX - 4 - 70;
            addCtl(cx, fy, 70, 18, () -> "Abbrechen", () -> false, () -> true, () -> {
                resetArmed = false;
                refresh();
            });
            ctls.add(new Ctl(cx - 4 - 80, fy, 80, 18, () -> "Bestätigen", () -> false, () -> true, () -> true));
            hit(cx - 4 - 80, fy, 80, 18, () -> {
                MixerConfig.get().resetActive();
                resetArmed = false;
                dirty = true;
                refresh();
            });
        }
    }

    /** Profil-Fenster: Liste, Namensfeld, Neu / Kopie / Umbenennen. */
    private void buildPopup() {
        MixerConfig cfg = MixerConfig.get();
        profNames = cfg.profileNames();
        int n = profNames.size();
        popW = Math.min(320, this.width - 40);
        popH = 34 + n * 22 + 8 + 18 + 6 + 20 + 12;
        popX = (this.width - popW) / 2;
        popY = Math.max(py0 + 4, (this.height - popH) / 2);

        int y = popY + 30;
        int delW = 60;
        boolean canDel = n > 1;
        for (String name : profNames) {
            final String nm = name;
            hit(popX + 8, y, popW - 16 - (canDel ? delW + 4 : 0), 20, () -> {
                if (!nm.equals(cfg.activeProfile)) {
                    cfg.switchProfile(nm);
                    dirty = true;
                }
                profOpen = false;
                deleteArmed = null;
                refresh();
            });
            if (canDel) {
                addPopCtl(popX + popW - 8 - delW, y, delW, 20,
                        () -> nm.equals(deleteArmed) ? "Sicher?" : "Löschen", () -> true,
                        () -> nm.equals(deleteArmed), () -> {
                            if (nm.equals(deleteArmed)) {
                                boolean wasActive = nm.equals(cfg.activeProfile);
                                cfg.deleteProfile(nm);
                                deleteArmed = null;
                                if (wasActive) dirty = true;
                            } else {
                                deleteArmed = nm;
                            }
                            refresh();
                        });
            }
            y += 22;
        }

        y += 6;
        nameFieldY = y;
        if (nameField == null) {
            nameField = new TextFieldWidget(this.textRenderer, 0, 0, 10, 10, Text.literal("Profilname"));
            nameField.setDrawsBackground(false);
            nameField.setMaxLength(MixerConfig.MAX_NAME);
            nameField.setEditableColor(Theme.text());
            nameField.setPlaceholder(Text.literal("Profilname ...").setStyle(Style.EMPTY.withColor(0x9A9684)));
        }
        nameField.setX(popX + 16);
        nameField.setY(y + 5);
        nameField.setWidth(popW - 32);
        nameField.setHeight(10);
        hit(popX + 8, y, popW - 16, 18, () -> focusNameNext = true);
        this.addSelectableChild(nameField);

        int by = y + 24;
        int bw = (popW - 16 - 8) / 3;
        boolean canAdd = cfg.canAddProfile();
        addPopCtl(popX + 8, by, bw, 20, () -> "Neu", () -> canAdd, () -> false, () -> {
            cfg.createProfile(nameField.getText(), false);
            nameField.setText("");
            dirty = true;
            refresh();
        });
        addPopCtl(popX + 8 + bw + 4, by, bw, 20, () -> "Kopie", () -> canAdd, () -> false, () -> {
            cfg.createProfile(nameField.getText(), true);
            nameField.setText("");
            refresh();
        });
        addPopCtl(popX + 8 + 2 * (bw + 4), by, bw, 20, () -> "Umbenennen", () -> true, () -> false, () -> {
            cfg.renameActive(nameField.getText());
            nameField.setText("");
            refresh();
        });

        // Klicks im Fenster nicht durchlassen, Klick daneben schließt es
        hit(popX, popY, popW, popH, () -> {});
        hit(0, 0, this.width, this.height, () -> {
            profOpen = false;
            deleteArmed = null;
            refresh();
        });
    }

    /** Ist der Chip mit dieser Ziel-Variante aktiv? (Bei Gruppen zählt die ganze Gruppe.) */
    private boolean chipActive(int target) {
        Category c = cat();
        if (!c.hasGroups()) return variantIdx == target;
        String g = c.variants().get(target).group();
        return g != null && g.equals(c.variants().get(Math.min(variantIdx, c.variants().size() - 1)).group());
    }

    /** Öffnet den Editor für das eigene Fadenkreuz. Beim Übernehmen wird "Eigenes" ausgewählt. */
    private void openCrosshairEditor() {
        if (this.client == null) return;
        this.client.setScreen(new CrosshairEditorScreen(this, () -> {
            MixerConfig cfg = MixerConfig.get();
            cfg.selections.put(CustomCrosshair.KEY, CustomCrosshair.ID);
            cfg.save();
            dirty = true;
            replay = true;
            needsRebuild = true;
            flashes.put("card:" + CustomCrosshair.ID, nowMs());
        }));
    }

    private void select(Category cat, Category.Variant v, PackScanner.PackInfo p) {
        if (p == CUSTOM && CustomCrosshair.active()) {
            // schon ausgewählt: der zweite Klick öffnet den Editor
            openCrosshairEditor();
            return;
        }
        if (isBlocks()) {
            selectBlock(p);
        } else {
            MixerConfig cfg = MixerConfig.get();
            String id = cat.fullId(v);
            if (p == null) cfg.selections.remove(id);
            else cfg.selections.put(id, p.name());
            cfg.save();
            dirty = true;
        }
        flashes.put("card:" + (p == null ? "" : p.name()), nowMs());
        if (v.isSound()) SoundPreview.play(p); // Klick wählt aus und spielt den Sound ab (auch erneut bei schon gewählter Karte)
    }

    /** Klick in die Blockliste (Position vom letzten Frame, die Klickfläche deckt die ganze Liste ab). */
    private void clickList() {
        int idx = (int) ((lastMy - listY + Math.round(scrollPx)) / (float) ROW_H);
        if (lastMy < listY || idx < 0 || idx >= visible.size()) return;
        BlockGroups.Group g = visible.get(idx);
        boolean dots = g.multi() && lastMx >= listX + LIST_W - DOTS_W - 4;
        boolean same = group != null && group.base().equals(g.base());
        if (dots) {
            if (same) {
                facesOpen = !facesOpen;
            } else {
                group = g;
                facesOpen = true;
            }
            face = null;
        } else {
            if (same) return;
            group = g;
            face = null;
            facesOpen = false;
        }
        page = 0;
        refresh();
    }

    private float maxScroll() {
        return Math.max(0, visible.size() * ROW_H - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isBlocks() && inside((int) mouseX, (int) mouseY, listX, listY, LIST_W, listH)) {
            scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) verticalAmount * ROW_H * 3));
            return true;
        }
        if (pages > 1 && mouseY > subY) {
            int np = verticalAmount < 0 ? page + 1 : page - 1;
            if (np >= 0 && np < pages) {
                int dir = np > page ? 1 : -1;
                page = np;
                refresh(dir);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---------- Animations-Helfer ----------

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static float easeOut(float t) {
        float u = 1 - clamp01(t);
        return 1 - u * u * u;
    }

    /** Bildratenunabhängiger Glättungsfaktor: Anteil des Restwegs, der in diesem Bild zurückgelegt wird. */
    private float k(float rate) {
        return 1f - (float) Math.exp(-dt * rate);
    }

    /** Weich nachgeführter Wert (0..1) für Hover/Auswahl. */
    private float anim(String key, boolean on) {
        float t = on ? 1f : 0f;
        float v = anim.getOrDefault(key, 0f);
        v += (t - v) * k(16f);
        if (Math.abs(t - v) < 0.01f) v = t;
        anim.put(key, v);
        return v;
    }

    private static int a(int color, float f) {
        int al = Math.round((color >>> 24) * clamp01(f));
        return (al << 24) | (color & 0xFFFFFF);
    }

    private static int mix(int c1, int c2, float t) {
        t = clamp01(t);
        int r = 0;
        for (int sh = 24; sh >= 0; sh -= 8) {
            int x = (c1 >>> sh) & 0xFF;
            int y = (c2 >>> sh) & 0xFF;
            r |= (Math.round(x + (y - x) * t) & 0xFF) << sh;
        }
        return r;
    }

    /** Farbe mit der globalen Ein-/Ausblend-Transparenz. */
    private int c(int color) {
        return a(color, gA);
    }

    /** Legt eine undurchsichtige Fläche mit (1 - e) Deckkraft über Bilder/Items, damit sie einblenden. */
    private static void veil(DrawContext ctx, int x, int y, int w, int h, int color, float e) {
        if (e >= 0.995f) return;
        ctx.fill(x, y, x + w, y + h, a(0xFF000000 | color, 1f - e));
    }

    /** Breite eines Textes in der Menü-Schrift (Mono). */
    private int tw(String s) {
        return this.textRenderer.getWidth(MonoFont.text(s));
    }

    private void txt(DrawContext ctx, String s, int x, int y, int color) {
        if ((color >>> 24) < 8) return; // sehr niedrige Alpha-Werte würde Minecraft als undurchsichtig zeichnen
        ctx.drawText(this.textRenderer, MonoFont.text(s), x, y, color, false);
    }

    private void ctxt(DrawContext ctx, String s, int cx, int y, int color) {
        if ((color >>> 24) < 8) return;
        ctx.drawText(this.textRenderer, MonoFont.text(s), cx - tw(s) / 2, y, color, false);
    }

    // ---------- Zeichnen ----------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastFrameNs) / 1e9f);
        lastFrameNs = nowNs;
        lastMx = mouseX;
        lastMy = mouseY;
        long now = nowMs();
        Theme.update();
        if (search != null) search.setEditableColor(Theme.text());
        if (nameField != null) nameField.setEditableColor(Theme.text());

        if (needsRebuild) {
            needsRebuild = false;
            boolean refocus = refocusSearch;
            refocusSearch = false;
            this.clearAndInit();
            if (refocus && search != null) {
                this.setFocused(search);
                search.setFocused(true);
            }
        }
        if (focusNameNext && profOpen && nameField != null) {
            focusNameNext = false;
            this.setFocused(nameField);
            nameField.setFocused(true);
        }
        if (focusSearchNext && search != null) {
            focusSearchNext = false;
            this.setFocused(search);
            search.setFocused(true);
        }

        super.render(context, mouseX, mouseY, delta); // Hintergrund; die Klickflächen sind unsichtbar
        loadBudget = 2;

        float open = easeOut((now - openStart) / (float) OPEN_MS);
        float cl = closing ? easeOut((now - closeStart) / (float) CLOSE_MS) : 0f;
        gA = open * (1f - cl);

        // weiches Scrollen
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
        scrollPx += (scrollTarget - scrollPx) * k(16f);
        if (Math.abs(scrollTarget - scrollPx) < 0.3f) scrollPx = scrollTarget;

        Category cat = cat();
        Category.Variant v = variant();
        int cardH = v.isBody() ? CH_BODY : CH;
        boolean blocks = isBlocks();
        String active = blocks ? blockActive() : MixerConfig.get().selections.get(cat.fullId(v));
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 300.0);

        // Schleier über der Welt (hell: leicht weiß, dunkel: abgedunkelt)
        context.fill(0, 0, this.width, this.height, a(Theme.scrim(), gA));

        // Das ganze Menü gleitet beim Öffnen ein Stück nach oben ein
        var mtx = context.getMatrices();
        mtx.pushMatrix();
        mtx.translate(0f, Math.round((1f - open) * 10f));

        // weicher Schatten um das Fenster
        for (int i = 4; i >= 1; i--) {
            border(context, px0 - i, py0 - i, px1 - px0 + 2 * i, py1 - py0 + 2 * i,
                    c(a(0xFF000000, (0.05f + 0.05f * Theme.blend()) * (5 - i) / 4f)));
        }

        // Fenster: ruhige Kopfzeile, dünne Gold-Linie mit wanderndem Glanz, feiner Rahmen
        context.fill(px0, py0, px1, py1, c(Theme.bg()));
        context.fill(px0, py0, px1, py0 + 28, c(Theme.header()));
        context.fill(px0, py0 + 28, px1, py0 + 29, c(Theme.border()));
        int lineW = Math.round((px1 - px0) * open);
        context.fill(px0, py0 + 28, px0 + lineW, py0 + 30, c(Theme.accent()));
        {
            // Glanz wandert über die Gold-Linie (zehn schmale Streifen, in der Mitte am hellsten)
            float sh = (now % 4200L) / 4200f;
            int gx = px0 - 50 + Math.round((px1 - px0 + 100) * sh);
            for (int i = 0; i < 10; i++) {
                int x0 = Math.max(px0, gx + i * 5);
                int x1 = Math.min(px0 + lineW, gx + i * 5 + 5);
                if (x1 <= x0) continue;
                float al = 0.75f * (float) Math.sin(Math.PI * (i + 0.5) / 10.0);
                context.fill(x0, py0 + 28, x1, py0 + 30, c(a(0xFFFFFFFF, al)));
            }
        }
        border(context, px0, py0, px1 - px0, py1 - py0, c(Theme.border()));

        // kurzes Aufleuchten beim Umschalten von Hell/Dunkel
        float tf = clamp01((now - themeFlashAt) / 450f);
        if (tf < 1f) {
            context.fill(px0, py0, px1, py1, a(Theme.accent(), 0.16f * (1f - tf) * (1f - tf) * gA));
        }

        txt(context, "PIKACHU STYLES", px0 + 14, py0 + 10, c(Theme.text()));
        int titleTw = tw("PIKACHU STYLES");
        context.fill(px0 + 6, py0 + 8, px0 + 9, py0 + 20, c(Theme.accent()));
        String status = dirty ? "nicht angewendet" : packs.size() + " Packs gefunden";
        int statusX = px0 + 14 + titleTw + 12;
        if (statusX + tw(status) < sfx - 6) {
            txt(context, status, statusX, py0 + 10, c(dirty ? a(Theme.accentText(), 0.55f + 0.45f * pulse) : Theme.muted()));
        }

        // Suchleiste
        boolean sf = search != null && search.isFocused();
        float sfa = anim("search", sf);
        panel(context, sfx, sfy, sfw, 18, c(mix(Theme.field(), Theme.fieldFocus(), sfa)), c(mix(Theme.border(), Theme.accent(), sfa)));
        if (search != null) search.render(context, mouseX, mouseY, delta);

        // Reiter
        String hoverName = null;
        drawThemeSwitch(context, mouseX, mouseY, now);
        if (interactive() && inside(mouseX, mouseY, themeX, py0 + 6, THEME_W, 16)) {
            hoverName = Theme.dark() ? "Zum hellen Design wechseln" : "Zum dunklen Design wechseln";
        }
        int n = Category.ALL.size();
        for (int i = 0; i < n; i++) {
            Category c = Category.ALL.get(i);
            int x = tabStartX + (i % tabsPerRow) * (tabSize + 2);
            int y = tabY + (i / tabsPerRow) * (tabSize + 2);
            boolean sel = i == tab;
            boolean hov = interactive() && inside(mouseX, mouseY, x, y, tabSize, tabSize);
            if (hov) hoverName = c.name();
            float h = anim(kTab[i], hov);
            float s = anim(kTabSel[i], sel);
            boolean match = i < tabMatch.length && tabMatch[i];
            float te = easeOut((now - openStart - i * 9L) / 240f) * gA;
            float dim = anim(kTabDim[i], !match);
            int fill = mix(mix(Theme.card(), Theme.cardHover(), h), Theme.accentFill(), s);
            panel(context, x, y, tabSize, tabSize, a(fill, te), a(mix(mix(Theme.border(), Theme.hoverBorder(), h), Theme.accent(), s), te));
            int hop = Math.round(h * 1.5f * (1f - s));
            int ix = x + (tabSize - 16) / 2;
            int iy = y + (tabSize - 16) / 2 - hop;
            drawIcon(context, c.id(), ix, iy);
            veil(context, ix, iy, 16, 16, fill, te * (1f - 0.65f * dim));
        }
        // gleitender Unterstrich unter dem aktiven Reiter
        {
            float tx = tabStartX + (tab % tabsPerRow) * (tabSize + 2);
            float ty = tabY + (tab / tabsPerRow) * (tabSize + 2);
            if (indX < 0) {
                indX = tx;
                indY = ty;
            }
            float kk = k(14f);
            indX += (tx - indX) * kk;
            indY += (ty - indY) * kk;
            int ux = Math.round(indX);
            int uy = Math.round(indY) + tabSize - 3;
            context.fill(ux + 2, uy, ux + tabSize - 2, uy + 2, c(Theme.accent()));
        }

        // Titelzeilen
        int count = Math.max(0, entryCount - 1 - (isCrosshair() ? 1 : 0));
        int titleX = blocks ? px0 + 12 : gridX;
        String title;
        if (blocks) {
            if (group == null) {
                title = "BLÖCKE  (" + visible.size() + ")";
            } else {
                String fl = face == null ? "" : "  ·  " + faceLabel(face).toUpperCase(Locale.ROOT);
                title = "BLÖCKE: " + group.title().toUpperCase(Locale.ROOT) + fl + "  (" + count + ")";
            }
        } else {
            title = cat.name().toUpperCase(Locale.ROOT) + "  (" + count + ")";
        }
        int maxW = blocks ? Math.max(40, this.width - titleX * 2)
                : Math.max(40, this.width - 24 - topRowW - titleX);
        float tAnim = easeOut((now - contentStart) / 220f);
        txt(context, fit(title, maxW), titleX + Math.round((1f - tAnim) * 6f * (slideDir == 0 ? 1 : slideDir)), subY,
                c(a(Theme.text(), 0.35f + 0.65f * tAnim)));

        // Blockliste
        if (blocks) drawBlockList(context, mouseX, mouseY, now);

        // Karten
        int idx = 0;
        for (Card cd : cards) {
            if (interactive() && inside(mouseX, mouseY, cd.x(), cd.y(), CW, cardH)) {
                hoverName = cd.pack() == null ? standardLabel() : label(cd.pack().name());
                if (v.isSound()) hoverName += "  ·  Klick = auswählen und anhören";
            }
            drawCard(context, cd, idx++, cat, v, active, cardH, mouseX, mouseY, now);
        }
        if (blocks && group == null) {
            String q = query();
            txt(context, q.isEmpty() ? "Keine Block-Texturen in deinen Packs gefunden."
                    : "Keine Treffer für \"" + q + "\"", gridX, listY + 4, c(Theme.muted()));
        }

        String line2 = hoverName != null ? hoverName
                : "Aktiv: " + (active == null ? standardLabel() : label(active));
        int line2Color = hoverName != null ? Theme.accentText() : Theme.muted();
        if (resetArmed) {
            line2 = "Alle Auswahlen von Profil \"" + MixerConfig.get().activeProfile + "\" zurücksetzen? Bestätigen klicken.";
            line2Color = Theme.danger();
        }
        if (!line2.equals(shownLine2)) {
            shownLine2 = line2;
            line2At = now;
        }
        float l2 = easeOut((now - line2At) / 140f);
        txt(context, fit(line2, resetArmed ? Math.max(maxW, 300) : maxW), titleX + Math.round((1f - l2) * 5f),
                subY + 11, c(a(line2Color, 0.4f + 0.6f * l2)));

        // Bedienelemente
        for (Ctl ct : ctls) {
            drawCtl(context, ct, mouseX, mouseY, interactive());
        }

        // "Anwenden" pulsiert, solange es ungespeicherte Änderungen gibt
        if (dirty && interactive()) {
            border(context, applyX - 1, applyY - 1, applyW + 2, 20, c(a(Theme.accent(), 0.2f + 0.55f * pulse)));
        }

        // Seitenanzeige
        int fy = py1 - 24;
        ctxt(context, (page + 1) + "/" + pages, px0 + 12 + 20 + 30, fy + 5, c(Theme.muted()));

        if (profOpen) drawPopup(context, mouseX, mouseY, delta);
        mtx.popMatrix();
    }

    /** Dark/Light-Schalter: gleitender Knopf (Sonne wird zum Mond), Sterne im dunklen Zustand. */
    private void drawThemeSwitch(DrawContext ctx, int mx, int my, long now) {
        int x = themeX;
        int y = py0 + 6;
        int w = THEME_W;
        int h = 16;
        boolean hov = interactive() && inside(mx, my, x, y, w, h);
        float hv = anim("themeSwitch", hov);
        float d = Theme.blend();

        int track = mix(0xFFFFE9A0, 0xFF3A3524, d);
        panel(ctx, x, y, w, h, c(track), c(mix(Theme.border(), Theme.accent(), Math.max(hv, 0.25f * (1f - d)))));

        // Sterne (nur dunkel), funkeln leicht
        float tw1 = 0.5f + 0.5f * (float) Math.sin(now / 380.0);
        float tw2 = 0.5f + 0.5f * (float) Math.sin(now / 520.0 + 1.7);
        ctx.fill(x + 6, y + 4, x + 7, y + 5, c(a(0xFFFFF3C4, d * (0.4f + 0.6f * tw1))));
        ctx.fill(x + 10, y + 10, x + 11, y + 11, c(a(0xFFFFF3C4, d * (0.4f + 0.6f * tw2))));
        ctx.fill(x + 4, y + 11, x + 5, y + 12, c(a(0xFFFFF3C4, d * (0.3f + 0.5f * tw2))));

        // Knopf
        int kx = x + 2 + Math.round(d * (w - 16));
        int ky = y + 2;
        int knob = mix(Theme.accent(), 0xFFEDE8D6, d);
        panel(ctx, kx, ky, 12, 12, c(knob), c(mix(0xFFE0A800, 0xFFB9B39A, d)));
        // Sonne: Kern + kurze Strahlen, die beim Wechsel einziehen
        float sun = 1f - d;
        if (sun > 0.05f) {
            ctx.fill(kx + 4, ky + 4, kx + 8, ky + 8, c(a(0xFFE59A00, sun)));
            ctx.fill(kx + 5, ky + 1, kx + 7, ky + 2, c(a(0xFFE59A00, sun)));
            ctx.fill(kx + 5, ky + 10, kx + 7, ky + 11, c(a(0xFFE59A00, sun)));
            ctx.fill(kx + 1, ky + 5, kx + 2, ky + 7, c(a(0xFFE59A00, sun)));
            ctx.fill(kx + 10, ky + 5, kx + 11, ky + 7, c(a(0xFFE59A00, sun)));
        }
        // Mond: Krater und Ausschnitt in Spurfarbe
        if (d > 0.05f) {
            ctx.fill(kx + 3, ky + 5, kx + 5, ky + 7, c(a(0xFFC9C3AA, d)));
            ctx.fill(kx + 5, ky + 7, kx + 6, ky + 8, c(a(0xFFC9C3AA, d)));
            ctx.fill(kx + 7, ky + 2, kx + 11, ky + 6, c(a(track, d)));
        }
    }

    private void drawPopup(DrawContext ctx, int mx, int my, float delta) {
        MixerConfig cfg = MixerConfig.get();
        float pa = anim("pop", true) * gA;
        ctx.fill(0, 0, this.width, this.height, a(0x70000000, pa));
        panel(ctx, popX, popY, popW, popH, a(Theme.header(), pa), a(Theme.accent(), pa));
        txt(ctx, "PROFILE", popX + 10, popY + 11, a(Theme.text(), pa));
        String cnt = profNames.size() + "/" + MixerConfig.MAX_PROFILES;
        txt(ctx, cnt, popX + popW - 10 - tw(cnt), popY + 11, a(Theme.muted(), pa));

        int n = profNames.size();
        boolean canDel = n > 1;
        int rowW = popW - 16 - (canDel ? 64 : 0);
        int y = popY + 30;
        for (String name : profNames) {
            boolean act = name.equals(cfg.activeProfile);
            boolean hov = inside(mx, my, popX + 8, y, rowW, 20);
            float h = anim("prof:" + name, hov);
            float s = anim("profsel:" + name, act);
            int fill = mix(mix(Theme.card(), Theme.cardHover(), h), Theme.accentFill(), s);
            int bd = mix(mix(Theme.border(), Theme.hoverBorder(), h), Theme.accent(), s);
            panel(ctx, popX + 8, y, rowW, 20, a(fill, pa), a(bd, pa));
            String shown = fit(name, rowW - 70);
            txt(ctx, shown, popX + 16, y + 6, a(Theme.text(), pa));
            String info = cfg.profileSize(name) + " Änderungen";
            int iw = tw(info);
            txt(ctx, info, popX + 8 + rowW - 8 - iw, y + 6, a(Theme.muted(), pa));
            y += 22;
        }

        boolean nf = nameField != null && nameField.isFocused();
        float na = anim("namefield", nf);
        panel(ctx, popX + 8, nameFieldY, popW - 16, 18, a(mix(Theme.field(), Theme.fieldFocus(), na), pa),
                a(mix(Theme.border(), Theme.accent(), na), pa));
        if (nameField != null) nameField.render(ctx, mx, my, delta);

        for (Ctl ct : popCtls) drawCtl(ctx, ct, mx, my, !closing);
    }

    private String faceLabel(String tex) {
        if (group != null) {
            for (BlockGroups.Face f : group.faces()) {
                if (f.tex().equals(tex)) return f.label();
            }
        }
        return pretty(tex);
    }

    private void drawBlockList(DrawContext ctx, int mouseX, int mouseY, long now) {
        int h = listH;
        panel(ctx, listX - 1, listY - 1, LIST_W + 2, h + 2, c(Theme.card()), c(Theme.border()));
        int sp = Math.round(scrollPx);
        float la = easeOut((now - contentStart) / 260f) * gA;

        ctx.enableScissor(listX, listY, listX + LIST_W, listY + h);

        // gleitende Markierung
        int selIdx = -1;
        for (int i = 0; i < visible.size(); i++) {
            if (group != null && visible.get(i).base().equals(group.base())) {
                selIdx = i;
                break;
            }
        }
        if (selIdx >= 0) {
            float target = selIdx * ROW_H;
            if (selHi < 0) selHi = target;
            selHi += (target - selHi) * k(18f);
            if (Math.abs(target - selHi) < 0.3f) selHi = target;
            int hy = listY + Math.round(selHi) - sp;
            ctx.fill(listX, hy, listX + LIST_W, hy + ROW_H - 1, c(Theme.accentFill()));
            ctx.fill(listX, hy, listX + 2, hy + ROW_H - 1, c(Theme.accent()));
        }

        int first = Math.max(0, sp / ROW_H);
        int last = Math.min(visible.size() - 1, (sp + h) / ROW_H);
        for (int i = first; i <= last; i++) {
            BlockGroups.Group g = visible.get(i);
            int y = listY + i * ROW_H - sp;
            boolean sel = i == selIdx;
            boolean rowHov = interactive() && inside(mouseX, mouseY, listX, y, LIST_W - 4, ROW_H - 1)
                    && mouseY >= listY && mouseY < listY + h;
            boolean dotsHov = rowHov && g.multi() && mouseX >= listX + LIST_W - DOTS_W - 4;
            float hv = anim("row:" + g.base(), rowHov && !sel);
            if (hv > 0.01f) ctx.fill(listX, y, listX + LIST_W, y + ROW_H - 1, a(Theme.cardHover(), hv * gA));

            int textMax = LIST_W - 12 - (g.multi() ? DOTS_W : 0);
            int color = sel ? Theme.text() : groupChosen(g) ? Theme.accentText() : Theme.text();
            txt(ctx, fit(g.title(), textMax), listX + 6 + Math.round(hv * 2), y + 3, a(color, la));

            if (g.multi()) {
                boolean open = sel && facesOpen;
                float dh = anim("dots:" + g.base(), dotsHov || open);
                int bx = listX + LIST_W - DOTS_W - 2;
                if (dh > 0.01f) ctx.fill(bx, y + 1, bx + DOTS_W - 2, y + ROW_H - 2, a(open ? Theme.accentFill() : Theme.rowHover(), dh * gA));
                int dc = mix(Theme.muted(), Theme.accentText(), dh);
                int dx = bx + (DOTS_W - 2 - 10) / 2;
                int dy = y + (ROW_H - 1) / 2 - 1;
                for (int k = 0; k < 3; k++) {
                    ctx.fill(dx + k * 4, dy, dx + k * 4 + 2, dy + 2, a(dc, gA));
                }
            }
        }

        // Scrollleiste
        float ms = maxScroll();
        if (ms > 0) {
            int total = visible.size() * ROW_H;
            int barH = Math.max(12, h * h / Math.max(1, total));
            int barY = listY + Math.round((h - barH) * (scrollPx / ms));
            ctx.fill(listX + LIST_W - 3, barY, listX + LIST_W - 1, barY + barH, c(Theme.scrollbar()));
        }
        ctx.disableScissor();
    }

    private void drawCtl(DrawContext ctx, Ctl ct, int mx, int my, boolean live) {
        boolean en = ct.enabled().getAsBoolean();
        boolean sel = ct.selected().getAsBoolean();
        boolean hov = en && live && inside(mx, my, ct.x(), ct.y(), ct.w(), ct.h());
        float h = anim(ct.key(), hov);
        float s = anim(ct.keyS(), sel);
        float d = anim(ct.keyD(), ct.danger().getAsBoolean());
        int base = mix(Theme.button(), Theme.cardHover(), h);
        int bg = !en ? Theme.disabledFill()
                : mix(mix(base, Theme.accent(), s), mix(Theme.dangerFill(), Theme.dangerFillHover(), h), d);
        int bd = !en ? Theme.border()
                : mix(mix(mix(Theme.border(), Theme.hoverBorder(), h), Theme.accent(), s), Theme.danger(), d);
        panel(ctx, ct.x(), ct.y(), ct.w(), ct.h(), c(bg), c(bd));
        if (en && h > 0.02f && s < 0.5f && d < 0.5f) {
            // beim Überfahren wächst von der Mitte eine Gold-Linie am unteren Rand
            int lw = Math.round((ct.w() - 8) * h);
            int lx = ct.x() + (ct.w() - lw) / 2;
            ctx.fill(lx, ct.y() + ct.h() - 2, lx + lw, ct.y() + ct.h() - 1, c(a(Theme.accent(), h)));
        }
        int tc = !en ? Theme.disabledText()
                : mix(mix(Theme.text(), Theme.onAccent(), s), Theme.danger(), d);
        ctxt(ctx, fit(ct.label().get(), ct.w() - 6), ct.x() + ct.w() / 2, ct.y() + (ct.h() - 8) / 2, c(tc));
    }

    private void drawCard(DrawContext context, Card cd, int index, Category cat, Category.Variant v, String activeSel,
                          int cardH, int mouseX, int mouseY, long now) {
        PackScanner.PackInfo pack = cd.pack();
        boolean blocks = isBlocks();
        boolean selected;
        if (blocks) {
            selected = blockSelected(pack);
        } else {
            selected = pack == null ? activeSel == null : pack.name().equals(activeSel);
        }
        String key = cd.key();

        // Erscheinen: gestaffelt einblenden, nach oben gleiten (beim Blättern von der Seite)
        float e = easeOut((now - contentStart - index * 22L) / 240f);
        float ea = e * gA;

        boolean hov = interactive() && inside(mouseX, mouseY, cd.x(), cd.y(), CW, cardH);
        float h = anim(cd.kh(), hov);
        float s = anim(cd.ks(), selected);

        // Beim Überfahren hebt sich die Karte leicht an und wirft einen Schatten
        int lift = Math.round(h * 2f);
        int cx = cd.x() + Math.round((1f - e) * 16f * slideDir);
        int cy = cd.y() + Math.round((1f - e) * 10f) - lift;
        if (lift > 0) {
            context.fill(cx + 2, cy + cardH, cx + CW - 2, cy + cardH + lift + 1, a(0xFF000000, (0.12f + 0.16f * Theme.blend()) * h * ea));
        }

        int fill = mix(mix(Theme.card(), Theme.cardHover(), h), Theme.accentFill(), s);
        int bd = mix(mix(Theme.border(), Theme.hoverBorder(), h), Theme.accent(), s);
        if (h > 0.02f) {
            border(context, cx - 1, cy - 1, CW + 2, cardH + 2, a(Theme.accent(), 0.22f * h * ea));
        }
        panel(context, cx, cy, CW, cardH, a(fill, ea), a(bd, ea));
        if (s > 0.01f) {
            // die gewählte Karte "atmet" leicht
            float breathe = 0.7f + 0.3f * (float) Math.sin(now / 420.0);
            border(context, cx + 1, cy + 1, CW - 2, cardH - 2, a(Theme.accent(), ea * s * breathe));
        }

        // Aufblitzen nach dem Anklicken
        Long fl = flashes.get(key);
        if (fl != null) {
            float t = (now - fl) / 380f;
            if (t >= 1f) flashes.remove(key);
            else context.fill(cx + 1, cy + 1, cx + CW - 1, cy + cardH - 1, a(Theme.accent(), 0.28f * (1f - t) * gA));
        }

        int size = PreviewCache.SIZE;
        Category.Variant pv = v;
        if (blocks && pack != null && group != null) {
            String tex = face != null ? face : group.rep(pack.blocks());
            pv = Category.blockVariant(tex);
        }
        boolean bodyView = pv.isBody();
        int previewH = bodyView ? BodyView.H + 2 : size;
        int px = cx + (CW - size) / 2;
        int py = cy + 5;
        int previewBg = Theme.preview();
        if (bodyView) {
            context.fillGradient(px, py, px + size, py + previewH, a(Theme.previewTop(), ea), a(Theme.previewBottom(), ea));
        } else {
            context.fill(px, py, px + size, py + previewH, a(previewBg, ea));
        }

        String name;
        if (pv.isSound()) {
            // Totem-Sound: Totem mit Klangbalken, Klick = auswählen und abspielen
            name = pack == null ? standardLabel() : label(pack.name());
            drawSoundPreview(context, cd, pack, px, py, size, h, s, ea, now);
        } else if (bodyView) {
            // Body: Spieler-Skin mit der Rüstung des Packs (Standard = Vanilla-Rüstung)
            name = pack == null ? standardLabel() : label(pack.name());
            PreviewCache.Body body = PreviewCache.peekBody(pack, cat, pv);
            if (body == null && loadBudget > 0) {
                loadBudget--;
                body = PreviewCache.loadBody(pack, cat, pv);
            }
            if (body == null) {
                shimmer(context, px, py, size, previewH, now, ea);
            } else if (body.isEmpty()) {
                ctxt(context, "-", px + size / 2, py + previewH / 2 - 4, a(Theme.faint(), ea));
            } else {
                BodyView.draw(context, px + (size - BodyView.W) / 2, py + 1, body);
                veil(context, px, py, size, previewH, Theme.preview(), ea * anim(cd.kp(), true));
            }
        } else if (pack == null) {
            name = standardLabel();
            ItemStack[] set = pv.isArmorItems() ? armorSets.get(cat.id() + ":" + pv.material()) : null;
            if (set != null) {
                // Item: Helm, Brustplatte, Hose, Stiefel als 2x2
                int ox = px + (size - 32) / 2;
                int oy = py + (size - 32) / 2;
                for (int i = 0; i < 4; i++) {
                    context.drawItem(set[i], ox + (i % 2) * 16, oy + (i / 2) * 16);
                }
            } else {
                drawIcon(context, cat.id(), px + (size - 16) / 2, py + (size - 16) / 2);
            }
            veil(context, px, py, size, size, previewBg, ea);
        } else if (pack == CUSTOM) {
            // Eigenes Fadenkreuz: direkt aus der Config gezeichnet, doppelt so groß wie im Spiel
            name = label(pack.name());
            CustomCrosshair cc = MixerConfig.get().crosshair;
            int s2 = 2;
            int size2 = CustomCrosshair.N * s2;
            CrosshairHud.draw(context, px + (size - size2) / 2, py + (size - size2) / 2, cc.pixels, s2);
            veil(context, px, py, size, size, previewBg, ea);
        } else {
            name = label(pack.name());
            PreviewCache.Preview pr = PreviewCache.peek(pack, cat, pv);
            if (pr == null && loadBudget > 0) {
                loadBudget--;
                pr = PreviewCache.load(pack, cat, pv);
            }
            if (pr == null) {
                shimmer(context, px, py, size, size, now, ea);
            } else if (pr.id() == null) {
                ctxt(context, "-", px + size / 2, py + size / 2 - 4, a(Theme.faint(), ea));
            } else {
                float vOff = pr.currentFrame() * pr.frameH();
                context.drawTexture(RenderPipelines.GUI_TEXTURED, pr.id(),
                        px + (size - pr.dw()) / 2, py + (size - pr.dh()) / 2,
                        0f, vOff, pr.dw(), pr.dh(), pr.frameW(), pr.frameH(), pr.texW(), pr.texH());
                // das fertig geladene Vorschaubild blendet weich ein
                veil(context, px, py, size, size, previewBg, ea * anim(cd.kp(), true));
            }
        }

        ctxt(context, fit(name, CW - 6), cx + CW / 2, cy + 5 + previewH + 6, a(mix(Theme.text(), Theme.accentText(), s), ea));

        // Haken-Plakette: poppt beim Auswählen auf
        if (s > 0.02f) {
            int bs = Math.round(11 * s);
            if (bs >= 3) {
                int bx = cx + CW - 10 - bs / 2;
                int by = cy + 9 - bs / 2;
                panel(context, bx, by, bs, bs, a(Theme.accent(), ea), a(0xFFE0A800, ea * s));
                if (s > 0.85f) {
                    int ck = a(Theme.onAccent(), ea);
                    int ox = cx + CW - 15;
                    int oy = cy + 4;
                    context.fill(ox + 2, oy + 5, ox + 3, oy + 7, ck);
                    context.fill(ox + 3, oy + 6, ox + 4, oy + 8, ck);
                    context.fill(ox + 4, oy + 5, ox + 5, oy + 7, ck);
                    context.fill(ox + 5, oy + 4, ox + 6, oy + 6, ck);
                    context.fill(ox + 6, oy + 3, ox + 7, oy + 5, ck);
                    context.fill(ox + 7, oy + 2, ox + 8, oy + 4, ck);
                }
            }
        }
    }

    /** Ladeplatzhalter: ein heller Streifen wandert über die Fläche. */
    private void shimmer(DrawContext ctx, int x, int y, int w, int h, long now, float ea) {
        ctx.enableScissor(x, y, x + w, y + h);
        float t = (now % 1100L) / 1100f;
        int bx = x - 18 + Math.round(t * (w + 36));
        ctx.fill(bx, y, bx + 10, y + h, a(Theme.shimmer(), 0.55f * ea));
        ctx.fill(bx + 10, y, bx + 18, y + h, a(Theme.shimmer(), 0.3f * ea));
        ctx.disableScissor();
    }

    /** Vorschau der Totem-Sound-Karte: Totem, Klangbalken (tanzen beim Abspielen), Play/Pause-Symbol und Fortschritt. */
    private void drawSoundPreview(DrawContext ctx, Card cd, PackScanner.PackInfo pack, int px, int py, int size,
                                  float h, float s, float ea, long now) {
        boolean playing = SoundPreview.isPlaying(pack);
        float pf = anim(cd.kp(), playing);
        float prog = SoundPreview.progress(pack);

        // sanftes Leuchten hinter dem Totem, solange der Sound läuft
        if (pf > 0.01f) {
            float glow = 0.6f + 0.4f * (float) Math.sin(now / 160.0);
            ctx.fill(px + size / 2 - 13, py + 3, px + size / 2 + 13, py + 24, a(Theme.accent(), 0.22f * pf * glow * ea));
        }
        ctx.drawItem(icons.get(cat().id()), px + (size - 16) / 2, py + 6);
        veil(ctx, px, py, size, size, Theme.preview(), ea);

        // Klangbalken
        int bars = 9;
        int bw = 3;
        int gap = 1;
        int total = bars * bw + (bars - 1) * gap;
        int bx = px + (size - total) / 2;
        int baseY = py + size - 6;
        int col = mix(mix(Theme.soundBarIdle(), Theme.soundBarHover(), h), Theme.accent(), Math.max(pf, s * 0.6f));
        for (int i = 0; i < bars; i++) {
            double wave = 0.5 + 0.5 * Math.sin(now / 85.0 + i * 1.25) * Math.sin(now / 230.0 + i * 0.7 + 1.0);
            float idle = 1.5f + 1.2f * (float) (0.5 + 0.5 * Math.sin(now / 700.0 + i * 0.9));
            float bh = idle + pf * (2f + 8f * (float) wave) * (1f - 0.35f * prog);
            int x = bx + i * (bw + gap);
            ctx.fill(x, baseY - Math.round(bh), x + bw, baseY, a(col, ea));
        }

        // Play-Dreieck bzw. Pause-Symbol oben links
        float hi = Math.max(h, pf);
        int tc = a(mix(Theme.muted(), Theme.accentText(), hi), ea * (0.45f + 0.55f * hi));
        int tx = px + 4;
        int ty = py + 4;
        if (playing) {
            ctx.fill(tx, ty, tx + 2, ty + 7, tc);
            ctx.fill(tx + 4, ty, tx + 6, ty + 7, tc);
        } else {
            for (int r = 0; r < 7; r++) {
                ctx.fill(tx, ty + r, tx + Math.min(r, 6 - r) + 1, ty + r + 1, tc);
            }
        }

        // Fortschrittsstreifen am unteren Rand
        if (playing) {
            ctx.fill(px, py + size - 2, px + Math.round(size * prog), py + size, a(Theme.accent(), ea));
        }
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void border(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y + 1, x + 1, y + h - 1, color);
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Fläche mit 1 px Rand und abgeschrägten (gekappten) Ecken. */
    private static void panel(DrawContext c, int x, int y, int w, int h, int fill, int border) {
        if (w < 6 || h < 6) {
            c.fill(x, y, x + w, y + h, border);
            return;
        }
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        c.fill(x + 2, y, x + w - 2, y + 1, border);
        c.fill(x + 2, y + h - 1, x + w - 2, y + h, border);
        c.fill(x, y + 2, x + 1, y + h - 2, border);
        c.fill(x + w - 1, y + 2, x + w, y + h - 2, border);
        c.fill(x + 1, y + 1, x + 2, y + 2, border);
        c.fill(x + w - 2, y + 1, x + w - 1, y + 2, border);
        c.fill(x + 1, y + h - 2, x + 2, y + h - 1, border);
        c.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, border);
    }

    private String fit(String s, int maxW) {
        if (tw(s) <= maxW) return s;
        String ell = "..";
        while (s.length() > 1 && tw(s + ell) > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ell;
    }

    private static String stripZip(String name) {
        return name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    // ---------- Taste zum Öffnen ----------

    private static String keyName(int key) {
        if (key < 0) return "Nicht belegt";
        String n = KEY_NAMES.get(key);
        return n != null ? n : "Taste " + key;
    }

    private static Map<Integer, String> buildKeyNames() {
        Map<Integer, String> map = new HashMap<>();
        for (Field f : GLFW.class.getFields()) {
            String n = f.getName();
            if (!n.startsWith("GLFW_KEY_") || f.getType() != int.class) continue;
            String shortName = n.substring("GLFW_KEY_".length());
            if (shortName.equals("LAST") || shortName.equals("UNKNOWN")) continue;
            try {
                map.putIfAbsent(f.getInt(null), shortName);
            } catch (IllegalAccessException ignored) {
            }
        }
        return map;
    }

    /**
     * ESC: Bei der Tastenwahl nichts schließen; ist die Suchleiste aktiv, leert/verlässt ESC zuerst
     * die Suche (shouldCloseOnEsc wird nur bei ESC gefragt).
     */
    @Override
    public boolean shouldCloseOnEsc() {
        if (listening) return false;
        if (profOpen) {
            profOpen = false;
            deleteArmed = null;
            needsRebuild = true;
            return false;
        }
        if (resetArmed) {
            resetArmed = false;
            needsRebuild = true;
            return false;
        }
        if (search != null && search.isFocused()) {
            this.setFocused(null);
            search.setFocused(false);
            if (!search.getText().isEmpty()) search.setText("");
            return false;
        }
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (resetArmed && nowMs() - resetAt > 6000) {
            resetArmed = false;
            needsRebuild = true;
        }
        if (closing && nowMs() - closeStart >= CLOSE_MS) {
            finishClose();
            return;
        }
        if (!listening || this.client == null) return;

        long handle = this.client.getWindow().getHandle();
        for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) {
            if (GLFW.glfwGetKey(handle, k) != GLFW.GLFW_PRESS) continue;
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                // Abbruch, Belegung bleibt wie sie war
            } else if (k == GLFW.GLFW_KEY_DELETE || k == GLFW.GLFW_KEY_BACKSPACE) {
                MixerConfig.get().openKey = -1;
                MixerConfig.get().save();
            } else {
                MixerConfig.get().openKey = k;
                MixerConfig.get().save();
            }
            listening = false;
            return;
        }
    }

    /** Beim Verlassen des Menüs: Sound stoppen, offene Zip-Dateien freigeben. */
    @Override
    public void removed() {
        SoundPreview.stop();
        PackScanner.closeCaches();
        PreviewCache.clear(); // keine Textur-Reste über das Neuladen der Ressourcen hinweg
        super.removed();
    }

    /**
     * Kein Vanilla-Hintergrund (Weichzeichner/Abdunkelung): Das Menü zeichnet seinen eigenen Schleier mit
     * der Ein-/Ausblend-Transparenz. Sonst verschwindet der Weichzeichner beim Schließen schlagartig.
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    /** Startet die Ausblend-Animation, geschlossen wird danach in tick(). */
    @Override
    public void close() {
        if (closing) return;
        closing = true;
        closeStart = nowMs();
    }

    private void finishClose() {
        closing = false;
        boolean apply = dirty;
        dirty = false;
        // erst das Menü sauber schließen, das Pack wird danach im Hintergrund gebaut
        if (this.client != null) this.client.setScreen(parent);
        if (apply) PackApplier.applyAsync();
    }
}
