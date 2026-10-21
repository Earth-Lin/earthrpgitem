package EarthRPGItem.gui;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.EnhanceStationManager;
import EarthRPGItem.items.EnhancementManager;
import EarthRPGItem.items.GemstoneManager;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.skills.SkillBackpackManager;
import EarthRPGItem.skills.SkillLevelManager;
import EarthRPGItem.skills.SkillPanelManager;
import EarthRPGItem.skills.SkillPresetManager;
import EarthRPGItem.util.GuiSounds;
import EarthRPGItem.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RpgMenuListener implements Listener {
    private final EarthRPGItem plugin;
    public RpgMenuListener(EarthRPGItem plugin) { this.plugin = plugin; }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RpgMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        RpgMenu menu = new RpgMenu(plugin);
        int slot = event.getRawSlot();
        // 顶栏（本插件的界面）每次点击都给一声轻响，保证「每个按钮都有反馈」；
        // 下面的具体操作再按结果叠加成功/失败/放入/取出音，两者不冲突。
        if (slot >= 0 && slot < event.getInventory().getSize()) GuiSounds.click(player);
        // The backpack needs finer control: clicks in the player's own inventory must stay
        // usable, otherwise a cancelled event prevents picking items up at all.
        if (holder.getMenuType() == RpgMenuType.SKILL_BACKPACK) {
            handleSkillBackpackClick(menu, player, holder, event, slot);
            return;
        }
        // The station also needs the player's own inventory to stay clickable, for the same reason
        // the backpack does: the material has to be picked up onto the cursor before it can be
        // handed over, and a blanket cancel would make that impossible.
        if (holder.getMenuType() == RpgMenuType.ENHANCE) {
            handleEnhanceClick(menu, player, event, slot);
            return;
        }
        // The gem station needs the same thing: the gem has to be picked up onto the cursor before it
        // can be socketed, so a blanket cancel would make socketing impossible.
        if (holder.getMenuType() == RpgMenuType.GEMS) {
            handleGemClick(menu, player, event, slot);
            return;
        }
        event.setCancelled(true);
        switch (holder.getMenuType()) {
            case MAIN -> handleMainClick(menu, player, slot);
            case SETTING -> handleSettingClick(menu, player, holder, slot);
            case GROUPS -> handleGroupsClick(menu, player, holder, slot);
            case CATEGORY -> handleCategoryClick(menu, player, holder, slot);
            case COLLECTION -> handleCollectionClick(menu, player, holder, slot);
            case SKILL_PANEL -> handleSkillPanelClick(menu, player, event, slot);
            case SKILL_PRESET -> handleSkillPresetClick(menu, player, holder, event, slot);
            case SKILL_ITEMS -> handleSkillItemsClick(menu, player, holder, slot);
            case SKILL_LIBRARY -> handleSkillLibraryClick(menu, player, holder, slot);
            case SKILL_FORGE -> handleSkillForgeClick(menu, player, holder, event, slot);
            case SKILL_FORGE_DETAIL -> handleSkillForgeDetailClick(menu, player, holder, slot);
            case SKILL_DELETE_CONFIRM -> handleSkillDeleteConfirmClick(menu, player, holder, slot);
            default -> { }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof RpgMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String id = plugin.getSkillPanelManager().registeredSkillId(event.getOldCursor());
        if (id == null) return;
        if (holder.getMenuType() == RpgMenuType.SKILL_BACKPACK) {
            if (storeSkill(player, plugin.getSkillBackpackManager(), id)) player.setItemOnCursor(null);
            Bukkit.getScheduler().runTask(plugin, () -> new RpgMenu(plugin)
                    .openSkillBackpack(player, backpackMode(holder), holder.getPage()));
            return;
        }
        if (holder.getMenuType() != RpgMenuType.SKILL_PANEL) return;
        for (int rawSlot : event.getRawSlots()) {
            int offset = rawSlot - RpgMenu.PANEL_SLOT_BASE;
            if (offset < 0 || offset >= SkillPanelManager.TRIGGERS.size()) continue;
            String trigger = SkillPanelManager.TRIGGERS.get(offset);
            SkillPanelManager.EquipResult result = plugin.getSkillPanelManager().equip(player, trigger, id);
            player.sendMessage(equipMessage(player, result, id, trigger));
            Bukkit.getScheduler().runTask(plugin, () -> new RpgMenu(plugin).openSkillPanel(player));
            return;
        }
    }

    private net.kyori.adventure.text.Component equipMessage(Player player, SkillPanelManager.EquipResult result, String id, String trigger) {
        SkillPanelManager panel = plugin.getSkillPanelManager();
        // 装备结果统一在这里发声：所有「装技能」的入口都会经过本方法。
        if (result == SkillPanelManager.EquipResult.OK) GuiSounds.place(player);
        else GuiSounds.fail(player);
        return switch (result) {
            case OK -> {
                String hint = panel.weaponMismatchHint(player, id);
                yield Texts.color("&a已装备 &f" + id + " &a到 &f" + SkillPanelManager.triggerLabel(trigger)
                        + (hint == null ? "" : "\n&e提示：&f" + hint));
            }
            case ALREADY_EQUIPPED -> Texts.color("&c该技能已经装备在其他槽位，一种技能只能占用一个槽位");
            case GROUP_CONFLICT -> {
                String slot = panel.groupConflictSlot(player, trigger, id);
                String otherId = slot == null ? "其他槽位" : panel.getSkillId(player, slot);
                String slotName = slot == null ? "其他槽位" : SkillPanelManager.triggerLabel(slot);
                yield Texts.color("&c技能组冲突：&f" + panel.skillGroup(id) + " &c已被 &f" + slotName
                        + " &c的 &f" + otherId + " &c占用。同一个技能组同时装备会让两个键位都失效，"
                        + "请先清空该槽位，或把其中一个技能的 &fskill-group &c改成别的名字。");
            }
            case NOT_IN_BACKPACK -> Texts.color("&c该技能不在技能背包中，请先放入技能背包");
            case TRIGGER_UNSUPPORTED -> Texts.color("&c该技能不提供 &f"
                    + SkillPanelManager.triggerLabel(trigger) + " &c这个键位，无法装备到该槽位。"
                    + "请换一个槽位，或在该技能的 yml 里补上对应键位的技能段。");
            case INVALID_SKILL -> Texts.color("&c技能无效或注册定义缺失");
        };
    }

    private void handleMainClick(RpgMenu menu, Player player, int slot) {
        switch (slot) {
            case 20 -> menu.openSetting(player);
            case 22 -> menu.openGroups(player, RpgMenuType.ARMOR, 0);
            case 24 -> menu.openGroups(player, RpgMenuType.TOOL, 0);
            case 26 -> menu.openGroups(player, RpgMenuType.WEAPON, 0);
            case 28 -> menu.openGroups(player, RpgMenuType.ITEMS, 0);
            case 30 -> menu.openGroups(player, RpgMenuType.ACCESSORY, 0);
            case 32 -> menu.openCollectionGroups(player, 0);
            case 34 -> menu.openSkillPanel(player);
            case 36 -> menu.openSkillLibrary(player, 0);
            case 38 -> {
                if (!player.hasPermission(ENHANCE_PERMISSION)) {
                    player.sendMessage(Texts.color("&c你没有使用强化台的权限。"));
                    player.closeInventory();
                    return;
                }
                menu.openEnhance(player);
            }
            case 40 -> {
                if (!player.hasPermission(GEM_PERMISSION)) {
                    player.sendMessage(Texts.color("&c你没有使用宝石台的权限。"));
                    player.closeInventory();
                    return;
                }
                menu.openGems(player);
            }
            case 42 -> {
                if (!player.hasPermission(SKILL_FORGE_PERMISSION)) {
                    player.sendMessage(Texts.color("&c你没有使用技能强化台的权限。"));
                    player.closeInventory();
                    return;
                }
                menu.openSkillForge(player);
            }
            default -> { }
        }
    }

    private void handleSettingClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        if (slot == RpgMenu.BACK_SLOT) { menu.openMain(player); return; }
        int maxPage = Math.max(1, (int) Math.ceil(RpgMenu.SETTING_PATHS.length / (double) RpgMenu.PAGE_SIZE));
        if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) { menu.openSetting(player, holder.getPage() - 1); return; }
        if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) { menu.openSetting(player, holder.getPage() + 1); return; }
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
        if (slot < 0 || slot >= RpgMenu.PAGE_SIZE || index >= RpgMenu.SETTING_PATHS.length) return;
        String path = RpgMenu.SETTING_PATHS[index];
        plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path, true));
        plugin.saveConfig();
        menu.openSetting(player, holder.getPage());
    }

    private void handleGroupsClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        if ("COLLECTION".equals(holder.getGroup())) {
            var groups = plugin.getCollectionManager().getDefinitionGroups();
            int maxPage = Math.max(1, (int) Math.ceil(groups.size() / (double) RpgMenu.PAGE_SIZE));
            if (slot == RpgMenu.BACK_SLOT) { menu.openMain(player); return; }
            if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) { menu.openCollectionGroups(player, holder.getPage() - 1); return; }
            if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) { menu.openCollectionGroups(player, holder.getPage() + 1); return; }
            int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
            if (slot >= 0 && slot < RpgMenu.PAGE_SIZE && index < groups.size()) menu.openCollectionCategory(player, 0, groups.get(index));
            return;
        }
        RpgMenuType type = RpgMenuType.valueOf(holder.getGroup());
        var groups = plugin.getRpgItemManager().getItemGroups(type.getItemType());
        int maxPage = Math.max(1, (int) Math.ceil(groups.size() / (double) RpgMenu.PAGE_SIZE));
        if (slot == RpgMenu.BACK_SLOT) { menu.openMain(player); return; }
        if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) { menu.openGroups(player, type, holder.getPage() - 1); return; }
        if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) { menu.openGroups(player, type, holder.getPage() + 1); return; }
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
        if (slot >= 0 && slot < RpgMenu.PAGE_SIZE && index < groups.size()) menu.openCategory(player, type, 0, groups.get(index));
    }

    private void handleCategoryClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        String[] parts = holder.getGroup().split("\\n", 2);
        RpgMenuType type = RpgMenuType.valueOf(parts[0]);
        String group = parts.length > 1 ? parts[1] : "默认 TXT 物品";
        var items = plugin.getRpgItemManager().getItems(type.getItemType(), group);
        int maxPage = Math.max(1, (int) Math.ceil(items.size() / (double) RpgMenu.PAGE_SIZE));
        if (slot == RpgMenu.BACK_SLOT) { menu.openGroups(player, type, 0); return; }
        if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) { menu.openCategory(player, type, holder.getPage() - 1, group); return; }
        if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) { menu.openCategory(player, type, holder.getPage() + 1, group); return; }
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
        if (slot >= 0 && slot < RpgMenu.PAGE_SIZE && index < items.size()) player.getInventory().addItem(plugin.getRpgItemManager().createItem(items.get(index)));
    }

    private void handleSkillPanelClick(RpgMenu menu, Player player, InventoryClickEvent event, int slot) {
        if (slot == RpgMenu.PANEL_CLOSE_SLOT) { player.closeInventory(); return; }
        if (slot == RpgMenu.PANEL_BACKPACK_SLOT) { menu.openSkillBackpack(player, RpgMenu.BACKPACK_MANAGE, 0); return; }
        if (slot == RpgMenu.PANEL_PRESET_SLOT) { menu.openSkillPreset(player); return; }
        if (slot == RpgMenu.PANEL_FORGE_SLOT) {
            // 与主菜单的强化台入口同一道权限门。区别是这里<b>不关界面</b>：
            // 玩家已经在技能面板里，没权限时把人踢出整个面板太粗暴，提示一句就够了。
            if (!player.hasPermission(SKILL_FORGE_PERMISSION)) {
                player.sendMessage(Texts.color("&c你没有使用技能强化台的权限。"));
                return;
            }
            menu.openSkillForgeFromPanel(player);
            return;
        }
        if (slot == RpgMenu.PANEL_MASTER_SLOT) {
            plugin.getSkillPanelManager().setEnabled(player, !plugin.getSkillPanelManager().isEnabled(player));
            menu.openSkillPanel(player);
            return;
        }
        int triggerCount = SkillPanelManager.TRIGGERS.size();
        if (slot >= RpgMenu.PANEL_COOLDOWN_BASE && slot < RpgMenu.PANEL_COOLDOWN_BASE + triggerCount) {
            String trigger = SkillPanelManager.TRIGGERS.get(slot - RpgMenu.PANEL_COOLDOWN_BASE);
            boolean enabled = !plugin.getSkillPanelManager().isCooldownMessagesEnabled(player, trigger);
            plugin.getSkillPanelManager().setCooldownMessagesEnabled(player, trigger, enabled);
            player.sendMessage(Texts.color((enabled ? "&a" : "&c") + SkillPanelManager.triggerLabel(trigger)
                    + (enabled ? " 冷却提示已开启" : " 冷却提示已关闭")));
            menu.openSkillPanel(player);
            return;
        }
        if (slot >= RpgMenu.PANEL_READY_BASE && slot < RpgMenu.PANEL_READY_BASE + triggerCount) {
            String trigger = SkillPanelManager.TRIGGERS.get(slot - RpgMenu.PANEL_READY_BASE);
            boolean enabled = !plugin.getSkillPanelManager().isReadyMessagesEnabled(player, trigger);
            plugin.getSkillPanelManager().setReadyMessagesEnabled(player, trigger, enabled);
            player.sendMessage(Texts.color((enabled ? "&a" : "&c") + SkillPanelManager.triggerLabel(trigger)
                    + (enabled ? " 就绪提示已开启" : " 就绪提示已关闭")));
            menu.openSkillPanel(player);
            return;
        }
        if (slot >= RpgMenu.PANEL_ACTIVATION_BASE && slot < RpgMenu.PANEL_ACTIVATION_BASE + triggerCount) {
            String trigger = SkillPanelManager.TRIGGERS.get(slot - RpgMenu.PANEL_ACTIVATION_BASE);
            boolean enabled = !plugin.getSkillPanelManager().isActivationMessagesEnabled(player, trigger);
            plugin.getSkillPanelManager().setActivationMessagesEnabled(player, trigger, enabled);
            player.sendMessage(Texts.color((enabled ? "&a" : "&c") + SkillPanelManager.triggerLabel(trigger)
                    + (enabled ? " 释放提示已开启" : " 释放提示已关闭")));
            menu.openSkillPanel(player);
            return;
        }
        if (slot >= RpgMenu.PANEL_TRIGGER_BASE && slot < RpgMenu.PANEL_TRIGGER_BASE + triggerCount) {
            String trigger = SkillPanelManager.TRIGGERS.get(slot - RpgMenu.PANEL_TRIGGER_BASE);
            boolean enabled = !plugin.getSkillPanelManager().isTriggerEnabled(player, trigger);
            plugin.getSkillPanelManager().setTriggerEnabled(player, trigger, enabled);
            player.sendMessage(Texts.color((enabled ? "&a" : "&c") + SkillPanelManager.triggerLabel(trigger)
                    + (enabled ? " 键位已启用" : " 键位已禁用")));
            menu.openSkillPanel(player);
            return;
        }
        if (slot < RpgMenu.PANEL_SLOT_BASE || slot >= RpgMenu.PANEL_SLOT_BASE + triggerCount) return;
        String trigger = SkillPanelManager.TRIGGERS.get(slot - RpgMenu.PANEL_SLOT_BASE);
        if (event.isRightClick()) {
            plugin.getSkillPanelManager().setSkillId(player, trigger, null);
            GuiSounds.remove(player);
            menu.openSkillPanel(player);
            return;
        }
        String placedId = plugin.getSkillPanelManager().registeredSkillId(event.getCursor());
        if (placedId != null) {
            SkillPanelManager.EquipResult result = plugin.getSkillPanelManager().equip(player, trigger, placedId);
            player.sendMessage(equipMessage(player, result, placedId, trigger));
            menu.openSkillPanel(player);
            return;
        }
        menu.openSkillBackpack(player, trigger, 0);
    }

    /**
     * 技能预设界面的点击：空位保存、已保存的加载、右键覆盖保存、删除模式下删除。
     * <p>
     * 加载是唯一会改技能等级的入口，所以结果一律在聊天栏说清楚——扣了多少点、还剩多少点、
     * 失败是缺技能还是缺点数——而不是让玩家从面板的变化里去猜发生了什么。
     */
    private void handleSkillPresetClick(RpgMenu menu, Player player, RpgMenuHolder holder,
                                        InventoryClickEvent event, int slot) {
        if (slot == RpgMenu.PRESET_CLOSE_SLOT) { player.closeInventory(); return; }
        if (slot == RpgMenu.PRESET_BACK_SLOT) { menu.openSkillPanel(player); return; }
        if (slot == RpgMenu.PRESET_DELETE_MODE_SLOT) { menu.openSkillPreset(player, !holder.isDeleteMode()); return; }
        if (slot < RpgMenu.PRESET_BASE || slot >= RpgMenu.PRESET_BASE + SkillPresetManager.PRESET_COUNT) return;

        int index = slot - RpgMenu.PRESET_BASE;
        SkillPresetManager presets = plugin.getSkillPresetManager();
        String label = "预设 " + (index + 1);
        SkillPresetManager.Preset preset = presets.preset(player, index);

        if (holder.isDeleteMode()) {
            if (preset == null) {
                GuiSounds.fail(player);
                player.sendMessage(Texts.color("&7" + label + " 是空的，没有可删除的内容"));
                return;
            }
            boolean deleted = presets.delete(player, index);
            if (deleted) GuiSounds.delete(player); else GuiSounds.fail(player);
            player.sendMessage(deleted
                    ? Texts.color("&c已删除 " + label)
                    : Texts.color("&c删除 " + label + " 失败，请查看控制台日志"));
            menu.openSkillPreset(player, true);
            return;
        }

        // 空位、或右键 = 保存（右键在已保存的格子上是「覆盖保存」）。
        if (preset == null || event.isRightClick()) {
            SkillPresetManager.SaveResult outcome = presets.save(player, index);
            if (outcome == SkillPresetManager.SaveResult.OK) GuiSounds.success(player);
            else GuiSounds.fail(player);
            player.sendMessage(switch (outcome) {
                case OK -> Texts.color("&a已把当前技能配置保存到 " + label);
                case EMPTY_PRESET -> Texts.color("&c当前没有任何技能绑定到键位，先装备技能再保存");
                case SAVE_FAILED -> Texts.color("&c保存 " + label + " 失败，请查看控制台日志");
            });
            menu.openSkillPreset(player, false);
            return;
        }

        SkillPresetManager.LoadOutcome loaded = presets.load(player, index);
        if (loaded.result() == SkillPresetManager.LoadResult.OK) GuiSounds.success(player);
        else GuiSounds.fail(player);
        player.sendMessage(presetLoadMessage(label, loaded));
        menu.openSkillPreset(player, false);
    }

    private net.kyori.adventure.text.Component presetLoadMessage(String label, SkillPresetManager.LoadOutcome outcome) {
        return switch (outcome.result()) {
            case OK -> {
                if (outcome.reallocated()) {
                    yield Texts.color("&a已加载 " + label + "：技能与提示开关已还原。"
                            + "已按预设重分配天赋点，消耗 &f" + outcome.required() + " &a点，"
                            + "剩余 &f" + outcome.remaining() + " &a点仍在你手里，自己决定怎么用");
                }
                if (outcome.skillPointMode()) {
                    // EarthExp 还没实装扣点：重置等级换不回任何点数，所以刻意不重置，
                    // 只把预设里的技能等级直接套上去。这句必须说清楚，否则玩家会以为出了 bug。
                    yield Texts.color("&a已加载 " + label + "：技能与提示开关已还原，"
                            + "预设里的技能等级已直接应用。"
                            + "&7（EarthExp 目前还没有实装扣点接口，所以加载不消耗技能点，"
                            + "也不会重置其他技能——重置换不回点数，只会白洗等级）");
                }
                yield Texts.color("&a已加载 " + label + "：技能与提示开关已还原"
                        + "&7（宝石模式不做天赋重分配，技能等级保持不变）");
            }
            case EMPTY -> Texts.color("&7" + label + " 是空的，先左键保存一次");
            case BROKEN -> Texts.color("&c" + label + " 已失效，无法使用：&f"
                    + String.join("&7, &f", outcome.problems()));
            case NOT_ENOUGH_POINTS -> Texts.color("&c" + label + " 需要 &f" + outcome.required()
                    + " &c点天赋点，你只有 &f" + outcome.available() + " &c点，还差 &f"
                    + (outcome.required() - outcome.available()) + " &c点。已取消，技能等级与键位都没有改动");
            case SPEND_REJECTED -> Texts.color("&cEarthExp 拒绝了这次扣点，已取消，什么都没改。"
                    + "&7常见原因：当前配置的轨道没有开放外部消费"
                    + "（EarthExp 目前只给冒险轨道开了口子，配成 COMBAT 时会一直这样），"
                    + "或点数在操作途中被别的插件用掉了");
            case SAVE_FAILED -> Texts.color("&c等级表保存失败，已把点数改动全部回滚，"
                    + "技能等级与键位都没有改动。请检查服务器磁盘空间与文件权限");
        };
    }

    private String backpackMode(RpgMenuHolder holder) {
        return holder.getGroup() == null || holder.getGroup().isBlank()
                ? RpgMenu.BACKPACK_MANAGE : holder.getGroup();
    }

    /**
     * Backpack clicks. Placement works with a plain left or right click, no dragging required:
     * clicking a skill item in the player's own inventory stores it, and clicking the backpack
     * with a skill on the cursor stores that one too.
     */
    private void handleSkillBackpackClick(RpgMenu menu, Player player, RpgMenuHolder holder,
                                          InventoryClickEvent event, int slot) {
        String mode = backpackMode(holder);
        boolean equipMode = !mode.equals(RpgMenu.BACKPACK_MANAGE);
        SkillBackpackManager backpack = plugin.getSkillBackpackManager();
        boolean ownInventory = event.getClickedInventory() != null
                && event.getClickedInventory().equals(player.getInventory());

        if (ownInventory) {
            String id = plugin.getSkillPanelManager().registeredSkillId(event.getCurrentItem());
            if (id != null) {
                event.setCancelled(true);
                // The physical item is only consumed after the backpack accepted it,
                // otherwise a rejected store (duplicate / full / save failure) would destroy it.
                if (storeSkill(player, backpack, id)) {
                    ItemStack current = event.getCurrentItem();
                    if (current != null && current.getAmount() > 1) {
                        current.setAmount(current.getAmount() - 1);
                        player.getInventory().setItem(event.getSlot(), current);
                    } else {
                        player.getInventory().setItem(event.getSlot(), null);
                    }
                }
                menu.openSkillBackpack(player, mode, holder.getPage(), holder.isDeleteMode());
                return;
            }
            // Anything else stays normal inventory behaviour so items can still be moved,
            // except shift-click which would push arbitrary items into the backpack GUI.
            event.setCancelled(event.isShiftClick());
            return;
        }

        event.setCancelled(true);
        String cursorId = plugin.getSkillPanelManager().registeredSkillId(event.getCursor());
        if (cursorId != null) {
            // Clear the cursor only when the backpack actually accepted the skill.
            if (storeSkill(player, backpack, cursorId)) player.setItemOnCursor(null);
            menu.openSkillBackpack(player, mode, holder.getPage(), holder.isDeleteMode());
            return;
        }

        if (slot == RpgMenu.BACK_SLOT) { menu.openSkillPanel(player); return; }
        if (slot == RpgMenu.BACKPACK_DELETE_MODE_SLOT) {
            holder.setDeleteMode(!holder.isDeleteMode());
            menu.openSkillBackpack(player, mode, holder.getPage(), holder.isDeleteMode());
            return;
        }
        List<String> ids = plugin.getSkillPanelManager().backpackIds(player);
        int maxPage = Math.max(1, (int) Math.ceil(ids.size() / (double) RpgMenu.PAGE_SIZE));
        if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) {
            menu.openSkillBackpack(player, mode, holder.getPage() - 1, holder.isDeleteMode());
            return;
        }
        if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) {
            menu.openSkillBackpack(player, mode, holder.getPage() + 1, holder.isDeleteMode());
            return;
        }
        if (slot < 0 || slot >= RpgMenu.PAGE_SIZE) return;
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
        if (index >= ids.size()) return;
        String id = ids.get(index);

        if (equipMode) {
            SkillPanelManager.EquipResult result = plugin.getSkillPanelManager().equip(player, mode, id);
            player.sendMessage(equipMessage(player, result, id, mode));
            menu.openSkillPanel(player);
            return;
        }

        // 管理模式：技能【不能取出】。开启「删除模式」后点击技能=进入【确认删除】界面，
        // 玩家看清楚再点确认（防止手贱误删）；确认后才真正删除并退还技能点。
        if (holder.isDeleteMode()) {
            menu.openSkillDeleteConfirm(player, id, holder.getPage());
            return;
        }
        player.sendMessage(Texts.color("&e技能无法取出；点击底部的「删除模式」按钮开启后，"
                + "再点击技能即可删除（删除会退还已花费的技能点）"));
        menu.openSkillBackpack(player, mode, holder.getPage(), false);
    }

    /**
     * 确认删除界面：独立 GUI，中间展示要删的技能。「确认删除」才真正删，
     * 「取消 / 关闭」直接回技能背包。返回后停在删除模式（方便一次删多个）。
     * <p>
     * 用 {@code holder.isConfirmHandled()} 做「消费一次」保护：连点或重复事件不会
     * 删除两次、也不会退款两次。
     */
    private void handleSkillDeleteConfirmClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        if (holder.isConfirmHandled()) return;
        String skillId = holder.getGroup();
        String backpackMode = RpgMenu.BACKPACK_MANAGE;
        int returnPage = holder.getPage();
        holder.setConfirmHandled(true);

        if (slot == RpgMenu.DELETE_CONFIRM_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == RpgMenu.DELETE_CONFIRM_NO_SLOT) {
            menu.openSkillBackpack(player, backpackMode, returnPage, true);
            return;
        }
        if (slot != RpgMenu.DELETE_CONFIRM_YES_SLOT) return;
        if (skillId == null || skillId.isBlank()) {
            menu.openSkillPanel(player);
            return;
        }
        // 重新校验归属：确认界面停留期间技能可能已被别处移除，避免对着不存在的技能退款。
        SkillBackpackManager backpack = plugin.getSkillBackpackManager();
        if (!backpack.contains(player.getUniqueId(), skillId)) {
            player.sendMessage(Texts.color("&c该技能已不在技能背包中，无需删除"));
            menu.openSkillBackpack(player, backpackMode, returnPage, true);
            return;
        }
        deleteSkill(player, skillId);
        menu.openSkillBackpack(player, backpackMode, returnPage, true);
    }

    /** 真正删除：从技能背包移除、清面板槽位、等级复位 Lv.1、退还技能点。 */
    private void deleteSkill(Player player, String id) {
        SkillBackpackManager backpack = plugin.getSkillBackpackManager();
        SkillLevelManager levels = plugin.getSkillLevelManager();
        int level = levels.level(player.getUniqueId(), id);
        if (!backpack.remove(player.getUniqueId(), id)) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color("&c删除失败：技能不在技能背包中或保存失败：&f" + id));
            return;
        }
        GuiSounds.delete(player);
        levels.setLevel(player.getUniqueId(), id, SkillLevelManager.MIN_LEVEL);
        if (levels.usesSkillPoints()) {
            int wanted = SkillLevelManager.cumulativePoints(level);
            // 用【实际】退回量报数：EarthExp 的退款上限是「本插件实际扣过的量」，
            // 升级发生在扣点接口出现之前的技能退不出任何点数。
            // 直接报 wanted 会说出一个根本没退的数字。
            int refunded = levels.refundPoints(player, wanted);
            player.sendMessage(Texts.color("&c已删除技能: &f" + id
                    + "&7（退还已花费的技能点 &f×" + refunded + "&7"
                    + (refunded < wanted ? "，另有 &f×" + (wanted - refunded)
                    + "&7 是接口出现前升的级，EarthExp 账上没有记录、退不出来" : "") + "）"));
        } else {
            player.sendMessage(Texts.color("&c已删除技能: &f" + id
                    + "&7（未接入 EarthExp，删除不退还宝石）"));
        }
    }

    /**
     * 把技能物品安全地交给玩家（技能物品库发放用）。
     * <p>
     * {@code Inventory#addItem} 在装不下时会把剩下的物品**退回来**而不是销毁，旧代码直接忽略返回值，
     * 于是「技能库发放技能」在背包满时会静默丢东西。这里把退回来的部分掉在玩家脚下，保证不会丢失。
     */
    private void giveSkillItem(RpgMenu menu, Player player, String id) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(menu.skillItem(id, null, null));
        for (ItemStack rest : leftover.values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
    }

    private boolean storeSkill(Player player, SkillBackpackManager backpack, String id) {
        if (backpack.add(player.getUniqueId(), id)) {
            GuiSounds.place(player);
            player.sendMessage(Texts.color("&a已放入技能背包: &f" + id));
            return true;
        }
        GuiSounds.fail(player);
        if (backpack.contains(player.getUniqueId(), id)) {
            player.sendMessage(Texts.color("&c技能背包中已有相同技能，无法重复放入"));
        } else if (backpack.isFull(player.getUniqueId())) {
            player.sendMessage(Texts.color("&c技能背包已满"));
        } else {
            player.sendMessage(Texts.color("&c技能背包保存失败，物品未收取，请稍后再试"));
        }
        return false;
    }

    private void handleSkillItemsClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        var ids = plugin.getSkillPanelManager().registeredSkillIds();
        int maxPage = Math.max(1, (int) Math.ceil(ids.size() / (double) RpgMenu.PAGE_SIZE));
        if (slot == RpgMenu.BACK_SLOT) { menu.openSkillPanel(player); return; }
        if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) { menu.openSkillItems(player, holder.getGroup(), holder.getPage() - 1); return; }
        if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) { menu.openSkillItems(player, holder.getGroup(), holder.getPage() + 1); return; }
        if (slot < 0 || slot >= RpgMenu.PAGE_SIZE) return;
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
        if (index >= ids.size()) return;
        String id = ids.get(index);
        if (plugin.getSkillPanelManager().registeredItem(id) == null) return;
        // 必须走 equip()：直接 setSkillId() 会绕过「技能必须在技能背包里」「同一技能只能占一个槽位」
        // 和「技能组冲突」三项校验，装出一个永远不能用的槽位。
        SkillPanelManager.EquipResult result = plugin.getSkillPanelManager().equip(player, holder.getGroup(), id);
        player.sendMessage(equipMessage(player, result, id, holder.getGroup()));
        menu.openSkillPanel(player);
    }

    private void handleSkillLibraryClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        var ids = plugin.getSkillPanelManager().registeredSkillIds();
        int maxPage = Math.max(1, (int) Math.ceil(ids.size() / (double) RpgMenu.PAGE_SIZE));
        if (slot == RpgMenu.BACK_SLOT) { menu.openMain(player); return; }
        if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) { menu.openSkillLibrary(player, holder.getPage() - 1); return; }
        if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) { menu.openSkillLibrary(player, holder.getPage() + 1); return; }
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
        if (slot < 0 || slot >= RpgMenu.PAGE_SIZE || index >= ids.size()) return;
        String id = ids.get(index);
        if (plugin.getSkillPanelManager().registeredItem(id) != null) {
            giveSkillItem(menu, player, id);
        }
    }

    /** Permission that gates the station; kept in one place so opening and clicking agree. */
    private static final String ENHANCE_PERMISSION = "earthrpgitem.enhance";

    private void handleEnhanceClick(RpgMenu menu, Player player, InventoryClickEvent event, int slot) {
        // Clicking is re-checked, not just opening: a permission can be revoked while the window
        // stays open, and the station spends material.
        if (!player.hasPermission(ENHANCE_PERMISSION)) {
            event.setCancelled(true);
            player.sendMessage(Texts.color("&c你没有使用强化台的权限。"));
            player.closeInventory();
            return;
        }
        boolean ownInventory = event.getClickedInventory() != null
                && event.getClickedInventory().equals(player.getInventory());
        if (ownInventory) {
            // Left usable so material can be picked up; only shift-click is blocked, because that
            // would push arbitrary items into the station's display slots.
            event.setCancelled(event.isShiftClick());
            return;
        }
        event.setCancelled(true);
        if (slot == RpgMenu.ENHANCE_CLOSE_SLOT) { player.closeInventory(); return; }
        if (slot == RpgMenu.ENHANCE_MATERIAL_SLOT) { handleMaterialClick(menu, player, event); return; }
        if (slot == RpgMenu.ENHANCE_BUTTON_SLOT) { handleEnhanceButton(menu, player); }
    }

    private void handleEnhanceButton(RpgMenu menu, Player player) {
        // The item is read fresh here rather than taken from what the screen was drawn with: the
        // player may have swapped main-hand items since it opened.
        ItemStack held = player.getInventory().getItemInMainHand();
        plugin.getEnhancementService().attempt(player, held,
                plugin.getEnhanceStationManager().source(player.getUniqueId()));
        // Re-rendered either way: the level, the preview and the stored material all just changed.
        menu.openEnhance(player);
    }

    /**
     * Deposits material into, or withdraws it from, the station's stock.
     * <p>
     * The slot is a rendering of {@link EnhanceStationManager}, never a container, so the item on
     * the cursor is consumed into a number instead of being placed anywhere.
     */
    private void handleMaterialClick(RpgMenu menu, Player player, InventoryClickEvent event) {
        EnhancementManager enhancement = plugin.getEnhancementManager();
        EnhanceStationManager station = plugin.getEnhanceStationManager();
        if (enhancement == null || station == null || !enhancement.consumesStone()) {
            menu.openEnhance(player);
            return;
        }
        String stoneId = enhancement.stoneItemId();
        UUID playerId = player.getUniqueId();
        ItemStack cursor = event.getCursor();
        // Emptiness is tested on the stack itself, never on the resolved id: a plain vanilla item
        // also resolves to a null id, and treating that as "empty hand" would make clicking this
        // slot while carrying anything unrecognised silently withdraw the whole stock.
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        String cursorId = cursorEmpty ? null : registeredId(cursor);

        if (!cursorEmpty && stoneId.equals(cursorId)) {
            int offer = event.isRightClick() ? 1 : cursor.getAmount();
            int accepted = station.store(playerId, stoneId, offer);
            if (accepted <= 0) {
                GuiSounds.fail(player);
                player.sendMessage(Texts.color("&c存入失败：槽位里还存着别的材料，或已经存满了。"));
            } else {
                GuiSounds.place(player);
                int left = cursor.getAmount() - accepted;
                if (left <= 0) {
                    player.setItemOnCursor(null);
                } else {
                    // Cloned rather than mutated in place: the cursor stack is owned by the view.
                    ItemStack remaining = cursor.clone();
                    remaining.setAmount(left);
                    player.setItemOnCursor(remaining);
                }
                player.sendMessage(Texts.color("&a已存入 &f" + accepted + " &a个 &f" + stoneId));
            }
            menu.openEnhance(player);
            return;
        }

        if (!cursorEmpty) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color("&c这个槽位只接受 &f" + stoneId + "&c。"));
            menu.openEnhance(player);
            return;
        }

        // Empty cursor: hand the whole stock back.
        EnhanceStationManager.Deposit deposit = station.deposit(playerId);
        if (deposit.isEmpty()) {
            menu.openEnhance(player);
            return;
        }
        // Resolved *before* clearing the stock. Clearing first and then discovering the item id no
        // longer exists would delete the material outright, which is the one outcome this whole
        // design exists to prevent.
        RpgItem stone = plugin.getRpgItemManager().findById(deposit.itemId());
        if (stone == null) {
            player.sendMessage(Texts.color("&c无法取回：物品 &f" + deposit.itemId()
                    + " &c已不在配置中。材料仍然安全保存在强化台里，恢复该物品后即可取回。"));
            menu.openEnhance(player);
            return;
        }
        EnhanceStationManager.Deposit taken = station.takeAll(playerId);
        if (taken.isEmpty()) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color("&c取回失败：无法写入数据文件，材料仍在强化台里。"));
            menu.openEnhance(player);
            return;
        }
        giveBack(player, stone, taken.count());
        GuiSounds.remove(player);
        player.sendMessage(Texts.color("&e已取回 &f" + taken.count() + " &e个 &f" + taken.itemId()));
        menu.openEnhance(player);
    }

    /**
     * Returns {@code amount} of {@code stone} to the player, dropping whatever does not fit.
     * Dropping rather than discarding matters: a full inventory must not quietly destroy material
     * that the player was told they had.
     */
    private void giveBack(Player player, RpgItem stone, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = plugin.getRpgItemManager().createItem(stone);
            int size = Math.max(1, Math.min(remaining, stack.getMaxStackSize()));
            stack.setAmount(size);
            remaining -= size;
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack dropped : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }
        }
    }

    /** The registered id of an item, or null when it is empty or not an RPG item. */
    private String registeredId(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        RpgItem item = plugin.getRpgItemManager().findItem(stack);
        return item == null ? null : item.id();
    }

    /** Permission that gates the gem station; separate from the admin enhancement commands. */
    private static final String GEM_PERMISSION = "earthrpgitem.gem";

    /** Permission that gates the skill forge. Player-facing, so it defaults to true. */
    private static final String SKILL_FORGE_PERMISSION = RpgMenu.SKILL_FORGE_PERMISSION;

    /**
     * 技能强化台（列表页）：左键强化，右键看完整成长表。
     * <p>
     * 列表内容是玩家技能背包里的技能，所以索引要按「页 * 每页数量 + 槽位偏移」算回去，
     * 和技能背包/技能物品库用的是同一套分页约定。
     */
    private void handleSkillForgeClick(RpgMenu menu, Player player, RpgMenuHolder holder,
                                       InventoryClickEvent event, int slot) {
        // 从技能面板进来的强化台，「返回」回技能面板（和技能背包/技能预设一致）；
        // 从主菜单进来的回主菜单。这个标记必须跟着每一次重开界面传下去，
        // 否则翻一页或强化一次之后「返回」就会莫名其妙跳到主菜单。
        boolean fromPanel = holder.isForgeFromPanel();
        if (slot == RpgMenu.FORGE_BACK_SLOT) {
            if (fromPanel) menu.openSkillPanel(player); else menu.openMain(player);
            return;
        }
        if (slot == RpgMenu.FORGE_PREVIOUS_SLOT) { menu.openSkillForge(player, holder.getPage() - 1, fromPanel); return; }
        if (slot == RpgMenu.FORGE_NEXT_SLOT) { menu.openSkillForge(player, holder.getPage() + 1, fromPanel); return; }
        if (slot == RpgMenu.FORGE_RESET_SLOT) {
            resetAllSkillPoints(menu, player, fromPanel);
            return;
        }
        if (slot < RpgMenu.FORGE_LIST_BASE || slot >= RpgMenu.FORGE_LIST_BASE + RpgMenu.PAGE_SIZE) return;
        if (!forgePermission(player)) return;
        List<String> owned = plugin.getSkillPanelManager().backpackIds(player);
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + (slot - RpgMenu.FORGE_LIST_BASE);
        if (index < 0 || index >= owned.size()) return;
        String id = owned.get(index);
        if (event.isRightClick()) {
            menu.openSkillForgeDetail(player, id, holder.getPage(), fromPanel);
            return;
        }
        attemptSkillUpgrade(menu, player, id, holder.getPage(), false, fromPanel);
    }

    /**
     * 一键重置技能点：把技能背包里所有已升级技能回到 Lv.1，退还各自已花费的技能点
     * （按累计公式，Lv.1 免费）。技能本身保留在背包和面板里，只是等级复位；
     * 面板每次打开都按当前等级重新解析，所以刷新后不会出现旧数值残留。
     * <p>
     * 没接入 EarthExp（宝石模式）时按钮不可用，这里也拦一道：宝石是实打实扣过的，
     * 一键重置会把投入全部抹掉且无法退还，不允许误触。
     */
    private void resetAllSkillPoints(RpgMenu menu, Player player, boolean fromPanel) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        if (!levels.usesSkillPoints()) {
            player.sendMessage(Texts.color("&c未接入 EarthExp（技能点），一键重置不可用。"));
            menu.openSkillForge(player, 0, fromPanel);
            return;
        }
        if (!forgePermission(player)) return;
        UUID playerId = player.getUniqueId();
        int resetCount = 0;
        Map<String, Integer> target = new LinkedHashMap<>();
        // 重置【所有已存储】的等级，不只是背包里的：漏掉任何一个，账上退还的点数就会多于
        // 实际清掉的等级，等于凭空发点。预设重分配那边用的是同一套规则。
        for (Map.Entry<String, Integer> entry : levels.levels(playerId).entrySet()) {
            if (entry.getValue() > SkillLevelManager.MIN_LEVEL) resetCount++;
            target.put(entry.getKey(), SkillLevelManager.MIN_LEVEL);
        }
        if (resetCount == 0) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color("&7没有已升级的技能，无需重置。"));
            menu.openSkillForge(player, 0, fromPanel);
            return;
        }
        // 先退点数（上限由 EarthExp 的账本卡住），再一次性把整张等级表写成全 Lv.1。
        // 顺序与回滚方式和技能预设的重分配保持一致：写盘失败就把点数原样塞回去。
        int reclaimable = levels.spentSkillPoints(player);
        int refunded = reclaimable > 0 ? levels.refundPoints(player, reclaimable) : 0;
        if (!levels.setLevels(playerId, target)) {
            if (refunded > 0) levels.spendPoints(player, refunded);
            player.sendMessage(Texts.color("&c等级表保存失败，已回滚，技能等级没有改动。"
                    + "请检查服务器磁盘空间与文件权限"));
            menu.openSkillForge(player, 0, fromPanel);
            return;
        }
        player.sendMessage(Texts.color("&e一键重置完成：&f" + resetCount + " &e个技能回到 Lv.1，"
                + "退还战斗天赋点 &f×" + refunded
                + (refunded == 0 ? "&7（EarthExp 账上没有本插件的扣点记录，没有可退的点数——"
                + "这些等级是在扣点接口出现之前升的）" : "")));
        menu.openSkillForge(player, 0, fromPanel);
    }

    /** 技能强化台（详情页）：只有强化按钮和返回/关闭。 */
    private void handleSkillForgeDetailClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        String skillId = holder.getGroup();
        boolean fromPanel = holder.isForgeFromPanel();
        if (slot == RpgMenu.FORGE_DETAIL_BACK_SLOT) { menu.openSkillForge(player, holder.getPage(), fromPanel); return; }
        if (slot == RpgMenu.FORGE_DETAIL_CLOSE_SLOT) { player.closeInventory(); return; }
        if (slot != RpgMenu.FORGE_DETAIL_BUTTON_SLOT) return;
        if (skillId == null || skillId.isBlank()) { menu.openSkillForge(player, holder.getPage(), fromPanel); return; }
        if (!forgePermission(player)) return;
        attemptSkillUpgrade(menu, player, skillId, holder.getPage(), true, fromPanel);
    }

    private boolean forgePermission(Player player) {
        if (player.hasPermission(SKILL_FORGE_PERMISSION)) return true;
        player.sendMessage(Texts.color(plugin.getSkillLevelManager()
                .message("no-permission", "&c你没有使用技能强化台的权限。")));
        player.closeInventory();
        return false;
    }

    /**
     * 升一级：校验 → 扣材料 → 写等级。任何一步失败都保持原状，且绝不吞材料。
     * <p>
     * 顺序刻意是「先扣材料、后写等级」并在写失败时<b>退还</b>材料：反过来先写等级再扣材料，
     * 一旦扣材料失败玩家就白升了一级；而这个方向最坏也只是「材料扣了、等级没升」，
     * 用退还把它补回来。
     */
    private void attemptSkillUpgrade(RpgMenu menu, Player player, String skillId, int returnPage,
                                     boolean detail, boolean fromPanel) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        if (!levels.enabled()) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color(levels.message("not-enabled", "&c技能强化系统当前已关闭。")));
            reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
            return;
        }
        if (!plugin.getSkillBackpackManager().contains(player.getUniqueId(), skillId)) {
            player.sendMessage(Texts.color(levels.message("not-owned", "&c你还没有这个技能，请先放入技能背包。")));
            reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
            return;
        }
        if (!levels.hasRules(skillId)) {
            player.sendMessage(Texts.color(levels.message("no-rules", "&c这个技能没有配置成长规则（leveling 段），无法强化。")));
            reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
            return;
        }
        int level = levels.level(player.getUniqueId(), skillId);
        int max = levels.maxLevel(skillId);
        if (level >= max) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color(fill(levels.message("max-level", "&e这个技能已经达到上限 Lv.{max}。"),
                    "{max}", String.valueOf(max))));
            reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
            return;
        }
        if (levels.usesSkillPoints()) {
            // 技能点模式（已接入 EarthExp）：升到第 N 级花 N 点。
            int needPoints = levels.pointsCost(level + 1);
            if (!levels.hasSkillPoints(player, needPoints)) {
                GuiSounds.fail(player);
                String raw = levels.message("missing-points",
                        "&c战斗天赋点不足，升级需要：&f{need}")
                        + "&7（当前 &f" + levels.availableSkillPoints(player) + "&7）";
                player.sendMessage(Texts.color(fill(raw, "{need}", levels.describePointsCost(needPoints))));
                reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
                return;
            }
            if (!levels.spendPoints(player, needPoints)) {
                // 走到这里不是「保存失败」，而是 EarthExp 明确拒绝了这次扣点：
                // 要么点数在刚才这一瞬被别的插件用掉了，要么当前配置的轨道没有开放外部消费
                // （EarthExp 目前只给冒险轨道开了口子，config.yml 配成 COMBAT 时必然如此）。
                player.sendMessage(Texts.color("&cEarthExp 拒绝了这次扣点，等级没有变化。"
                        + "&7常见原因：点数不足，或该轨道没有开放外部扣点。"
                        + "&7EarthExp 0.5.1+ 支持按轨道扣点（COMBAT / ADVENTURE 各自独立）；"
                        + "&70.5.0 及更早只支持 &fADVENTURE&7，"
                        + "此时请升级 EarthExp 或把 &fearthexp.skill-point-stat&7 改成 ADVENTURE"));
                reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
                return;
            }
            if (!levels.setLevel(player.getUniqueId(), skillId, level + 1)) {
                levels.refundPoints(player, needPoints);
                player.sendMessage(Texts.color(levels.message("save-failed",
                        "&c保存失败，等级没有变化。请稍后再试。")));
                reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
                return;
            }
        } else {
            // 没有 EarthExp：退回旧宝石消耗。takeGems 先整体校验再扣，失败时背包一定没被改动。
            Map<String, Integer> need = levels.costFor(skillId, level + 1);
            if (!levels.takeGems(player, need)) {
                GuiSounds.fail(player);
                player.sendMessage(Texts.color(fill(levels.message("missing-gems", "&c材料不足，升级需要：&f{need}"),
                        "{need}", levels.describeCost(need))));
                reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
                return;
            }
            if (!levels.setLevel(player.getUniqueId(), skillId, level + 1)) {
                levels.giveGems(player, need);
                player.sendMessage(Texts.color(levels.message("save-failed",
                        "&c保存失败，等级没有变化。请稍后再试。")));
                reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
                return;
            }
        }
        player.sendMessage(Texts.color(fill(fill(fill(
                levels.message("success", "&a技能强化成功：&f{skill} &aLv.{from} &7→ &aLv.{to}"),
                "{skill}", skillId),
                "{from}", String.valueOf(level)),
                "{to}", String.valueOf(level + 1))));
        GuiSounds.success(player);
        reopenForge(menu, player, skillId, returnPage, detail, fromPanel);
    }

    private void reopenForge(RpgMenu menu, Player player, String skillId, int returnPage,
                             boolean detail, boolean fromPanel) {
        if (detail) menu.openSkillForgeDetail(player, skillId, returnPage, fromPanel);
        else menu.openSkillForge(player, returnPage, fromPanel);
    }

    /** 文案占位符替换。 */
    private static String fill(String template, String key, String value) {
        return template.replace(key, value);
    }

    private void handleGemClick(RpgMenu menu, Player player, InventoryClickEvent event, int slot) {
        // Re-checked on every click, not only when opening: a permission can be revoked while the
        // window stays open, and socketing consumes a gem.
        if (!player.hasPermission(GEM_PERMISSION)) {
            event.setCancelled(true);
            player.sendMessage(Texts.color("&c你没有使用宝石台的权限。"));
            player.closeInventory();
            return;
        }
        boolean ownInventory = event.getClickedInventory() != null
                && event.getClickedInventory().equals(player.getInventory());
        if (ownInventory) {
            // Left usable so a gem can be picked up onto the cursor; only shift-click is blocked,
            // because that would push arbitrary items into the station's display slots.
            event.setCancelled(event.isShiftClick());
            return;
        }
        event.setCancelled(true);
        if (slot == RpgMenu.GEM_CLOSE_SLOT) { player.closeInventory(); return; }
        if (slot >= RpgMenu.GEM_SOCKET_FIRST_SLOT && slot < RpgMenu.GEM_SOCKET_FIRST_SLOT + RpgMenu.GEM_SOCKET_MAX) {
            handleSocketClick(menu, player, event, slot - RpgMenu.GEM_SOCKET_FIRST_SLOT);
        }
    }

    /**
     * Sockets a gem from the cursor into one slot, or unsockets the gem already in it.
     * <p>
     * The item is read fresh from the main hand rather than taken from what the screen was drawn
     * with, because the player may have swapped items since it opened. A gem is consumed from the
     * cursor only <em>after</em> the socket write succeeded, so a refusal never costs a gem, and it
     * is taken one at a time so a stack of gems cannot be swallowed by a single click.
     */
    private void handleSocketClick(RpgMenu menu, Player player, InventoryClickEvent event, int index) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            menu.openGems(player);
            return;
        }
        var items = plugin.getRpgItemManager();
        RpgItem registered = items.findItem(held);
        if (registered == null || index < 0 || index >= registered.sockets()) {
            menu.openGems(player);
            return;
        }

        if (event.isRightClick()) {
            List<String> sockets = items.socketedGems(held);
            String current = index < sockets.size() ? sockets.get(index) : "";
            if (current == null || current.isBlank()) {
                menu.openGems(player);
                return;
            }
            // Resolved *before* the socket is cleared. Clearing first and then discovering the gem
            // item no longer exists would delete the gem outright, which is the one outcome this
            // whole design exists to prevent.
            RpgItem gemItem = items.findById(current);
            if (gemItem == null) {
                player.sendMessage(Texts.color("&c无法卸下：宝石物品 &f" + current
                        + " &c已不在配置中。它仍然安全地留在装备里，恢复该物品后即可卸下。"));
                menu.openGems(player);
                return;
            }
            String removed = items.unsocketGem(held, index);
            if (removed == null) {
                menu.openGems(player);
                return;
            }
            giveBack(player, gemItem, 1);
            GuiSounds.remove(player);
            player.sendMessage(Texts.color("&e已卸下 &f" + removed + " &e，宝石已回到背包"));
            menu.openGems(player);
            return;
        }

        ItemStack cursor = event.getCursor();
        // Emptiness is tested on the stack itself, never on the resolved id: a plain vanilla item also
        // resolves to a null id, and deciding on the id would treat "holding something unrecognised"
        // as an empty hand.
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        if (cursorEmpty) {
            menu.openGems(player);
            return;
        }
        String gemId = registeredId(cursor);
        GemstoneManager gems = plugin.getGemstoneManager();
        if (gemId == null || gems == null || !gems.isGem(gemId)) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color("&c这不是注册的宝石，无法镶嵌。"));
            menu.openGems(player);
            return;
        }
        if (!items.socketGem(held, index, gemId)) {
            GuiSounds.fail(player);
            player.sendMessage(Texts.color("&c这个槽位无法镶嵌（可能已经插了宝石）。"));
            menu.openGems(player);
            return;
        }
        // Written back explicitly rather than trusting the main-hand stack to be a live mirror: the
        // rebuild replaced the stack's meta, and losing that write would show the player a gem that
        // was consumed but never socketed.
        player.getInventory().setItemInMainHand(held);
        // A socketed gem changes the weapon's stats, and the write above bypasses the click event
        // that normally drops the memoised settlement.
        plugin.getRpgItemManager().invalidateStatsCache(player);
        int left = cursor.getAmount() - 1;
        if (left <= 0) {
            player.setItemOnCursor(null);
        } else {
            ItemStack remaining = cursor.clone();
            remaining.setAmount(left);
            player.setItemOnCursor(remaining);
        }
        player.sendMessage(Texts.color("&a已镶嵌 &f" + gemId + " &a到第 &f" + (index + 1) + " &a个槽位"));
        GuiSounds.place(player);
        menu.openGems(player);
    }

    private void handleCollectionClick(RpgMenu menu, Player player, RpgMenuHolder holder, int slot) {
        String group = holder.getGroup();
        if (group == null) { if (slot == RpgMenu.BACK_SLOT) menu.openMain(player); return; }
        if (slot == RpgMenu.BACK_SLOT) { menu.openCollectionGroups(player, 0); return; }
        var definitions = plugin.getCollectionManager().getDefinitions(group);
        int maxPage = Math.max(1, (int) Math.ceil(definitions.size() / (double) RpgMenu.PAGE_SIZE));
        if (slot == RpgMenu.PREVIOUS_SLOT && holder.getPage() > 0) { menu.openCollectionCategory(player, holder.getPage() - 1, group); return; }
        if (slot == RpgMenu.NEXT_SLOT && holder.getPage() + 1 < maxPage) { menu.openCollectionCategory(player, holder.getPage() + 1, group); return; }
        int index = holder.getPage() * RpgMenu.PAGE_SIZE + slot;
        if (slot >= 0 && slot < RpgMenu.PAGE_SIZE && index < definitions.size()) player.getInventory().addItem(plugin.getCollectionManager().createDisplayItem(definitions.get(index)));
    }
}
