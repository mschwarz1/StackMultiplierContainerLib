package com.hypixel.hytale.server.core.inventory.container;

import com.hypixel.fastutil.ints.Int2ObjectConcurrentHashMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.Short2ObjectMapCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.inventory.transaction.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * An {@link ItemContainer} that multiplies each item's maximum stack size by a
 * configurable factor. A {@code stackMultiplier} of 4 allows up to four times as many
 * items per slot as a normal container.
 *
 * <h3>Virtual-view design</h3>
 * The Hytale inventory system moves items by reading and writing slot data inside a plain
 * {@code writeAction} held by the base class, which caps quantities at
 * {@code item.getMaxStack()}. To work around this, {@link #internal_getSlot} and
 * {@link #internal_setSlot} present a virtual quantity during external transfers so that
 * the base-class arithmetic yields the correct effective count. Operations initiated by
 * this class use {@link #ownWriteAction}, which keeps {@link #ownWriteDepth} &gt; 0 so
 * real quantities are used throughout.
 *
 * <h3>Package placement and classloader boundary</h3>
 * This class intentionally declares the package
 * {@code com.hypixel.hytale.server.core.inventory.container} so that protected members of
 * {@link ItemContainer} are accessible without triggering a {@link VerifyError} across
 * classloader boundaries.
 */
public class StackMultiplierContainer extends ItemContainer {
    public static final BuilderCodec<StackMultiplierContainer> CODEC;
    protected short capacity;
    protected int stackMultiplier;
    protected final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    protected Short2ObjectMap<ItemStack> items;
    protected Short2ObjectMap<ItemStack> hiddenItems;

    private final Map<FilterActionType, Int2ObjectConcurrentHashMap<SlotFilter>> slotFilters = new ConcurrentHashMap();
    private FilterType globalFilter;

    // Tracks how deep we are in our own write operations. Zero means the write
    // lock was acquired by the base class on our behalf (external transfer).
    private static final ThreadLocal<Integer> ownWriteDepth = ThreadLocal.withInitial(() -> 0);

    /**
     * No-arg constructor used by the codec deserializer. Fields are populated
     * via codec field setters after construction.
     */
    protected StackMultiplierContainer() {
        this.globalFilter = FilterType.ALLOW_ALL;
        this.stackMultiplier = 1;
    }

    /**
     * Creates a new container with the given capacity and stack multiplier.
     *
     * @param capacity       number of slots; must be &gt; 0
     * @param stackMultiplier multiplier applied to each item's base max-stack; must be &ge; 1
     * @throws IllegalArgumentException if capacity &le; 0 or stackMultiplier &lt; 1
     */
    public StackMultiplierContainer(short capacity, short stackMultiplier) {
        this.globalFilter = FilterType.ALLOW_ALL;
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity is less than or equal zero! " + capacity + " <= 0");
        }

        if (stackMultiplier < 1) {
            throw new IllegalArgumentException("Stack multiplier is less than 1! " + stackMultiplier + " < 1");
        }

        this.stackMultiplier = stackMultiplier;
        this.capacity = capacity;
        this.items = new Short2ObjectOpenHashMap(capacity);
        this.hiddenItems = new Short2ObjectOpenHashMap(capacity);
    }

    /**
     * Copy constructor. Copies slot contents, capacity, stack multiplier, slot
     * filters, and global filter from {@code other}. The new container acquires
     * its own independent lock and maps.
     *
     * @param other the container to copy; must not be {@code null}
     */
    public StackMultiplierContainer(@Nonnull StackMultiplierContainer other) {
        this.globalFilter = FilterType.ALLOW_ALL;
        this.capacity = other.capacity;
        this.stackMultiplier = other.stackMultiplier;
        other.lock.readLock().lock();

        try {
            this.items = new Short2ObjectOpenHashMap(other.items);
            this.hiddenItems = new Short2ObjectOpenHashMap(other.hiddenItems);

        } finally {
            other.lock.readLock().unlock();
        }

        this.slotFilters.putAll(other.slotFilters);
        this.globalFilter = other.globalFilter;
    }

    // -------------------------------------------------------------------------
    // Lock actions
    // -------------------------------------------------------------------------

    /**
     * Executes {@code action} while holding the read lock.
     *
     * @param action the computation to run under the read lock
     * @return the value returned by {@code action}
     */
    protected <V> V readAction(@Nonnull Supplier<V> action) {
        this.lock.readLock().lock();

        Object var2;
        try {
            var2 = action.get();
        } finally {
            this.lock.readLock().unlock();
        }

        return (V)var2;
    }

    /**
     * Executes {@code action} with argument {@code x} while holding the read lock.
     *
     * @param action the computation to run under the read lock
     * @param x      the argument passed to {@code action}
     * @return the value returned by {@code action}
     */
    protected <X, V> V readAction(@Nonnull Function<X, V> action, X x) {
        this.lock.readLock().lock();

        Object var3;
        try {
            var3 = action.apply(x);
        } finally {
            this.lock.readLock().unlock();
        }

        return (V)var3;
    }

    /**
     * Executes {@code action} while holding the write lock.
     *
     * @param action the computation to run under the write lock
     * @return the value returned by {@code action}
     */
    protected <V> V writeAction(@Nonnull Supplier<V> action) {
        this.lock.writeLock().lock();

        Object var2;
        try {
            var2 = action.get();
        } finally {
            this.lock.writeLock().unlock();
        }

        return (V)var2;
    }

    /**
     * Executes {@code action} with argument {@code x} while holding the write lock.
     *
     * @param action the computation to run under the write lock
     * @param x      the argument passed to {@code action}
     * @return the value returned by {@code action}
     */
    protected <X, V> V writeAction(@Nonnull Function<X, V> action, X x) {
        this.lock.writeLock().lock();

        Object var3;
        try {
            var3 = action.apply(x);
        } finally {
            this.lock.writeLock().unlock();
        }

        return (V)var3;
    }

    /**
     * Used by all of our own write operations. Increments ownWriteDepth so that
     * internal_getSlot and internal_setSlot know not to apply the virtual view.
     */
    protected <V> V ownWriteAction(@Nonnull Supplier<V> action) {
        ownWriteDepth.set(ownWriteDepth.get() + 1);
        try {
            return writeAction(action);
        } finally {
            ownWriteDepth.set(ownWriteDepth.get() - 1);
        }
    }

    /**
     * Executes {@code action} with argument {@code x} under the write lock,
     * incrementing {@link #ownWriteDepth} so that {@link #isExternalTransfer()}
     * returns {@code false} for the duration.
     *
     * @param action the computation to run
     * @param x      the argument passed to {@code action}
     * @return the value returned by {@code action}
     */
    protected <X, V> V ownWriteAction(@Nonnull Function<X, V> action, X x) {
        ownWriteDepth.set(ownWriteDepth.get() + 1);
        try {
            return writeAction(action, x);
        } finally {
            ownWriteDepth.set(ownWriteDepth.get() - 1);
        }
    }

    /**
     * Returns true when the write lock is held but ownWriteDepth is zero,
     * meaning the base class acquired the lock on our behalf to transfer items
     * into us using the hardcoded item.getMaxStack() limit.
     */
    protected boolean isExternalTransfer() {
        return lock.writeLock().isHeldByCurrentThread() && ownWriteDepth.get() == 0;
    }

    // -------------------------------------------------------------------------
    // Slot access — virtual view for external transfers
    // -------------------------------------------------------------------------

    /**
     * During an external transfer the util reads this to decide how much space
     * is available in a slot. We return a virtual quantity so that the util's
     * arithmetic (space = baseMax - virtualQty) equals our actual remaining
     * space (effectiveMax - realQty), making the transfer land exactly as many
     * items as truly fit without losing any to the source.
     *
     * Three cases:
     *   realQty >= effectiveMax          → slot is truly full, show baseMax
     *   actualSpace >= baseMax           → a full base-stack fits, appear empty
     *                                      so the util uses the empty-slot path
     *   0 < actualSpace < baseMax        → partial space, virtualQty = baseMax - actualSpace
     */
    @Override
    protected ItemStack internal_getSlot(short slot) {
        ItemStack real = (ItemStack) this.items.get(slot);

        if (!isExternalTransfer() || ItemStack.isEmpty(real)) {
            return real;
        }

        int baseMax      = real.getItem().getMaxStack();
        int effectiveMax = getMaxStackForItem(real.getItem());
        int actualSpace  = effectiveMax - real.getQuantity();

        if (actualSpace <= 0) {
            // Truly full — show as baseMax so the util skips this slot.
            return real.withQuantity(baseMax);
        }
        if (actualSpace >= baseMax) {
            // A full chunk still fits. Appear empty so the util uses the
            // empty-slot path and adds a full baseMax chunk.
            return null;
        }
        // Partial space — return virtualQty such that baseMax - virtualQty = actualSpace.
        return real.withQuantity(baseMax - actualSpace);
    }

    /**
     * During an external transfer the util writes the result of its baseMax-
     * capped arithmetic here. We recover the actual delta and apply it to the
     * real quantity so nothing is lost or invented.
     *
     * virtualQty is what internal_getSlot reported for this slot:
     *   actualSpace >= baseMax  → virtualQty = 0  (slot appeared empty)
     *   otherwise               → virtualQty = baseMax - actualSpace
     *
     * delta = newVirtualQty - virtualQty  →  newRealQty = realQty + delta
     */
    @Override
    protected ItemStack internal_setSlot(short slot, ItemStack itemStack) {
        if (!isExternalTransfer()) {
            return ItemStack.isEmpty(itemStack)
                    ? this.internal_removeSlot(slot)
                    : (ItemStack) this.items.put(slot, itemStack);
        }

        if (ItemStack.isEmpty(itemStack)) {
            return (ItemStack) this.items.remove(slot);
        }

        ItemStack real = (ItemStack) this.items.get(slot);

        if (ItemStack.isEmpty(real) || !itemStack.isStackableWith(real)) {
            // Truly empty slot or incompatible type — plain placement.
            return (ItemStack) this.items.put(slot, itemStack);
        }

        // Recover the delta the util intended to add.
        int baseMax      = itemStack.getItem().getMaxStack();
        int effectiveMax = getMaxStackForItem(itemStack.getItem());
        int realQty      = real.getQuantity();
        int actualSpace  = effectiveMax - realQty;

        int virtualQty = (actualSpace >= baseMax) ? 0 : (baseMax - actualSpace);
        int delta      = itemStack.getQuantity() - virtualQty;
        int newRealQty = Math.min(realQty + delta, effectiveMax);

        return (ItemStack) this.items.put(slot, real.withQuantity(newRealQty));
    }

    /**
     * Removes and returns the raw {@link ItemStack} stored at {@code slot},
     * or {@code null} if the slot was empty. No virtual-view translation is applied.
     *
     * @param slot the slot index to remove
     * @return the previous stack, or {@code null}
     */
    protected ItemStack internal_removeSlot(short slot) {
        return (ItemStack)this.items.remove(slot);
    }

    // -------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------

    /**
     * During an external transfer the slotItemStack argument comes from
     * internal_getSlot and may be null (virtual empty) even though the slot
     * holds real items. We check the real map directly so the util cannot place
     * an incompatible item type into a slot that only appears empty.
     */
    protected boolean cantAddToSlot(short slot, ItemStack itemStack, ItemStack slotItemStack) {
        if (!this.globalFilter.allowInput() && !this.testFilter(FilterActionType.ADD, slot, itemStack)) {
            return true;
        }

        if (isExternalTransfer()) {
            ItemStack real = (ItemStack) this.items.get(slot);
            if (!ItemStack.isEmpty(real)) {
                // Slot has real content — guard type compatibility and true capacity.
                if (!itemStack.isStackableWith(real)) return true;
                if (real.getQuantity() >= getMaxStackForItem(real.getItem())) return true;
                return false;
            }
            // Real slot is empty — only the global filter (already checked) applies.
            return false;
        }

        // Normal path: check if any slot can eventually accept this item.
        for (short i = 0; i < this.capacity; i++) {
            ItemStack existing = (ItemStack) this.items.get(i);
            if (ItemStack.isEmpty(existing) || itemStack.isStackableWith(existing)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if items may not be removed from {@code slot},
     * either because the global filter disallows output or a slot-level
     * {@link FilterActionType#REMOVE} filter rejects the operation.
     *
     * @param slot the slot index to test
     * @return {@code true} if removal is blocked
     */
    protected boolean cantRemoveFromSlot(short slot) {
        return !this.globalFilter.allowOutput() || this.testFilter(FilterActionType.REMOVE, slot, (ItemStack)null);
    }

    /**
     * Returns {@code true} if items may not be dropped from {@code slot},
     * as determined by the slot-level {@link FilterActionType#DROP} filter.
     *
     * @param slot the slot index to test
     * @return {@code true} if dropping is blocked
     */
    protected boolean cantDropFromSlot(short slot) {
        return this.testFilter(FilterActionType.DROP, slot, (ItemStack)null);
    }

    /**
     * Returns {@code true} if a move from {@code fromContainer} slot {@code slotFrom}
     * into this container should be blocked. Always returns {@code false} by default;
     * subclasses may override to add cross-container move restrictions.
     *
     * @param fromContainer the source container
     * @param slotFrom      the source slot index
     * @return {@code true} if the move is blocked
     */
    protected boolean cantMoveToSlot(ItemContainer fromContainer, short slotFrom) {
        return false;
    }

    /**
     * Consults the registered {@link SlotFilter} for the given action type and slot.
     * Returns {@code false} (not blocked) if no filter is registered.
     *
     * @param actionType the filter action type to look up
     * @param slot       the slot index
     * @param itemStack  the item stack involved in the action (may be {@code null})
     * @return {@code true} if the registered filter rejects the action
     */
    private boolean testFilter(FilterActionType actionType, short slot, ItemStack itemStack) {
        Int2ObjectConcurrentHashMap<SlotFilter> map = (Int2ObjectConcurrentHashMap)this.slotFilters.get(actionType);
        if (map == null) {
            return false;
        } else {
            SlotFilter filter = (SlotFilter)map.get(slot);
            if (filter == null) {
                return false;
            } else {
                return !filter.test(actionType, this, slot, itemStack);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Capacity / lifecycle
    // -------------------------------------------------------------------------

    /**
     * Returns the number of slots in this container.
     *
     * @return the slot count
     */
    public short getCapacity() {
        return this.capacity;
    }

    /**
     * Clears all slots and returns a {@link ClearTransaction} describing the
     * removed stacks. Must be called while the write lock is held.
     *
     * @return a transaction recording what was removed
     */
    @Nonnull
    protected ClearTransaction internal_clear() {
        ItemStack[] itemStacks = new ItemStack[this.getCapacity()];

        for(short i = 0; i < itemStacks.length; ++i) {
            itemStacks[i] = (ItemStack)this.items.get(i);
        }

        this.items.clear();
        return new ClearTransaction(true, (short)0, itemStacks);
    }

    /**
     * Returns a deep copy of this container, including all slot contents,
     * capacity, stack multiplier, and filters.
     *
     * @return a new independent {@link StackMultiplierContainer} with the same state
     */
    @Nonnull
    public StackMultiplierContainer clone() {
        return new StackMultiplierContainer(this);
    }

    /**
     * Returns {@code true} if every slot in this container is empty.
     * Acquires the read lock for the check.
     *
     * @return {@code true} if the container holds no items
     */
    public boolean isEmpty() {
        this.lock.readLock().lock();

        boolean var1;
        try {
            if (!this.items.isEmpty()) {
                return super.isEmpty();
            }

            var1 = true;
        } finally {
            this.lock.readLock().unlock();
        }

        return var1;
    }

    /**
     * Sets the global filter that governs whether items may be added to or
     * removed from the container as a whole.
     *
     * @param globalFilter the new global filter; must not be {@code null}
     */
    public void setGlobalFilter(@Nonnull FilterType globalFilter) {
        this.globalFilter = (FilterType)Objects.requireNonNull(globalFilter);
    }

    /**
     * Registers or removes a per-slot filter for the given action type.
     * Pass {@code null} for {@code filter} to remove any existing filter for
     * that slot/action combination.
     *
     * @param actionType the action type the filter applies to
     * @param slot       the slot index to filter
     * @param filter     the filter to install, or {@code null} to remove
     */
    public void setSlotFilter(FilterActionType actionType, short slot, @Nullable SlotFilter filter) {
        validateSlotIndex(slot, this.getCapacity());
        if (filter != null) {
            ((Int2ObjectConcurrentHashMap)this.slotFilters.computeIfAbsent(actionType, (k) -> new Int2ObjectConcurrentHashMap())).put(slot, filter);
        } else {
            this.slotFilters.computeIfPresent(actionType, (k, map) -> {
                map.remove(slot);
                return map.isEmpty() ? null : map;
            });
        }

    }

    // -------------------------------------------------------------------------
    // Public query / add API
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link ItemStack} in the given slot, or {@code null} if empty.
     * Acquires the read lock and does not apply the virtual view.
     *
     * @param slot the slot index; must be in range {@code [0, capacity)}
     * @return the item stack, or {@code null}
     * @throws IndexOutOfBoundsException if {@code slot} is out of range
     */
    @Nullable
    public ItemStack getItemStack(short slot) {
        validateSlotIndex(slot, this.getCapacity());
        this.lock.readLock().lock();

        ItemStack var2;
        try {
            var2 = this.internal_getSlot(slot);
        } finally {
            this.lock.readLock().unlock();
        }

        return var2;
    }

    /**
     * Returns {@code true} if {@code itemStack} can be added to the specified slot.
     *
     * @param slot         the target slot index
     * @param itemStack    the item to test
     * @param allOrNothing if {@code true}, the full quantity must fit
     * @param filter       if {@code true}, apply slot and global filters
     * @return {@code true} if the item can be placed in the slot
     */
    public boolean canAddItemStackToSlot(short slot, @Nonnull ItemStack itemStack, boolean allOrNothing, boolean filter) {

        validateSlotIndex(slot, this.getCapacity());
        return (Boolean)this.ownWriteAction(() -> {
            int quantityRemaining = itemStack.getQuantity();
            ItemStack slotItemStack = this.internal_getSlot(slot);
            if (filter && this.cantAddToSlot(slot, itemStack, slotItemStack)) {
                return false;
            } else if (slotItemStack == null) {
                return true;
            } else if (!itemStack.isStackableWith(slotItemStack)) {
                return false;
            } else {
                int quantity = slotItemStack.getQuantity();
                int quantityAdjustment = Math.min(( getMaxStackForItem(slotItemStack.getItem())) - quantity, quantityRemaining);
                int newQuantityRemaining = quantityRemaining - quantityAdjustment;
                return allOrNothing ? quantityRemaining <= 0 : quantityRemaining != newQuantityRemaining;
            }
        });
    }

    /**
     * Returns {@code true} if {@code itemStack} can be fully added to this container.
     *
     * @param itemStack  the item to test
     * @param fullStacks if {@code true}, only consider empty slots (skip partial stacks)
     * @param filter     if {@code true}, apply slot and global filters
     * @return {@code true} if the entire quantity fits
     */
    public boolean canAddItemStack(@Nonnull ItemStack itemStack, boolean fullStacks, boolean filter) {
        Item item = itemStack.getItem();
        if (item == null) {
            throw new IllegalArgumentException(itemStack.getItemId() + " is an invalid item!");
        } else {
            int itemMaxStack = getMaxStackForItem(item);
            return (Boolean)this.readAction(() -> {
                int testQuantityRemaining = itemStack.getQuantity();
                if (!fullStacks) {
                    testQuantityRemaining = smcTestAddToExistingItemStacks(this, itemStack, itemMaxStack, testQuantityRemaining, filter);
                }

                testQuantityRemaining = smcTestAddToEmptySlots(this, itemStack, itemMaxStack, testQuantityRemaining, filter);
                return testQuantityRemaining <= 0;
            });
        }
    }

    /**
     * Returns {@code true} if every stack in {@code itemStacks} can be fully added
     * to this container. Returns {@code true} immediately when the list is empty.
     *
     * @param itemStacks the items to test; may be {@code null} or empty
     * @param fullStacks if {@code true}, only consider empty slots (skip partial stacks)
     * @param filter     if {@code true}, apply slot and global filters
     * @return {@code true} if all stacks fit
     */
    public boolean canAddItemStacks(@Nullable List<ItemStack> itemStacks, boolean fullStacks, boolean filter) {

        if (itemStacks != null && !itemStacks.isEmpty()) {
            List<TempItemData> tempItemDataList = new ObjectArrayList(itemStacks.size());

            for(ItemStack itemStack : itemStacks) {
                Item item = itemStack.getItem();
                if (item == null) {
                    throw new IllegalArgumentException(itemStack.getItemId() + " is an invalid item!");
                }

                tempItemDataList.add(new TempItemData(itemStack, item));
            }

            return (Boolean)this.readAction(() -> {
                for(TempItemData tempItemData : tempItemDataList) {
                    int itemMaxStack = getMaxStackForItem(tempItemData.item());
                    ItemStack itemStack = tempItemData.itemStack();
                    int testQuantityRemaining = itemStack.getQuantity();
                    if (!fullStacks) {
                        testQuantityRemaining = smcTestAddToExistingItemStacks(this, itemStack, itemMaxStack, testQuantityRemaining, filter);
                    }

                    testQuantityRemaining = smcTestAddToEmptySlots(this, itemStack, itemMaxStack, testQuantityRemaining, filter);
                    if (testQuantityRemaining > 0) {
                        return false;
                    }
                }

                return true;
            });
        } else {
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Internal transfer operations — all use ownWriteAction
    // -------------------------------------------------------------------------

    @Override
    @Nonnull
    protected ListTransaction<MoveTransaction<SlotTransaction>> internal_combineItemStacksIntoSlot(@Nonnull ItemContainer containerTo, short slotTo) {

        validateSlotIndex(slotTo, containerTo.getCapacity());
        return (ListTransaction)this.ownWriteAction(() -> {
            ItemStack itemStack = containerTo.getItemStack(slotTo);
            Item item = itemStack.getItem();

            int maxStack;
            if (containerTo instanceof StackMultiplierContainer) {
                maxStack = ((StackMultiplierContainer) containerTo).getMaxStackForItem(item);
            } else {
                maxStack = item.getMaxStack();
            }

            if (!ItemStack.isEmpty(itemStack) && itemStack.getQuantity() < maxStack) {
                int count = 0;
                int[] quantities = new int[this.getCapacity()];
                int[] indexes = new int[this.getCapacity()];

                for(short i = 0; i < this.getCapacity(); ++i) {
                    if (!this.cantRemoveFromSlot(i)) {
                        ItemStack itemFrom = this.internal_getSlot(i);
                        if (itemStack != itemFrom && !ItemStack.isEmpty(itemFrom) && itemFrom.isStackableWith(itemStack)) {
                            indexes[count] = i;
                            quantities[count] = itemFrom.getQuantity();
                            ++count;
                        }
                    }
                }

                IntArrays.quickSort(quantities, indexes, 0, count);
                int quantity = itemStack.getQuantity();
                List<MoveTransaction<SlotTransaction>> list = new ObjectArrayList();

                for(int ai = 0; ai < count && quantity < maxStack; ++ai) {
                    short i = (short)indexes[ai];
                    ItemStack itemFrom = this.internal_getSlot(i);
                    MoveTransaction<SlotTransaction> transaction = this.internal_moveItemStackFromSlot(i, itemFrom.getQuantity(), containerTo, slotTo, true);
                    list.add(transaction);
                    quantity = !ItemStack.isEmpty(((SlotTransaction)transaction.getAddTransaction()).getSlotAfter()) ? ((SlotTransaction)transaction.getAddTransaction()).getSlotAfter().getQuantity() : 0;
                }

                return new ListTransaction(true, list);
            } else {
                return new ListTransaction(false, Collections.emptyList());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Bulk-transfer overrides — must use ownWriteAction so internal_getSlot
    // returns real (non-virtual) quantities for the slot-iteration reads.
    // -------------------------------------------------------------------------

    /**
     * Shift-click / no-target-slot move: remove the full slot and add it to the
     * destination container.  The base class implementation calls internal_getSlot
     * inside a plain writeAction (isExternalTransfer=true), which makes occupied
     * backpack slots appear empty and therefore skips them.  By wrapping in
     * ownWriteAction we get real quantities throughout, and we put the remainder
     * back at the backpack's effectiveMax rather than baseMax.
     */
    @Override
    protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(
            short slot, @Nonnull ItemContainer containerTo, boolean allOrNothing, boolean filter) {
        validateSlotIndex(slot, this.getCapacity());
        return (MoveTransaction<ItemStackTransaction>) this.ownWriteAction(() -> {
            if (filter && this.cantRemoveFromSlot(slot)) {
                ItemStack current = this.internal_getSlot(slot);
                SlotTransaction removeTx = new SlotTransaction(false, ActionType.REMOVE, slot, current, current, (ItemStack) null, false, false, filter);
                return new MoveTransaction<>(false, removeTx, MoveType.MOVE_FROM_SELF, containerTo, SlotTransaction.FAILED_ADD);
            }
            ItemStack removed = this.internal_removeSlot(slot);
            if (ItemStack.isEmpty(removed)) {
                SlotTransaction removeTx = new SlotTransaction(false, ActionType.REMOVE, slot, (ItemStack) null, (ItemStack) null, (ItemStack) null, false, false, filter);
                return new MoveTransaction<>(false, removeTx, MoveType.MOVE_FROM_SELF, containerTo, SlotTransaction.FAILED_ADD);
            }
            SlotTransaction removeTx = new SlotTransaction(true, ActionType.REMOVE, slot, removed, (ItemStack) null, (ItemStack) null, false, false, filter);
            // Add to destination; its public addItemStack respects the dest's own max stack.
            ItemStackTransaction addTx = containerTo.addItemStack(removed, allOrNothing, false, filter);
            // Put any remainder back into the source slot.  Using internal_setSlot here
            // (ownWriteDepth > 0 so no virtual view) stores the full remainder without
            // the baseMax cap that InternalContainerUtilItemStack.internal_addItemStackToSlot
            // would impose.
            ItemStack remainder = addTx.getRemainder();
            if (!ItemStack.isEmpty(remainder)) {
                this.internal_setSlot(slot, remainder);
            }
            return new MoveTransaction<>(addTx.succeeded(), removeTx, MoveType.MOVE_FROM_SELF, containerTo, addTx);
        });
    }

    /**
     * Shift-click / take-all with explicit quantity: called by the public
     * moveItemStackFromSlot(short, int, ItemContainer) path, which is what
     * Inventory.smartMoveItem and Inventory.moveItemFromCheckToInventory both use.
     * The base class wraps the work in writeAction (isExternalTransfer=true -> virtual
     * view on), so internal_removeItemStack sees slots as empty and removes nothing.
     * Wrapping in ownWriteAction first (ownWriteDepth=1) keeps isExternalTransfer=false
     * when the base-class lambda runs, restoring real quantities throughout.
     */
    @Override
    protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(
            short slot, int quantity, @Nonnull ItemContainer dest, boolean allOrNothing, boolean filter) {
        return this.ownWriteAction(
                () -> super.internal_moveItemStackFromSlot(slot, quantity, dest, allOrNothing, filter));
    }

    /**
     * "Take all" / quickStack: the base class iterates slots via internal_getSlot
     * inside a plain writeAction, so the virtual view turns occupied backpack slots
     * into apparent empties and nothing is moved.  Override with ownWriteAction so
     * real quantities are visible, then delegate per-slot work to the public
     * moveItemStackFromSlot (which goes through our override above).
     */
    @Override
    @SuppressWarnings("unchecked")
    protected ListTransaction<MoveTransaction<ItemStackTransaction>> internal_moveAllItemStacksTo(
            Predicate<ItemStack> predicate,
            ItemContainer[] containers) {
        return (ListTransaction<MoveTransaction<ItemStackTransaction>>) this.ownWriteAction(() -> {
            List<MoveTransaction<ItemStackTransaction>> results = new ObjectArrayList<>();
            for (short i = 0; i < this.getCapacity(); i++) {
                if (this.cantRemoveFromSlot(i)) continue;
                ItemStack slot = this.internal_getSlot(i);   // real qty: ownWriteDepth > 0
                if (ItemStack.isEmpty(slot)) continue;
                if (predicate != null && !predicate.test(slot)) continue;
                results.addAll(this.moveItemStackFromSlot(i, containers).getList());
            }
            return new ListTransaction<>(true, results);
        });
    }

    // Replicates InternalContainerUtilItemStack.internal_setItemStackForSlot but uses a
    // StackMultiplierContainer-typed receiver so that all protected calls (internal_getSlot,
    // cantAddToSlot, internal_setSlot) pass JVM bytecode verification across classloaders.
    // Must be called from within the destination container's write lock.
    private ItemStackSlotTransaction smcSetSlot(StackMultiplierContainer dest, short slot, ItemStack item, boolean filter) {
        ItemStack slotBefore = dest.internal_getSlot(slot);
        if (filter && dest.cantAddToSlot(slot, item, slotBefore)) {
            return new ItemStackSlotTransaction(false, ActionType.SET, slot, slotBefore, slotBefore, (ItemStack) null, false, false, filter, false, item, item);
        }
        ItemStack oldSlot = dest.internal_setSlot(slot, item);
        return new ItemStackSlotTransaction(true, ActionType.SET, slot, oldSlot, item, (ItemStack) null, false, false, filter, false, item, (ItemStack) null);
    }

    // --- Replacements for InternalContainerUtilItemStack protected static methods ---
    // All methods in InternalContainerUtilItemStack are protected, so they cannot be
    // called from the plugin classloader. These replicate the exact bytecode logic using
    // a StackMultiplierContainer-typed parameter so protected calls are verifier-safe.

    private static int smcTestAddToExistingSlot(StackMultiplierContainer c, short slot, ItemStack item, int quantityLeft, int maxStack, boolean filter) {
        ItemStack existing = c.internal_getSlot(slot);
        if (ItemStack.isEmpty(existing)) return quantityLeft;
        if (!existing.isStackableWith(item)) return quantityLeft;
        if (filter && c.cantAddToSlot(slot, item, existing)) return quantityLeft;
        int canAdd = Math.min(maxStack - existing.getQuantity(), quantityLeft);
        return quantityLeft - canAdd;
    }

    private static int smcTestAddToExistingItemStacks(StackMultiplierContainer c, ItemStack item, int maxStack, int quantityLeft, boolean filter) {
        for (short i = 0; i < c.getCapacity() && quantityLeft > 0; i++) {
            quantityLeft = smcTestAddToExistingSlot(c, i, item, quantityLeft, maxStack, filter);
        }
        return quantityLeft;
    }

    private static int smcTestAddToEmptySlots(StackMultiplierContainer c, ItemStack item, int maxStack, int quantityLeft, boolean filter) {
        for (short i = 0; i < c.getCapacity() && quantityLeft > 0; i++) {
            ItemStack existing = c.internal_getSlot(i);
            if (existing != null && !existing.isEmpty()) continue;
            if (filter && c.cantAddToSlot(i, item, existing)) continue;
            quantityLeft -= Math.min(maxStack, quantityLeft);
        }
        return quantityLeft;
    }

    private static ItemStackSlotTransaction smcAddToExistingSlot(StackMultiplierContainer c, short slot, ItemStack item, int maxStack, boolean filter) {
        ItemStack existing = c.internal_getSlot(slot);
        if (ItemStack.isEmpty(existing) || !existing.isStackableWith(item)
                || (filter && c.cantAddToSlot(slot, item, existing))) {
            return new ItemStackSlotTransaction(false, ActionType.ADD, slot, existing, existing, (ItemStack) null, false, false, filter, true, item, item);
        }
        int itemQty = item.getQuantity();
        int existingQty = existing.getQuantity();
        int toAdd = Math.min(maxStack - existingQty, itemQty);
        if (toAdd <= 0) {
            return new ItemStackSlotTransaction(false, ActionType.ADD, slot, existing, existing, (ItemStack) null, false, false, filter, true, item, item);
        }
        int newExistingQty = existingQty + toAdd;
        int remaining = itemQty - toAdd;
        ItemStack newSlot = existing.withQuantity(newExistingQty);
        if (newExistingQty > 0) {
            c.internal_setSlot(slot, newSlot);
        } else {
            c.internal_removeSlot(slot);
        }
        ItemStack remainder = (remaining != itemQty) ? item.withQuantity(remaining) : item;
        return new ItemStackSlotTransaction(true, ActionType.ADD, slot, existing, newSlot, (ItemStack) null, false, false, filter, true, item, remainder);
    }

    private static ItemStackSlotTransaction smcAddToEmptySlot(StackMultiplierContainer c, short slot, ItemStack item, int maxStack, boolean filter) {
        ItemStack existing = c.internal_getSlot(slot);
        if ((existing != null && !existing.isEmpty()) || (filter && c.cantAddToSlot(slot, item, existing))) {
            return new ItemStackSlotTransaction(false, ActionType.ADD, slot, existing, existing, (ItemStack) null, false, false, filter, false, item, item);
        }
        int itemQty = item.getQuantity();
        int toAdd = Math.min(maxStack, itemQty);
        int remaining = itemQty - toAdd;
        ItemStack placed = item.withQuantity(toAdd);
        c.internal_setSlot(slot, placed);
        ItemStack remainder = (remaining != itemQty) ? item.withQuantity(remaining) : item;
        return new ItemStackSlotTransaction(true, ActionType.ADD, slot, existing, placed, (ItemStack) null, false, false, filter, false, item, remainder);
    }

    protected MoveTransaction<SlotTransaction> internal_moveItemStackFromSlot(short slot, int quantity, @Nonnull ItemContainer containerTo, short slotTo, boolean filter) {
        validateSlotIndex(slot, this.getCapacity());
        validateSlotIndex(slotTo, containerTo.getCapacity());
        validateQuantity(quantity);

        // The JVM bytecode verifier (spec §4.10.1.8) requires that when calling a
        // protected method from a superclass via invokevirtual, the receiver on the
        // stack must be assignable to the current class — not just the declaring class.
        // Because ItemContainer (HytaleServer.jar, server classloader) and
        // StackMultiplierContainer (plugin jar, plugin classloader) are in different
        // runtime packages, all protected calls on a plain ItemContainer reference
        // inside a lambda synthetic method trigger a VerifyError.  Capturing a
        // StackMultiplierContainer-typed reference up front lets the verifier accept
        // those calls; for non-StackMultiplierContainer destinations the protected
        // filter methods are skipped (their base-class default is false / no-block)
        // and internal_setSlot falls back to the public static utility.
        final StackMultiplierContainer containerToSMC =
                containerTo instanceof StackMultiplierContainer
                        ? (StackMultiplierContainer) containerTo : null;

        // Extract the transfer logic into a supplier so we can dispatch it
        // through ownWriteAction on whichever destination type we have.
        Supplier<MoveTransaction<SlotTransaction>> destLogic = () -> {
            if (filter && this.cantRemoveFromSlot(slot)) {
                ItemStack itemStack = this.internal_getSlot(slot);
                SlotTransaction slotTransaction = new SlotTransaction(false, ActionType.REMOVE, slot, itemStack, itemStack, (ItemStack)null, false, false, filter);
                return new MoveTransaction(false, slotTransaction, MoveType.MOVE_FROM_SELF, containerTo, SlotTransaction.FAILED_ADD);
            } else if (filter && containerToSMC != null && containerToSMC.cantMoveToSlot(this, slot)) {
                ItemStack itemStack = this.internal_getSlot(slot);
                SlotTransaction slotTransaction = new SlotTransaction(false, ActionType.REMOVE, slot, itemStack, itemStack, (ItemStack)null, false, false, filter);
                return new MoveTransaction(false, slotTransaction, MoveType.MOVE_FROM_SELF, containerTo, SlotTransaction.FAILED_ADD);
            } else {
                ItemStackSlotTransaction fromTransaction = this.internal_removeItemStack(slot, quantity);
                if (!fromTransaction.succeeded()) {
                    return new MoveTransaction(false, fromTransaction, MoveType.MOVE_FROM_SELF, containerTo, SlotTransaction.FAILED_ADD);
                } else {
                    ItemStack itemFrom = fromTransaction.getOutput();
                    if (ItemStack.isEmpty(itemFrom)) {
                        return new MoveTransaction(true, fromTransaction, MoveType.MOVE_FROM_SELF, containerTo, SlotTransaction.FAILED_ADD);
                    } else {
                        ItemStack itemTo = containerTo.getItemStack(slotTo);
                        if (filter && containerToSMC != null && containerToSMC.cantAddToSlot(slotTo, itemFrom, itemTo)) {
                            this.internal_setSlot(slot, fromTransaction.getSlotBefore());
                            SlotTransaction slotTransaction = new SlotTransaction(true, ActionType.REMOVE, slot, fromTransaction.getSlotBefore(), fromTransaction.getSlotAfter(), (ItemStack)null, false, false, filter);
                            SlotTransaction addTransaction = new SlotTransaction(false, ActionType.ADD, slotTo, itemTo, itemTo, (ItemStack)null, false, false, filter);
                            return new MoveTransaction(false, slotTransaction, MoveType.MOVE_FROM_SELF, containerTo, addTransaction);
                        } else if (ItemStack.isEmpty(itemTo)) {
                            int destMax = getMaxStackForContainer(containerTo, itemFrom.getItem());
                            ItemStack toPlace = itemFrom.getQuantity() <= destMax ? itemFrom : itemFrom.withQuantity(destMax);
                            if (itemFrom.getQuantity() > destMax) {
                                // Put the overflow back into the source slot so items aren't lost.
                                int remainder = itemFrom.getQuantity() - destMax;
                                int quantityLeft = !ItemStack.isEmpty(fromTransaction.getSlotAfter()) ? fromTransaction.getSlotAfter().getQuantity() : 0;
                                this.internal_setSlot(slot, itemFrom.withQuantity(remainder + quantityLeft));
                            }
                            ItemStackSlotTransaction addTransaction = containerToSMC != null
                                    ? smcSetSlot(containerToSMC, slotTo, toPlace, filter)
                                    : containerTo.setItemStackForSlot(slotTo, toPlace, filter);
                            return new MoveTransaction(true, fromTransaction, MoveType.MOVE_FROM_SELF, containerTo, addTransaction);
                        } else if (!itemFrom.isStackableWith(itemTo)) {
                            if (ItemStack.isEmpty(fromTransaction.getSlotAfter())) {
                                if (filter && this.cantAddToSlot(slot, itemTo, itemFrom)) {
                                    this.internal_setSlot(slot, fromTransaction.getSlotBefore());
                                    SlotTransaction slotTransaction = new SlotTransaction(true, ActionType.REMOVE, slot, fromTransaction.getSlotBefore(), fromTransaction.getSlotAfter(), (ItemStack)null, false, false, filter);
                                    SlotTransaction addTransaction = new SlotTransaction(false, ActionType.ADD, slotTo, itemTo, itemTo, (ItemStack)null, false, false, filter);
                                    return new MoveTransaction(false, slotTransaction, MoveType.MOVE_FROM_SELF, containerTo, addTransaction);
                                } else {
                                    // When swapping into a non-SMC container, guard against placing
                                    // an over-max quantity (e.g. a multiplied backpack stack into a
                                    // regular inventory slot).  Restore the source slot and fail so
                                    // the client sees a clean rejection rather than data corruption.
                                    if (containerToSMC == null) {
                                        int destMax = getMaxStackForContainer(containerTo, itemFrom.getItem());
                                        if (itemFrom.getQuantity() > destMax) {
                                            this.internal_setSlot(slot, fromTransaction.getSlotBefore());
                                            SlotTransaction from = new SlotTransaction(false, ActionType.REMOVE, slot, fromTransaction.getSlotBefore(), fromTransaction.getSlotAfter(), (ItemStack)null, false, false, filter);
                                            SlotTransaction to = new SlotTransaction(false, ActionType.ADD, slotTo, itemTo, itemTo, (ItemStack)null, false, false, filter);
                                            return new MoveTransaction(false, from, MoveType.MOVE_FROM_SELF, containerTo, to);
                                        }
                                    }
                                    this.internal_setSlot(slot, itemTo);
                                    if (containerToSMC != null) {
                                        containerToSMC.internal_setSlot(slotTo, itemFrom);
                                    } else {
                                        containerTo.setItemStackForSlot(slotTo, itemFrom, false);
                                    }
                                    SlotTransaction from = new SlotTransaction(true, ActionType.REPLACE, slot, itemFrom, itemTo, (ItemStack)null, false, false, filter);
                                    SlotTransaction to = new SlotTransaction(true, ActionType.REPLACE, slotTo, itemTo, itemFrom, (ItemStack)null, false, false, filter);
                                    return new MoveTransaction(true, from, MoveType.MOVE_FROM_SELF, containerTo, to);
                                }
                            } else {
                                this.internal_setSlot(slot, fromTransaction.getSlotBefore());
                                SlotTransaction slotTransaction = new SlotTransaction(true, ActionType.REMOVE, slot, fromTransaction.getSlotBefore(), fromTransaction.getSlotAfter(), (ItemStack)null, false, false, filter);
                                SlotTransaction addTransaction = new SlotTransaction(false, ActionType.ADD, slotTo, itemTo, itemTo, (ItemStack)null, false, false, filter);
                                return new MoveTransaction(false, slotTransaction, MoveType.MOVE_FROM_SELF, containerTo, addTransaction);
                            }
                        } else {
                            int maxStack = getMaxStackForContainer(containerTo, itemFrom.getItem());

                            int newQuantity = itemFrom.getQuantity() + itemTo.getQuantity();
                            if (newQuantity <= maxStack) {
                                ItemStackSlotTransaction addTransaction = containerToSMC != null
                                        ? smcSetSlot(containerToSMC, slotTo, itemTo.withQuantity(newQuantity), filter)
                                        : containerTo.setItemStackForSlot(slotTo, itemTo.withQuantity(newQuantity), filter);
                                return new MoveTransaction(true, fromTransaction, MoveType.MOVE_FROM_SELF, containerTo, addTransaction);
                            } else {
                                ItemStackSlotTransaction addTransaction = containerToSMC != null
                                        ? smcSetSlot(containerToSMC, slotTo, itemTo.withQuantity(maxStack), filter)
                                        : containerTo.setItemStackForSlot(slotTo, itemTo.withQuantity(maxStack), filter);
                                int remainder = newQuantity - maxStack;
                                int quantityLeft = !ItemStack.isEmpty(fromTransaction.getSlotAfter()) ? fromTransaction.getSlotAfter().getQuantity() : 0;
                                this.internal_setSlot(slot, itemFrom.withQuantity(remainder + quantityLeft));
                                return new MoveTransaction(true, fromTransaction, MoveType.MOVE_FROM_SELF, containerTo, addTransaction);
                            }
                        }
                    }
                }
            }
        };

        // Use ownWriteAction on both sides so neither container activates the
        // virtual view — we are already computing the correct effective max.
        // The instanceof branch is resolved here rather than inside a lambda because
        // writeAction is protected abstract in ItemContainer; calling it on a plain
        // ItemContainer reference inside a lambda triggers the same classloader
        // VerifyError as the other protected calls above.
        if (containerToSMC != null) {
            return (MoveTransaction) this.ownWriteAction(() -> containerToSMC.ownWriteAction(destLogic));
        } else {
            return (MoveTransaction) this.ownWriteAction(destLogic);
        }
    }

    /**
     * Sorts all moveable items in this container using the given {@link SortType} comparator,
     * consolidating partial stacks and packing items toward the front. Items whose slots
     * are blocked by a {@link FilterActionType#REMOVE} filter are left in place.
     *
     * @param sort the sort order to apply
     * @return a transaction listing each slot that changed
     */
    protected ListTransaction<SlotTransaction> internal_sortItems(@Nonnull SortType sort) {
        return (ListTransaction)this.ownWriteAction(() -> {
            ItemStack[] stacks = new ItemStack[this.getCapacity()];
            int stackOffset = 0;

            for(short i = 0; i < stacks.length; ++i) {
                if (!this.cantRemoveFromSlot(i)) {
                    ItemStack slot = this.internal_getSlot(i);
                    if (slot != null) {
                        Item item = slot.getItem();
                        int maxStack = getMaxStackForItem(item);
                        int slotQuantity = slot.getQuantity();
                        if (maxStack > 1) {
                            for(int j = 0; j < stackOffset && slotQuantity > 0; ++j) {
                                ItemStack stack = stacks[j];
                                if (slot.isStackableWith(stack)) {
                                    int stackQuantity = stack.getQuantity();
                                    if (stackQuantity < maxStack) {
                                        int adjust = Math.min(slotQuantity, maxStack - stackQuantity);
                                        slotQuantity -= adjust;
                                        stacks[j] = stack.withQuantity(stackQuantity + adjust);
                                    }
                                }
                            }
                        }

                        if (slotQuantity > 0) {
                            stacks[stackOffset++] = slotQuantity != slot.getQuantity() ? slot.withQuantity(slotQuantity) : slot;
                        }
                    }
                }
            }

            Arrays.sort(stacks, sort.getComparator());
            List<SlotTransaction> transactions = new ObjectArrayList(stacks.length);
            stackOffset = 0;

            for(short i = 0; i < stacks.length; ++i) {
                if (!this.cantRemoveFromSlot(i)) {
                    ItemStack existing = this.internal_getSlot(i);
                    ItemStack replacement = stackOffset < stacks.length ? stacks[stackOffset] : null;
                    if (replacement == null) {
                        // All packed items have been placed; clear any remaining occupied slots.
                        if (!ItemStack.isEmpty(existing)) {
                            this.internal_removeSlot(i);
                            transactions.add(new SlotTransaction(true, ActionType.REMOVE, i, existing, (ItemStack)null, (ItemStack)null, false, false, true));
                        }
                    } else if (!this.cantAddToSlot(i, replacement, existing)) {
                        ++stackOffset;
                        if (existing != replacement) {
                            this.internal_setSlot(i, replacement);
                            transactions.add(new SlotTransaction(true, ActionType.REMOVE, i, existing, (ItemStack)null, replacement, false, false, true));
                        }
                    }
                }
            }

            for(int i = stackOffset; i < stacks.length; ++i) {
                if (stacks[i] != null) {
                    throw new IllegalStateException("Had leftover stacks that didn't get sorted!");
                }
            }

            return new ListTransaction(true, transactions);
        });
    }

    // -------------------------------------------------------------------------
    // Max stack helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the effective maximum stack size for {@code item} in this container:
     * {@code item.getMaxStack() * stackMultiplier}, capped at {@link Integer#MAX_VALUE}.
     * Items with a base max-stack of 1 (non-stackable) are not multiplied.
     *
     * @param item the item to query
     * @return the effective max stack count
     */
    public int getMaxStackForItem(Item item) {
        int maxStack = item.getMaxStack();
        if (maxStack > 1) {
            maxStack = (int) Math.min((long) maxStack * stackMultiplier, Integer.MAX_VALUE);
        }
        return maxStack;
    }

    /**
     * Returns the effective max stack size for {@code item} inside {@code container}.
     * Delegates to {@link #getMaxStackForItem} when the container is a
     * {@link StackMultiplierContainer}; otherwise returns {@code item.getMaxStack()}.
     *
     * @param container the target container
     * @param item      the item to query
     * @return the effective max stack count for that container
     */
    private int getMaxStackForContainer(ItemContainer container, Item item) {

        if (container instanceof StackMultiplierContainer) {
            return ((StackMultiplierContainer) container).getMaxStackForItem(item);
        }
        return item.getMaxStack();
    }

    // -------------------------------------------------------------------------
    // Public add API — use ownWriteAction so virtual view stays off
    // -------------------------------------------------------------------------

    /**
     * Adds {@code itemStack} to this container, filling existing partial stacks
     * before empty slots (unless {@code fullStacks} is {@code true}).
     *
     * @param itemStack    the item to add
     * @param allOrNothing if {@code true}, the operation fails unless the full quantity fits
     * @param fullStacks   if {@code true}, only place into empty slots
     * @param filter       if {@code true}, apply slot and global filters
     * @return a transaction describing what was added and any remainder
     */
    @Override
    @Nonnull
    public ItemStackTransaction addItemStack(@Nonnull ItemStack itemStack, boolean allOrNothing, boolean fullStacks, boolean filter) {

        ItemStackTransaction transaction = internal_addItemStack(this, itemStack, allOrNothing, fullStacks, filter);

        this.sendUpdate(transaction);
        return transaction;
    }

    /**
     * Core add implementation. Runs under {@link #ownWriteAction} so the virtual view
     * stays off throughout. Uses the SMC-specific helper methods to bypass the
     * classloader-boundary issue with {@code InternalContainerUtilItemStack}.
     *
     * @param itemContainer the target container
     * @param itemStack     the item to add
     * @param allOrNothing  if {@code true}, fail unless the entire quantity fits
     * @param fullStacks    if {@code true}, only place into empty slots
     * @param filter        if {@code true}, apply slot and global filters
     * @return a transaction describing the result
     */
    protected static ItemStackTransaction internal_addItemStack(@Nonnull StackMultiplierContainer itemContainer, @Nonnull ItemStack itemStack, boolean allOrNothing, boolean fullStacks, boolean filter) {

        Item item = itemStack.getItem();
        if (item == null) {
            throw new IllegalArgumentException(itemStack.getItemId() + " is an invalid item!");
        } else {
            int itemMaxStack = itemContainer.getMaxStackForItem(item);
            return (ItemStackTransaction)itemContainer.ownWriteAction(() -> {
                if (allOrNothing) {
                    int testQuantityRemaining = itemStack.getQuantity();
                    if (!fullStacks) {
                        testQuantityRemaining = smcTestAddToExistingItemStacks(itemContainer, itemStack, itemMaxStack, testQuantityRemaining, filter);
                    }

                    testQuantityRemaining = smcTestAddToEmptySlots(itemContainer, itemStack, itemMaxStack, testQuantityRemaining, filter);
                    if (testQuantityRemaining > 0) {
                        return new ItemStackTransaction(false, ActionType.ADD, itemStack, itemStack, allOrNothing, filter, Collections.emptyList());
                    }
                }

                List<ItemStackSlotTransaction> list = new ObjectArrayList();
                ItemStack remaining = itemStack;
                if (!fullStacks) {
                    for(short i = 0; i < itemContainer.getCapacity() && !ItemStack.isEmpty(remaining); ++i) {
                        ItemStackSlotTransaction transaction = smcAddToExistingSlot(itemContainer, i, remaining, itemMaxStack, filter);
                        list.add(transaction);
                        remaining = transaction.getRemainder();
                    }
                }

                for(short i = 0; i < itemContainer.getCapacity() && !ItemStack.isEmpty(remaining); ++i) {
                    ItemStackSlotTransaction transaction = smcAddToEmptySlot(itemContainer, i, remaining, itemMaxStack, filter);
                    list.add(transaction);
                    remaining = transaction.getRemainder();
                }

                return new ItemStackTransaction(true, ActionType.ADD, itemStack, remaining, allOrNothing, filter, list);
            });
        }
    }

    /**
     * Convenience overload: adds {@code itemStack} with {@code allOrNothing=false},
     * {@code fullStacks=false}, and {@code filter=true}.
     *
     * @param itemStack the item to add
     * @return a transaction describing what was added and any remainder
     */
    @Override
    @Nonnull
    public ItemStackTransaction addItemStack(@Nonnull ItemStack itemStack) {

        return this.addItemStack(itemStack, false, false, true);
    }

    /**
     * Adds {@code itemStack} to the specific {@code slot}, respecting this container's
     * effective max stack. The slot must already hold a compatible item or be empty.
     *
     * @param slot         the target slot index
     * @param itemStack    the item to add
     * @param allOrNothing if {@code true}, the operation fails unless the full quantity fits
     * @param filter       if {@code true}, apply slot and global filters
     * @return a transaction describing the result
     */
    @Override
    @Nonnull
    public ItemStackSlotTransaction addItemStackToSlot(short slot, @Nonnull ItemStack itemStack, boolean allOrNothing, boolean filter) {

        validateSlotIndex(slot, this.getCapacity());

        ItemStackSlotTransaction transaction = (ItemStackSlotTransaction) this.ownWriteAction(() -> {
            int quantityRemaining = itemStack.getQuantity();
            ItemStack slotItemStack = this.internal_getSlot(slot);

            if (filter && this.cantAddToSlot(slot, itemStack, slotItemStack)) {
                return new ItemStackSlotTransaction(false, ActionType.ADD, slot, slotItemStack, slotItemStack,
                        itemStack, true, allOrNothing, false, filter, itemStack, itemStack);
            }

            if (slotItemStack == null || ItemStack.isEmpty(slotItemStack)) {
                int maxStack = getMaxStackForItem(itemStack.getItem());
                int toAdd = Math.min(quantityRemaining, maxStack);
                ItemStack newStack = itemStack.withQuantity(toAdd);
                this.internal_setSlot(slot, newStack);

                ItemStack remainder = quantityRemaining > toAdd ? itemStack.withQuantity(quantityRemaining - toAdd) : null;
                return new ItemStackSlotTransaction(true, ActionType.ADD, slot, null, newStack,
                        itemStack, true, allOrNothing, false, filter, newStack, remainder);
            }

            if (!itemStack.isStackableWith(slotItemStack)) {
                return new ItemStackSlotTransaction(false, ActionType.ADD, slot, slotItemStack, slotItemStack,
                        itemStack, true, allOrNothing, false, filter, itemStack, itemStack);
            }

            int maxStack = getMaxStackForItem(itemStack.getItem());
            int currentQuantity = slotItemStack.getQuantity();
            int spaceAvailable = maxStack - currentQuantity;
            int toAdd = Math.min(quantityRemaining, spaceAvailable);

            if (toAdd <= 0) {
                return new ItemStackSlotTransaction(false, ActionType.ADD, slot, slotItemStack, slotItemStack,
                        itemStack, true, allOrNothing, false, filter, itemStack, itemStack);
            }

            ItemStack newStack = slotItemStack.withQuantity(currentQuantity + toAdd);
            this.internal_setSlot(slot, newStack);

            ItemStack remainder = quantityRemaining > toAdd ? itemStack.withQuantity(quantityRemaining - toAdd) : null;
            boolean succeeded = !allOrNothing || remainder == null || ItemStack.isEmpty(remainder);

            return new ItemStackSlotTransaction(succeeded, ActionType.ADD, slot, slotItemStack, newStack,
                    itemStack, true, allOrNothing, false, filter, newStack, remainder);
        });

        this.sendUpdate(transaction);
        return transaction;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode
    // -------------------------------------------------------------------------

    /**
     * Two {@link StackMultiplierContainer} instances are equal when they have the
     * same {@code capacity}, {@code stackMultiplier}, and identical slot contents.
     *
     * @param o the object to compare
     * @return {@code true} if the containers are equal
     */
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (o instanceof StackMultiplierContainer) {
            StackMultiplierContainer that = (StackMultiplierContainer)o;
            if (this.capacity != that.capacity || this.stackMultiplier != that.stackMultiplier) {
                return false;
            } else {
                this.lock.readLock().lock();

                boolean var3;
                try {
                    var3 = this.items.equals(that.items);
                } finally {
                    this.lock.readLock().unlock();
                }

                return var3;
            }
        } else {
            return false;
        }
    }

    /** {@inheritDoc} */
    public int hashCode() {
        this.lock.readLock().lock();

        int result;
        try {
            result = this.items.hashCode();
        } finally {
            this.lock.readLock().unlock();
        }

        result = 31 * result + this.capacity * 233 + this.stackMultiplier;
        return result;
    }

    // -------------------------------------------------------------------------
    // Static utility helpers
    // -------------------------------------------------------------------------

    /**
     * Attempts to add {@code itemStack} to {@code itemContainer}. Any remainder
     * that did not fit is dropped at the entity's location.
     *
     * @param store         the entity store component accessor
     * @param ref           the entity reference used for dropping
     * @param itemContainer the target container
     * @param itemStack     the item to add
     * @return {@code true} if any item was dropped
     */
    public static boolean addOrDropItemStack(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull ItemContainer itemContainer, @Nonnull ItemStack itemStack) {

        ItemStackTransaction transaction = itemContainer.addItemStack(itemStack);
        ItemStack remainder = transaction.getRemainder();
        if (!ItemStack.isEmpty(remainder)) {
            ItemUtils.dropItem(ref, remainder, store);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Attempts to add {@code itemStack} to the specific {@code slot} in
     * {@code itemContainer}. If the slot cannot accept the full stack, the
     * remainder is added to any other available slot (or dropped if no space remains).
     *
     * @param store         the entity store component accessor
     * @param ref           the entity reference used for dropping
     * @param itemContainer the target container
     * @param slot          the preferred slot index
     * @param itemStack     the item to add
     * @return {@code true} if any item was dropped
     */
    public static boolean addOrDropItemStack(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull ItemContainer itemContainer, short slot, @Nonnull ItemStack itemStack) {

        ItemStackSlotTransaction transaction = itemContainer.addItemStackToSlot(slot, itemStack);
        ItemStack remainder = transaction.getRemainder();
        return !ItemStack.isEmpty(remainder) ? addOrDropItemStack(store, ref, itemContainer, itemStack) : false;
    }

    /**
     * Attempts to add each stack in {@code itemStacks} to {@code itemContainer}.
     * Any remainder from each stack is dropped at the entity's location.
     *
     * @param store         the entity store component accessor
     * @param ref           the entity reference used for dropping
     * @param itemContainer the target container
     * @param itemStacks    the items to add
     * @return {@code true} if at least one item was dropped
     */
    public static boolean addOrDropItemStacks(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull ItemContainer itemContainer, List<ItemStack> itemStacks) {

        ListTransaction<ItemStackTransaction> transaction = itemContainer.addItemStacks(itemStacks);
        boolean droppedItem = false;

        for(ItemStackTransaction stackTransaction : transaction.getList()) {
            ItemStack remainder = stackTransaction.getRemainder();
            if (!ItemStack.isEmpty(remainder)) {
                ItemUtils.dropItem(ref, remainder, store);
                droppedItem = true;
            }
        }

        return droppedItem;
    }

    /**
     * Attempts to add {@code itemStacks} to {@code itemContainer} using the ordered
     * (slot-by-slot) strategy. Any stacks that do not fit are added unordered; those
     * that still cannot fit are dropped at the entity's location.
     *
     * @param store         the entity store component accessor
     * @param ref           the entity reference used for dropping
     * @param itemContainer the target container
     * @param itemStacks    the items to add in order
     * @return {@code true} if at least one item was dropped
     */
    public static boolean tryAddOrderedOrDropItemStacks(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull ItemContainer itemContainer, List<ItemStack> itemStacks) {

        ListTransaction<ItemStackSlotTransaction> transaction = itemContainer.addItemStacksOrdered(itemStacks);
        List<ItemStack> remainderItemStacks = null;

        for(ItemStackSlotTransaction stackTransaction : transaction.getList()) {
            ItemStack remainder = stackTransaction.getRemainder();
            if (!ItemStack.isEmpty(remainder)) {
                if (remainderItemStacks == null) {
                    remainderItemStacks = new ObjectArrayList();
                }

                remainderItemStacks.add(remainder);
            }
        }

        return addOrDropItemStacks(store, ref, itemContainer, remainderItemStacks);
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    static {
        BuilderCodec.Builder<StackMultiplierContainer> builder =
                BuilderCodec.builder(StackMultiplierContainer.class, StackMultiplierContainer::new);

        CODEC = builder
                .append(
                        new KeyedCodec<>("Capacity", Codec.SHORT),
                        (StackMultiplierContainer o, Short i) -> o.capacity = i,
                        (StackMultiplierContainer o) -> o.capacity
                )
                .addValidator(Validators.greaterThanOrEqual((short) 0))
                .add()
                .append(
                        new KeyedCodec<>("StackMultiplier", Codec.INTEGER),
                        (StackMultiplierContainer o, Integer i) -> o.stackMultiplier = i,
                        (StackMultiplierContainer o) -> o.stackMultiplier
                )
                .addValidator(Validators.greaterThan((int) 0))
                .add()
                .append(
                        new KeyedCodec<>("Items",
                                new Short2ObjectMapCodec<>(
                                        ItemStack.CODEC,
                                        Short2ObjectOpenHashMap::new,
                                        false
                                )
                        ),
                        (StackMultiplierContainer o, Short2ObjectMap<ItemStack> i) -> o.items = i,
                        (StackMultiplierContainer o) -> o.items
                )
                .add()
                .afterDecode((StackMultiplierContainer i) -> {
                    if (i.items == null) {
                        i.items = new Short2ObjectOpenHashMap<>(i.capacity);
                    }

                    i.items.short2ObjectEntrySet().removeIf(e ->
                            e.getShortKey() < 0
                                    || e.getShortKey() >= i.capacity
                                    || ItemStack.isEmpty(e.getValue())
                    );
                })
                .build();
    }
}