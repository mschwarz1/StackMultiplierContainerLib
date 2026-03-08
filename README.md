# StackMultiplierContainer

A Hytale server plugin library that provides expandable, modifiable inventory containers with configurable stack size multipliers. This plugin is intended as a **foundation** — it registers the `StackMultiplierContainer` type with the server, and other plugins build on top of it to add items, UI, or gameplay functionality.

## What It Does

Hytale's built-in `ItemContainer` enforces hardcoded max stack sizes per item. `StackMultiplierContainer` extends `ItemContainer` to allow slots to hold **N times** the normal stack limit. For example, a container with a `stackMultiplier` of 4 lets each slot hold up to 4x the default max stack for any item (non-stackable items are unaffected).

It accomplishes this transparently using a virtual-view pattern so that the base Hytale inventory system continues to function correctly without modification.

### Key Features

- **Configurable stack multiplier** — set per-container at construction time
- **Slot and global filters** — control which items can be added, removed, or dropped per slot or across the entire container
- **Thread-safe** — all operations are protected by read/write locks
- **Serializable** — registered with Hytale's codec system under `"stack_multiplier_container"` for automatic save/load
- **Drop-safe utilities** — static helper methods to add items and drop overflow at the entity's location

## Requirements

- **Hytale Server** installed locally (the build resolves `HytaleServer.jar` from your Hytale installation)
- **Java 25+**
- **Gradle 9+**

## Building

1. Ensure Hytale is installed (default path: `%APPDATA%/Hytale`)
2. Clone this repository
3. Build:

```bash
./gradlew build
```

The build automatically:
- Resolves `HytaleServer.jar` from your local Hytale installation
- Extracts the server version from the JAR's manifest
- Updates `manifest.json` with the current build version and server version

The output JAR is located in `build/libs/`.

### Configuration

Build properties are in `gradle.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `java_version` | `25` | Java toolchain version |
| `patchline` | `release` | Hytale install channel (`release`, etc.) |

## Installation

Place the built JAR in your Hytale server's plugin directory.

## Usage From Other Plugins

### Creating a Container

```java
// 20 slots, each holding 4x the normal stack size
StackMultiplierContainer container = new StackMultiplierContainer((short) 20, (short) 4);
```

### Adding Items

```java
// Add with default behavior (partial fills allowed, filters applied)
ItemStackTransaction tx = container.addItemStack(itemStack);

// Add with full control
ItemStackTransaction tx = container.addItemStack(itemStack, allOrNothing, fullStacks, filter);

// Add to a specific slot
ItemStackSlotTransaction tx = container.addItemStackToSlot(slot, itemStack, allOrNothing, filter);
```

### Add-or-Drop Helpers

Static utility methods that add items to a container and drop any overflow at the entity's location:

```java
// Single item
boolean dropped = StackMultiplierContainer.addOrDropItemStack(store, ref, container, itemStack);

// Single item, try specific slot first
boolean dropped = StackMultiplierContainer.addOrDropItemStack(store, ref, container, slot, itemStack);

// Multiple items
boolean dropped = StackMultiplierContainer.addOrDropItemStacks(store, ref, container, itemStacks);
```

### Filters

```java
// Set a global filter for the entire container
container.setGlobalFilter(FilterType.ALLOW_ALL);

// Set a per-slot filter for a specific action type (ADD, REMOVE, DROP)
container.setSlotFilter(FilterActionType.ADD, slot, mySlotFilter);

// Remove a slot filter by passing null
container.setSlotFilter(FilterActionType.ADD, slot, null);
```

### Querying

```java
// Check effective max stack for an item (respects multiplier)
int maxStack = container.getMaxStackForItem(item);

// Check if an item can be added
boolean canAdd = container.canAddItemStack(itemStack, fullStacks, filter);

// Get the item at a slot
ItemStack stack = container.getItemStack(slot);
```

## Project Structure

```
├── src/main/java/
│   └── com/msgames/plugin/stackmultipliercontainer/
│       ├── StackMultiplierContainerPlugin.java   # Plugin entry point, codec registration
│       └── container/
│           └── StackMultiplierContainer.java      # Core container implementation
├── src/main/resources/
│   └── manifest.json                              # Hytale plugin manifest (auto-updated by build)
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## License

See [LICENSE](LICENSE) for details.