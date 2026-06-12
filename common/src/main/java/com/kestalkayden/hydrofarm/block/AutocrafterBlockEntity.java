package com.kestalkayden.hydrofarm.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.EnergyBuffer;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;
import com.kestalkayden.hydrofarm.platform.ItemApi;

/** Powered ghost-recipe autocrafter. Three logical inventories:
 *  <ul>
 *    <li><b>Template</b> (9 ghost slots) — count-1 markers defining the recipe positionally. Not
 *        consumed; they drive the output preview and the per-slot input filter.</li>
 *    <li><b>Input</b> (9 slots) — buffered ingredients. Hoppers/pumps push them in (filtered to the
 *        template), or they're pulled just-in-time from an adjacent inventory; one of each is
 *        consumed per craft. Shown as counts on the recipe grid.</li>
 *    <li><b>Output</b> (1 slot) — the crafted result; container remainders (buckets…) go back to a
 *        neighbour.</li>
 *  </ul>
 *
 *  <p>Input and output are exposed as one block item capability ({@link ItemView}): insert is
 *  template-filtered, extraction is output-only. It crafts on a fixed interval when the redstone
 *  mode permits, the input forms the templated recipe, the energy buffer holds
 *  {@link #ENERGY_PER_CRAFT}, and the output has room. Energy is <em>pulled</em> from the connected
 *  {@link EnergyPipeBlock} network each tick (sink-pull — cables are passive), and the energy buffer
 *  is also an insert-only capability so tech-mod cables can push power in directly. */
public class AutocrafterBlockEntity extends BlockEntity implements EnergyBuffer {

    public static final int GRID_W = 3;
    public static final int GRID_H = 3;
    public static final int GRID_SIZE = GRID_W * GRID_H;
    public static final int OUTPUT_SIZE = 1;

    public static final int ENERGY_CAPACITY  = 2_000;
    /** Cheap per-craft cost. A single Hydroelectric Generator (~1 energy/tick) sustains a craft
     *  every {@link #CRAFT_INTERVAL} ticks many times over, so power is a gentle gate, not a grind. */
    public static final int ENERGY_PER_CRAFT = 4;
    /** Ticks between craft attempts (20 = one craft/second when fed and powered). */
    public static final int CRAFT_INTERVAL = 20;
    /** Standby draw applied once per {@link #CRAFT_INTERVAL} (≈1 energy/second) whenever the block is
     *  redstone-enabled and not actively crafting that tick — a continuous "powered on" cost, even
     *  with no recipe set. Zero while redstone-disabled. */
    public static final int IDLE_ENERGY = 1;
    /** How long the green "producing" glow lingers past the last craft. Longer than
     *  {@link #CRAFT_INTERVAL} so a steady 1/second cadence reads as a constant glow, not a strobe. */
    private static final int CRAFT_GLOW_LINGER = 30;

    /** Energy pulled from the network per pull, and how often we top up. The source's own
     *  availability gates the real amount; this just keeps transfers gradual. */
    private static final int PULL_INTERVAL = 5;
    private static final int PULL_RATE = 256;

    /** Redstone behaviour. Stored as an ordinal so it round-trips as a plain int. */
    public static final int MODE_ALWAYS = 0;
    public static final int MODE_REQUIRES_SIGNAL = 1;
    public static final int MODE_DISABLED_BY_SIGNAL = 2;
    public static final int MODE_COUNT = 3;

    private int energyStored = 0;
    private int redstoneMode = MODE_ALWAYS;
    /** Game-tick of the last successful craft; drives the lingering green glow. Transient — re-lights
     *  within a craft of resuming after a reload. */
    private long lastCraftTick = Long.MIN_VALUE;

    /** The recipe matched from the current template, or null if the template forms no recipe.
     *  Recomputed lazily (templateDirty) on the server. */
    private CraftingRecipe cachedRecipe;
    private ItemStack outputPreview = ItemStack.EMPTY;
    private boolean templateDirty = true;
    /** 9-bit mask of which template cells are part of the current valid recipe (bit i = slot i).
     *  Synced to the client so the renderer can light those cells on the block's side grids. Zero
     *  when no valid recipe is prepped. */
    private int recipeMask = 0;
    /** Last-synced "has power" state; transitions push a block update so the renderer can show/hide
     *  the floating result without a per-tick energy sync. */
    private boolean lastSyncedPowered = false;

    /** Shared sink-pull helper — holds the cable-walk source cache (epoch + TTL) and the futile-pull
     *  back-off, same instance-per-BE pattern as the Mending Station and Repulser. Replaces a stale
     *  private fork of the same walk that predated the back-off. */
    private final EnergyNetwork energyNet = new EnergyNetwork();


    /** Ghost template — count-1 markers; never holds real stacks. Marking templateDirty re-derives
     *  the recipe + preview on the next tick. */
    public final SimpleContainer templateContainer = new SimpleContainer(GRID_SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            AutocrafterBlockEntity.this.templateDirty = true;
            AutocrafterBlockEntity.this.setChanged();
        }
    };

    public final SimpleContainer inputContainer = new SimpleContainer(GRID_SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            AutocrafterBlockEntity.this.setChanged();
        }
    };

    public final SimpleContainer outputContainer = new SimpleContainer(OUTPUT_SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            AutocrafterBlockEntity.this.setChanged();
        }
    };

    /** Input slots (0..8) plus the single output slot (9), for the combined item view. */
    private static final int[] ITEM_VIEW_SLOTS = java.util.stream.IntStream.range(0, GRID_SIZE + 1).toArray();

    /** The block's item capability: slots 0..8 are the template-filtered input (insert only — only
     *  the recipe's own ingredients are accepted), slot 9 is the crafted output (extract only). One
     *  capability that automation both feeds and drains. */
    public final class ItemView implements WorldlyContainer {
        @Override public int getContainerSize() { return GRID_SIZE + 1; }
        @Override public boolean isEmpty() { return inputContainer.isEmpty() && outputContainer.isEmpty(); }
        @Override public ItemStack getItem(int i) { return i < GRID_SIZE ? inputContainer.getItem(i) : outputContainer.getItem(0); }
        @Override public ItemStack removeItem(int i, int n) { return i < GRID_SIZE ? inputContainer.removeItem(i, n) : outputContainer.removeItem(0, n); }
        @Override public ItemStack removeItemNoUpdate(int i) { return i < GRID_SIZE ? inputContainer.removeItemNoUpdate(i) : outputContainer.removeItemNoUpdate(0); }
        @Override public void setItem(int i, ItemStack s) { if (i < GRID_SIZE) inputContainer.setItem(i, s); else outputContainer.setItem(0, s); }
        @Override public void setChanged() { AutocrafterBlockEntity.this.setChanged(); }
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
        @Override public void clearContent() { inputContainer.clearContent(); outputContainer.clearContent(); }
        @Override public int[] getSlotsForFace(Direction side) { return ITEM_VIEW_SLOTS; }
        @Override public boolean canPlaceItem(int i, ItemStack s) { return i < GRID_SIZE && acceptsInput(i, s); }
        @Override public boolean canPlaceItemThroughFace(int i, ItemStack s, Direction dir) { return i < GRID_SIZE && acceptsInput(i, s); }
        @Override public boolean canTakeItemThroughFace(int i, ItemStack s, Direction dir) { return i >= GRID_SIZE; }
    }

    private ItemView itemView;
    public ItemView itemView() {
        if (itemView == null) itemView = new ItemView();
        return itemView;
    }

    /** Whether {@code stack} may enter input slot {@code i}: the template marks that position and the
     *  item matches it. Template-filtered, so only recipe ingredients are ever accepted. */
    public boolean acceptsInput(int i, ItemStack stack) {
        ItemStack ghost = templateContainer.getItem(i);
        return !ghost.isEmpty() && ItemStack.isSameItem(ghost, stack);
    }

    /** Single-slot ghost of the current recipe's result. Recomputed alongside the recipe and synced
     *  to the client via the menu purely so the GUI can flag a faded preview in the output slot.
     *  Derived from the template — never extractable, never saved. */
    public final SimpleContainer previewContainer = new SimpleContainer(1);

    /** Loader-built insert-only energy capability wrapper (Team Reborn / NeoForge EnergyHandler),
     *  cached so its transaction snapshots stay consistent. Mirrors the generator. */
    private Object energyExposure;

    public AutocrafterBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.AUTOCRAFTER_BE.get(), pos, state);
    }

    @SuppressWarnings("unchecked")
    public <T> T energyExposure(Function<AutocrafterBlockEntity, T> factory) {
        if (energyExposure == null) energyExposure = factory.apply(this);
        return (T) energyExposure;
    }

    /** Cached NeoForge output item handler (one instance per BE for transaction consistency). */
    private Object itemExposure;

    @SuppressWarnings("unchecked")
    public <T> T itemExposure(Function<AutocrafterBlockEntity, T> factory) {
        if (itemExposure == null) itemExposure = factory.apply(this);
        return (T) itemExposure;
    }

    // ---- Energy buffer (insert-only to the outside) ---------------------------------------
    @Override public int getEnergyStored()   { return energyStored; }
    @Override public int getEnergyCapacity() { return ENERGY_CAPACITY; }

    /** Accepts up to {@code amount} energy; returns the amount accepted. Used by the insert-only
     *  capability (tech cables) and the network pull. */
    @Override
    public int insertEnergy(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, ENERGY_CAPACITY - energyStored);
        if (accepted > 0) { energyStored += accepted; setChanged(); }
        return accepted;
    }

    /** Direct setter for the loader wrapper's transaction snapshot restore. */
    @Override
    public void setEnergyStored(int v) {
        energyStored = Math.max(0, Math.min(ENERGY_CAPACITY, v));
        setChanged();
    }

    // ---- Redstone mode --------------------------------------------------------------------
    public int getRedstoneMode() { return redstoneMode; }
    public void cycleRedstoneMode() {
        redstoneMode = (redstoneMode + 1) % MODE_COUNT;
        setChanged();
    }

    public ItemStack getOutputPreview() { return outputPreview; }

    private boolean enabledByRedstone(Level level, BlockPos pos) {
        return switch (redstoneMode) {
            case MODE_REQUIRES_SIGNAL -> level.hasNeighborSignal(pos);
            case MODE_DISABLED_BY_SIGNAL -> !level.hasNeighborSignal(pos);
            default -> true;
        };
    }

    // ---- Tick -----------------------------------------------------------------------------
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long now = level.getGameTime();

        if (energyStored < ENERGY_CAPACITY && now % PULL_INTERVAL == 0) {
            energyNet.pull(level, pos, this, PULL_RATE);
        }

        if (templateDirty) {
            recomputeRecipe(level);
            templateDirty = false;
        }

        if (now % CRAFT_INTERVAL == 0 && enabledByRedstone(level, pos)) {
            boolean crafted = cachedRecipe != null && energyStored >= ENERGY_PER_CRAFT
                && pullAndCraft(level, pos);
            if (crafted) {
                lastCraftTick = now;
            } else if (energyStored > 0) {
                // Standby draw whenever powered on (and not crafting this tick) — even with no recipe.
                energyStored = Math.max(0, energyStored - IDLE_ENERGY);
                setChanged();
            }
        }

        // Crafting glow: the red side accent turns green while producing, lingering past the last
        // craft so a ~1/second cadence reads as a steady glow rather than a strobe. Client-only
        // update (flag 2) — no neighbour/redstone churn, and just a toggle on start/stop of a run.
        boolean glowing = lastCraftTick != Long.MIN_VALUE && (now - lastCraftTick) < CRAFT_GLOW_LINGER;
        if (state.getValue(AutocrafterBlock.CRAFTING) != glowing) {
            level.setBlock(pos, state.setValue(AutocrafterBlock.CRAFTING, glowing), Block.UPDATE_CLIENTS);
        }

        // Sync the floating-result hologram's "powered" gate only on transition (energy crossing
        // zero), so the renderer can show/hide it without a block update every energy tick.
        boolean powered = energyStored > 0;
        if (powered != lastSyncedPowered) {
            lastSyncedPowered = powered;
            BlockState st = getBlockState();
            level.sendBlockUpdated(getBlockPos(), st, st, Block.UPDATE_CLIENTS);
        }
    }

    /** Re-derives the recipe + output preview from the ghost template, and mirrors the preview into
     *  {@link #previewContainer} so the menu syncs it to the client. */
    private void recomputeRecipe(ServerLevel level) {
        CraftingInput input = craftingInputOf(templateContainer);
        Optional<CraftingRecipe> match = input.isEmpty() ? Optional.empty()
            : level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).map(RecipeHolder::value);
        if (match.isPresent()) {
            cachedRecipe = match.get();
            outputPreview = cachedRecipe.assemble(input);
        } else {
            cachedRecipe = null;
            outputPreview = ItemStack.EMPTY;
        }
        previewContainer.setItem(0, outputPreview.isEmpty() ? ItemStack.EMPTY : outputPreview.copy());

        // Recompute the lit-cell mask (filled template cells, only when the recipe is valid) and push
        // it to clients when it changes so the renderer can light those cells.
        int newMask = 0;
        if (cachedRecipe != null) {
            for (int i = 0; i < GRID_SIZE; i++) {
                if (!templateContainer.getItem(i).isEmpty()) newMask |= (1 << i);
            }
        }
        if (newMask != recipeMask) {
            recipeMask = newMask;
            BlockState st = getBlockState();
            level.sendBlockUpdated(getBlockPos(), st, st, Block.UPDATE_CLIENTS);
        }

        ejectMismatchedInput(level);
    }

    /** 9-bit mask of template cells lit by the current valid recipe (read by the renderer). */
    public int getRecipeMask() { return recipeMask; }
    /** The crafted result to float above the block (empty when no valid recipe). Renderer-side. */
    public ItemStack getDisplayResult() { return previewContainer.getItem(0); }
    /** Whether the energy buffer holds any charge — gates the floating result. Renderer-side. */
    public boolean isPowered() { return energyStored > 0; }

    /** After a recipe change, hand back any buffered ingredient whose slot the new template no longer
     *  matches — otherwise it would jam that slot (can't be consumed, can't be re-pushed). */
    private void ejectMismatchedInput(ServerLevel level) {
        BlockPos pos = getBlockPos();
        List<ItemApi.Endpoint> sources = null;
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack s = inputContainer.getItem(i);
            if (s.isEmpty() || acceptsInput(i, s)) continue;
            if (sources == null) sources = adjacentItemSources(level, pos);
            distributeOrDrop(level, pos, sources, s);
            inputContainer.setItem(i, ItemStack.EMPTY);
        }
    }

    /** Craft from the input buffer. Items pushed in by hoppers/pumps accumulate in the (template-
     *  filtered) input slots; any slot still empty is topped up just-in-time from an adjacent
     *  inventory. One of each ingredient is consumed per craft, leaving any surplus buffered. */
    private boolean pullAndCraft(ServerLevel level, BlockPos pos) {
        if (outputPreview.isEmpty()) return false;
        ItemStack out = outputContainer.getItem(0);
        if (!out.isEmpty() && (!ItemStack.isSameItemSameComponents(out, outputPreview)
                || out.getCount() + outputPreview.getCount() > out.getMaxStackSize())) return false;

        // The item capability fills slots greedily, piling a same-item ingredient into the first
        // matching slot — spread it so every templated position has one. Then top up any slot still
        // empty from an adjacent inventory (the pull-fed setup).
        balanceInput();
        tryCompleteFromSources(level, pos);

        CraftingInput input = craftingInputOf(inputContainer);
        if (!cachedRecipe.matches(input, level)) return false;   // not enough yet — buffered items wait
        ItemStack result = cachedRecipe.assemble(input);
        if (result.isEmpty()) return false;
        NonNullList<ItemStack> remainders = cachedRecipe.getRemainingItems(input);

        // Consume one of each ingredient (any buffered surplus stays for the next craft).
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack slot = inputContainer.getItem(i);
            if (!slot.isEmpty()) slot.shrink(1);
        }
        inputContainer.setChanged();

        if (out.isEmpty()) outputContainer.setItem(0, result.copy());
        else out.grow(result.getCount());
        outputContainer.setChanged();

        // Hand container remainders (empty buckets…) back to a neighbour; drop rather than void them.
        // Resolve the neighbour sink list once and reuse it — built lazily, since most recipes have
        // no remainders at all (was rebuilt per remainder: a fresh list + 6 neighbour finds each).
        List<ItemApi.Endpoint> sinks = null;
        for (ItemStack rem : remainders) {
            if (rem.isEmpty()) continue;
            if (sinks == null) sinks = adjacentItemSources(level, pos);
            distributeOrDrop(level, pos, sinks, rem.copy());
        }

        energyStored -= ENERGY_PER_CRAFT;
        setChanged();
        return true;
    }

    /** Spread buffered ingredients so each templated slot holds at least one — counters the greedy
     *  fill order of the item capability (which would pile a same-item ingredient into one slot, e.g.
     *  all 3 wheat for bread into a single cell). */
    private void balanceInput() {
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack ti = templateContainer.getItem(i);
            if (ti.isEmpty() || !inputContainer.getItem(i).isEmpty()) continue;
            for (int j = 0; j < GRID_SIZE; j++) {
                if (j == i) continue;
                ItemStack sj = inputContainer.getItem(j);
                if (sj.getCount() <= 1 || !ItemStack.isSameItem(sj, ti)) continue;
                inputContainer.setItem(i, sj.copyWithCount(1));
                sj.shrink(1);
                break;
            }
        }
    }

    /** Top up still-empty ingredient slots by pulling from adjacent inventories — but only if the
     *  whole shortfall is available, so a partial pull never strands items. Pushed-in items are kept
     *  as-is; this only fills the gaps. */
    private void tryCompleteFromSources(ServerLevel level, BlockPos pos) {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < GRID_SIZE; i++) {
            if (!templateContainer.getItem(i).isEmpty() && inputContainer.getItem(i).isEmpty()) missing.add(i);
        }
        if (missing.isEmpty()) return;

        List<ItemApi.Endpoint> sources = adjacentItemSources(level, pos);
        if (sources.isEmpty()) return;

        List<ItemStack> protos = new ArrayList<>();
        List<Integer> needs = new ArrayList<>();
        for (int i : missing) {
            ItemStack p = templateContainer.getItem(i);
            int idx = indexOfProto(protos, p);
            if (idx < 0) { protos.add(p); needs.add(1); } else needs.set(idx, needs.get(idx) + 1);
        }
        for (int k = 0; k < protos.size(); k++) {
            int avail = 0;
            for (ItemApi.Endpoint e : sources) avail += e.amountAvailable(protos.get(k));
            if (avail < needs.get(k)) return;   // can't fully complete via pull — leave it; push may finish
        }
        for (int i : missing) {
            ItemStack pulled = pullOne(sources, templateContainer.getItem(i));
            if (!pulled.isEmpty()) inputContainer.setItem(i, pulled);
        }
    }

    private static int indexOfProto(List<ItemStack> protos, ItemStack p) {
        for (int k = 0; k < protos.size(); k++) {
            if (ItemStack.isSameItemSameComponents(protos.get(k), p)) return k;
        }
        return -1;
    }

    private List<ItemApi.Endpoint> adjacentItemSources(ServerLevel level, BlockPos pos) {
        List<ItemApi.Endpoint> sources = new ArrayList<>();
        for (Direction d : Direction.values()) {
            ItemApi.Endpoint e = HydrofarmPlatform.items().find(level, pos.relative(d), d.getOpposite());
            if (e != null) sources.add(e);
        }
        return sources;
    }

    private static ItemStack pullOne(List<ItemApi.Endpoint> sources, ItemStack proto) {
        for (ItemApi.Endpoint e : sources) {
            ItemStack got = e.extract(proto, 1);
            if (!got.isEmpty()) return got;
        }
        return ItemStack.EMPTY;
    }

    /** Insert {@code stack} into the sources in order; whatever doesn't fit is dropped in-world so it
     *  is never voided. Mutates {@code stack}. */
    private static void distributeOrDrop(ServerLevel level, BlockPos pos,
                                         List<ItemApi.Endpoint> sources, ItemStack stack) {
        for (ItemApi.Endpoint e : sources) {
            int ins = e.insert(stack);
            if (ins > 0) stack.shrink(ins);
            if (stack.isEmpty()) return;
        }
        if (!stack.isEmpty()) Block.popResource(level, pos, stack);
    }

    /** Builds a 3×3 crafting input from a container's first {@link #GRID_SIZE} slots. */
    private static CraftingInput craftingInputOf(SimpleContainer container) {
        List<ItemStack> items = new ArrayList<>(GRID_SIZE);
        for (int i = 0; i < GRID_SIZE; i++) items.add(container.getItem(i));
        return CraftingInput.of(GRID_W, GRID_H, items);
    }

    /** Drop the real input + output contents when the block is broken (the ghost template is just
     *  markers, not items). 26.1 routes break side-effects through the BE, not Block.onRemove. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide()) {
            net.minecraft.world.Containers.dropContents(level, pos, inputContainer);
            net.minecraft.world.Containers.dropContents(level, pos, outputContainer);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        saveContainer(out, "Template", templateContainer);
        saveContainer(out, "Input", inputContainer);
        saveContainer(out, "Output", outputContainer);
        saveContainer(out, "Preview", previewContainer);   // synced so the renderer can float the result
        out.putInt("Energy", energyStored);
        out.putInt("RedstoneMode", redstoneMode);
        out.putInt("RecipeMask", recipeMask);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        loadContainer(in, "Template", templateContainer);
        loadContainer(in, "Input", inputContainer);
        loadContainer(in, "Output", outputContainer);
        loadContainer(in, "Preview", previewContainer);
        energyStored = Math.max(0, Math.min(ENERGY_CAPACITY, in.getIntOr("Energy", 0)));
        redstoneMode = Math.floorMod(in.getIntOr("RedstoneMode", MODE_ALWAYS), MODE_COUNT);
        recipeMask = in.getIntOr("RecipeMask", 0);
        templateDirty = true;
    }

    // ---- Client sync (renderer reads the lit-cell mask) -----------------------------------
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveCustomOnly(provider);
    }

    private static void saveContainer(ValueOutput out, String key, SimpleContainer container) {
        NonNullList<ItemStack> list = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < container.getContainerSize(); i++) list.set(i, container.getItem(i));
        ContainerHelper.saveAllItems(out.child(key), list);
    }

    private static void loadContainer(ValueInput in, String key, SimpleContainer container) {
        NonNullList<ItemStack> list = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
        in.child(key).ifPresent(c -> ContainerHelper.loadAllItems(c, list));
        for (int i = 0; i < container.getContainerSize(); i++) container.setItem(i, list.get(i));
    }
}
