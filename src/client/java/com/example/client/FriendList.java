package com.snakepvp;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FriendList {
    static final List<String> names = new ArrayList<>();
    static final List<Boolean> safe = new ArrayList<>();

    // PvP list: ek time par sirf ek ON. null = koi nahi
    // Naye PvP yahan add karte jana: "MACE" ...
    static final String[] PVP_NAMES = { "SWORD", "NETHPOT" };
    static String selectedPvp = null;

    // ---------------------------------------------------------------- friend data
    static boolean isSafe(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) return safe.get(i);
        }
        return false;
    }

    static void addName(String name) {
        name = name.trim();
        if (name.isEmpty()) return;
        for (String n : names) if (n.equalsIgnoreCase(name)) return;
        names.add(name);
        safe.add(true);
        save();
    }

    static void removeName(int i) {
        names.remove(i);
        safe.remove(i);
        save();
    }

    static void toggleName(int i) {
        safe.set(i, !safe.get(i));
        save();
    }

    // ---------------------------------------------------------------- save / load
    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("snakepvp_friends.txt");
    }

    static void load() {
        names.clear();
        safe.clear();
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] p = line.split(",");
                if (p.length < 2 || p[0].isBlank()) continue;
                names.add(p[0].trim());
                safe.add(Boolean.parseBoolean(p[1].trim()));
            }
        } catch (IOException ignored) { }
    }

    static void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) lines.add(names.get(i) + "," + safe.get(i));
            Files.write(file(), lines);
        } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- menu (C dabane pe)
    public static class Menu extends Screen {
        private String draft = "";
        private EditBox input;

        public Menu() {
            super(Component.literal("Snake PvP Menu"));
        }

        @Override
        public boolean isPauseScreen() { return false; }

        @Override
        protected void init() {
            int leftX = this.width / 2 - 200;
            int rightX = this.width / 2 + 20;

            input = new EditBox(this.font, leftX, 30, 170, 20, Component.literal("name"));
            input.setValue(draft);
            this.addRenderableWidget(input);
            this.setInitialFocus(input);

            this.addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
                addName(input.getValue());
                draft = "";
                this.rebuildWidgets();
            }).bounds(leftX + 175, 30, 50, 20).build());

            for (int i = 0; i < names.size(); i++) {
                final int idx = i;
                int y = 60 + i * 24;
                this.addRenderableWidget(Button.builder(
                        Component.literal(safe.get(idx) ? "ON" : "OFF"), b -> {
                            draft = input.getValue();
                            toggleName(idx);
                            this.rebuildWidgets();
                        }).bounds(leftX + 130, y, 50, 20).build());
                this.addRenderableWidget(Button.builder(
                        Component.literal("Hata"), b -> {
                            draft = input.getValue();
                            removeName(idx);
                            this.rebuildWidgets();
                        }).bounds(leftX + 185, y, 50, 20).build());
            }

            for (int i = 0; i < PVP_NAMES.length; i++) {
                final String pvp = PVP_NAMES[i];
                int y = 60 + i * 24;
                boolean on = pvp.equals(selectedPvp);
                this.addRenderableWidget(Button.builder(
                        Component.literal((on ? "ON  " : "OFF ") + pvp), b -> {
                            draft = input.getValue();
                            selectedPvp = pvp.equals(selectedPvp) ? null : pvp;
                            this.rebuildWidgets();
                        }).bounds(rightX, y, 170, 20).build());
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int leftX = this.width / 2 - 200;
            int rightX = this.width / 2 + 20;

            g.drawString(this.font, "Friend list (ON = safe, attack nahi)", leftX, 18, 0xFFFFFF);
            for (int i = 0; i < names.size(); i++) {
                g.drawString(this.font, names.get(i), leftX, 66 + i * 24, 0xFFFFFF);
            }

            g.drawString(this.font, "PvP select (ek hi ON)", rightX, 18, 0xFFFFFF);
            String cur = selectedPvp == null ? "koi nahi" : selectedPvp;
            g.drawString(this.font, "Abhi ON: " + cur, rightX, 60 + PVP_NAMES.length * 24 + 6, 0xFFFF55);
        }
    }
          }
