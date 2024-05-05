package bluet.fiiis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.datafixers.util.Pair;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent;
import net.minecraftforge.fml.common.Mod;

@Mod (FiiisMod.MOD_ID)
public class FiiisMod {
    /* Current features:
     * Fire-resist player drops;
     * Totem of undying Inventory Poping;
     * /tpa;
     * Recovery recovery compass;
     * Unlit campfires by default.
     * Requires client-side resource pack (e.g. through server.properties)
     */
    public static final String MOD_ID = "fiiis";
    public FiiisMod () {
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener (FiiisMod::unburn);
        bus.addListener (FiiisMod::burn);
        bus.addListener (FiiisMod::regcmd);
        bus.addListener (FiiisMod::pop_totem);
        bus.addListener (FiiisMod::compass_click);
        bus.addListener (FiiisMod::torchlit);
        bus.addListener (FiiisMod::placeunlit);
        bus.addListener (FiiisMod::unlitontick);
    }
    public static void unburn (LivingDropsEvent event) {
        if (event.getEntity () instanceof Player)
        for (ItemEntity ie : event.getDrops ()) {
            ItemStack stack = ie.getItem ();
            if (stack.get (DataComponents.FIRE_RESISTANT) == null) {
                stack.set (DataComponents.FIRE_RESISTANT, Unit.INSTANCE);
            }
        }
    }
    public static void burn (EntityItemPickupEvent event) {
        // Note:
        // This leaves a bug that
        // if the player uses a hopper to pick up the items
        // then the item remains fire-resist
        // which is very interesting.
        ItemStack stack = event.getItem () .getItem ();
        if (stack.getItem () .components () .get (DataComponents.FIRE_RESISTANT) == null) stack.remove (DataComponents.FIRE_RESISTANT);
    }
    // Contain Custom Unit Tag
    public static final String cut_loc = "fiiiscut";
    public static boolean ccut (String tagname, ItemStack stack) {
        CustomData data = stack.get (DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag ();
        ListTag cuts = tag.getList (cut_loc, Tag.TAG_STRING);
        int i;
        for (i=0; i<cuts.size (); i++)
        if (tagname.equals (cuts.getString (i)))
        return true;
        return false;
    }
    // The TPA command
    public static enum TPType {
        TO, HERE
    }
    public static record TPRequest (TPType type, Player dest, long gt) {}
    public static final Map <String, TPRequest> latesttpa = new HashMap <> ();
    public static final int ORANGE = 0xff8000,
                            RED = 0xff2020,
                            DARK_RED = 0xbb0000,
                            GREEN = 0x10ff10,
                            YELLOW = 0xffff00;
    public static void requestAteleport (TPType type, Player src, Player dst, ServerLevel level) {
        latesttpa.put (dst.getName () .getString (), new TPRequest (type, src, level.getGameTime ()));
        Component sent = Component.translatable ("fiiis.sent.tprequestpre") .withColor (ORANGE) .append (dst.getName () .copy () .withColor (YELLOW))
            .append (Component.translatable ("fiiis.sent.tprequest") .withColor (ORANGE));
        src.sendSystemMessage (sent);
        String trans = type == TPType.TO ? "to" : "here";
        sent = Component.translatable ("fiiis.recv.tprequestpre" + trans) .withColor (ORANGE) .append (src.getName () .copy () .withColor (YELLOW))
            .append (Component.translatable ("fiiis.recv.tprequest" + trans) .withColor (ORANGE))
            .append (Component.translatable ("fiiis.hint.tpaccept") .withColor (GREEN));
        dst.sendSystemMessage (sent);
    }
    public static void register_single (TPType type, String name, CommandDispatcher <CommandSourceStack> dispatcher) {
        dispatcher.register (
            Commands.literal (name) .then (
                Commands.argument ("target", EntityArgument.player ()) .executes (
                    ctx -> {
                        ServerPlayer player = EntityArgument.getPlayer (ctx, "target");
                        requestAteleport (type, ctx.getSource () .getPlayer (), player, ctx.getSource () .getLevel ());
                        return 1;
                    }
                )
            )
        );
    }
    public static void move (Player f, Player t) {
        f.teleportTo ((ServerLevel) t.level (), t.getX (), t.getY (), t.getZ (), Set.of (), t.getYRot (), t.getXRot ());
    }
    public static boolean tpaccept (Player execer, ServerLevel level) {
        var request = latesttpa.get (execer.getName () .getString ());
        if (request == null || request.gt + 2400 < level.getGameTime ()) {
            execer.sendSystemMessage (Component.translatable ("fiiis.error.norequest") .withColor (DARK_RED));
            return false;
        } else {
            Player dst = request.dest;
            Component sent = Component.translatable ("fiiis.suc.tpacceptedpre") .withColor (GREEN)
                .append (execer.getName () .copy () .withColor (YELLOW)) .append (Component.translatable ("fiiis.suc.tpaccepted") .withColor (GREEN));
            dst.sendSystemMessage (sent);
            sent = Component.translatable ("fiiis.suc.tpacceptpre") .withColor (GREEN)
                .append (dst.getName () .copy () .withColor (YELLOW)) .append (Component.translatable ("fiiis.suc.tpaccept") .withColor (GREEN));
            execer.sendSystemMessage (sent);
            if (request.type == TPType.HERE) move (execer, dst);
            else move (dst, execer);
            latesttpa.remove (execer.getName () .getString ());
            return true;
        }
    }
    public static void regcmd (RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher ();
        register_single (TPType.TO, "tpa", dispatcher);
        register_single (TPType.HERE, "tpahere", dispatcher);
        dispatcher.register (
            Commands.literal ("tpaccept")
            .executes (
                ctx -> {
                    Player execer = ctx.getSource () .getPlayer ();
                    ServerLevel level = ctx.getSource () .getLevel ();
                    return tpaccept (execer, level) ? 1 : 0;
                }
            )
        );
    }
    // Undie totem inventory poping
    public static void pop_totem (LivingDeathEvent event) {
        var entity = event.getEntity ();
        if (entity instanceof ServerPlayer player) {
            var items = player.getInventory ();
            ItemStack copy = null;
            for (var item : items.items) {
                if (item.is (Items.TOTEM_OF_UNDYING)) {
                    // Pop it!
                    copy = item.copy ();
                    item.shrink (1);
                    break;
                }
            }
            if (copy != null) {
                player.awardStat (Stats.ITEM_USED.get (Items.TOTEM_OF_UNDYING));
                CriteriaTriggers.USED_TOTEM.trigger (player, copy);
                player.gameEvent (GameEvent.ITEM_INTERACT_FINISH);
                player.setHealth (1f);
                player.removeAllEffects ();
                player.addEffect (new MobEffectInstance (MobEffects.REGENERATION, 900, 1));
                player.addEffect (new MobEffectInstance (MobEffects.ABSORPTION, 100, 1));
                player.addEffect (new MobEffectInstance (MobEffects.FIRE_RESISTANCE, 800, 0));
                player.level () .broadcastEntityEvent (player, (byte) 35); // Why 35? I guess it's just some hard coding thing
                event.setCanceled (true);
            }
        }
    }
    public static final Map <Player, Long> compassclick = new HashMap <> ();
    public static void compass_click (PlayerInteractEvent event) {
        Player player = event.getEntity ();
        var hand = event.getHand ();
        ItemStack stack = player.getItemInHand (hand);
        if (! event.getLevel () .isClientSide () && stack.is (Items.RECOVERY_COMPASS)) {
            Long ll = compassclick.get (player);
            long gt = event.getLevel () .getGameTime ();
            if (ll == null || ll.longValue () + 200 < gt) {
                Component sent = Component.translatable ("fiiis.hint.recoverpre") .withColor (ORANGE) .append (stack.getDisplayName () .copy () .withColor (GREEN))
                    .append (Component.translatable ("fiiis.hint.recover") .withColor (ORANGE));
                player.sendSystemMessage (sent);
                sent = Component.translatable ("fiiis.warn.recover") .withColor (RED);
                player.sendSystemMessage (sent);
                sent = Component.translatable ("fiiis.hint.recover.again") .withColor (GREEN);
                player.sendSystemMessage (sent);
                compassclick.put (player, gt);
            } else {
                // Because I do NOT know how many times this listener is called when a player uses the compass
                if (ll.longValue () + 2 >= gt) return;
                var loc = player.getLastDeathLocation ();
                if (loc.isEmpty ()) {
                    Component sent = Component.translatable ("fiiis.error.nondeath") .withColor (DARK_RED);
                    player.sendSystemMessage (sent);
                } else {
                    GlobalPos pos = loc.get ();
                    ServerLevel l = event.getLevel () .getServer () .getLevel (pos.dimension ());
                    player.teleportTo (l, pos.pos () .getX (), pos.pos () .getY (), pos.pos () .getZ (), Set.of (), 0f, 0f);
                    player.displayClientMessage (Component.translatable ("fiiis.suc.recover") .withColor (GREEN), true);
                    stack.shrink (1);
                }
                compassclick.remove (player);
            }
        }
    }
    // Now, when a campfire is placed, it is not lit.
    public static void torchlit (RightClickBlock event) {
        // Firstly the item must be a torch (flower?)
        ItemStack stack = event.getItemStack ();
        if (stack.is (Items.TORCH) || stack.is (Items.TORCHFLOWER)) {
            var level = event.getLevel ();
            var bs = level.getBlockState (event.getPos ());
            if (bs.is (Blocks.CAMPFIRE)) {
                if (bs.getValue (CampfireBlock.LIT) .booleanValue () == false) {
                    stack.shrink (1);
                    level.setBlockAndUpdate (event.getPos (), bs.setValue (CampfireBlock.LIT, true));
                }
            }
        }
    }
    public static final Set <Pair <Level, BlockPos>> lit = new HashSet <> ();
    public static void placeunlit (EntityPlaceEvent event) {
        BlockState state = event.getPlacedBlock ();
        if (event.getLevel () .isClientSide ()) return;
        if (state.getBlock () == Blocks.CAMPFIRE && state.getValue (CampfireBlock.LIT) == true) {
            // idk
            lit.add (Pair.of ((Level) event.getLevel (), event.getPos ()));
        }
    }
    public static void unlitontick (ServerTickEvent event) {
        for (var pair : lit) {
            Level level = pair.getFirst ();
            BlockPos pos = pair.getSecond ();
            level.setBlockAndUpdate (pos, level.getBlockState (pos) .setValue (CampfireBlock.LIT, false));
        }
        lit.clear ();
    }
}
