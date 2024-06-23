package me.roundaround.custompaintings.client.gui;

import me.roundaround.custompaintings.client.CustomPaintingManager;
import me.roundaround.custompaintings.client.CustomPaintingsClientMod;
import me.roundaround.custompaintings.entity.decoration.painting.PaintingData;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.decoration.painting.PaintingVariant;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.PaintingVariantTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

public class PaintingEditState {
  private final MinecraftClient client;
  private final HashMap<String, Group> allPaintings = new HashMap<>();
  private final HashMap<String, Boolean> canStayHashMap = new HashMap<>();
  private final UUID paintingUuid;
  private final int paintingId;
  private final BlockPos blockPos;
  private final Direction facing;
  private final FiltersState filtersState;

  private Group currentGroup = null;
  private PaintingData currentPainting = null;

  private static final Predicate<Entity> DECORATION_PREDICATE = (
      entity
  ) -> entity instanceof AbstractDecorationEntity;

  public PaintingEditState(
      MinecraftClient client, UUID paintingUuid, int paintingId, BlockPos blockPos, Direction facing
  ) {
    this.client = client;
    this.paintingUuid = paintingUuid;
    this.paintingId = paintingId;
    this.blockPos = blockPos;
    this.facing = facing;

    this.filtersState = new FiltersState(this::canStay);
    this.populatePaintings();
  }

  public FiltersState getFilters() {
    return this.filtersState;
  }

  public Collection<Group> getGroups() {
    return this.allPaintings.values();
  }

  public boolean hasMultipleGroups() {
    return this.allPaintings.size() > 1;
  }

  public boolean hasMultiplePaintings() {
    if (this.currentGroup == null) {
      return false;
    }
    return this.currentGroup.paintings().size() > 1;
  }

  public boolean hasNoPaintings() {
    return this.allPaintings.isEmpty();
  }

  public boolean hasGroup(String id) {
    return this.allPaintings.containsKey(id) && !this.allPaintings.get(id).paintings().isEmpty();
  }

  public UUID getPaintingUuid() {
    return this.paintingUuid;
  }

  public Group getCurrentGroup() {
    return this.currentGroup;
  }

  public PaintingData getCurrentPainting() {
    return this.currentPainting != null ? this.currentPainting : PaintingData.EMPTY;
  }

  public void selectFirstGroup() {
    this.currentGroup = this.allPaintings.values().iterator().next();
  }

  public void setCurrentGroup(String id) {
    if (!this.hasGroup(id)) {
      return;
    }
    this.setCurrentGroup(this.allPaintings.get(id));
  }

  public void setCurrentGroup(Group group) {
    this.currentGroup = group;
    if (group != null && !group.paintings().isEmpty()) {
      this.setCurrentPainting(group.paintings().getFirst());
    } else {
      this.setCurrentPainting((PaintingData) null);
    }
  }

  public void clearGroup() {
    this.setCurrentGroup((Group) null);
  }

  public void setCurrentPainting(PaintingData painting) {
    this.currentPainting = painting;
    if (this.client.currentScreen instanceof PaintingChangeListener) {
      ((PaintingChangeListener) this.client.currentScreen).onPaintingChange(painting);
    }
  }

  public void setCurrentPainting(Function<PaintingData, PaintingData> mapper) {
    this.setCurrentPainting(mapper.apply(this.currentPainting));
  }

  public void populatePaintings() {
    if (!this.allPaintings.isEmpty()) {
      return;
    }

    Registries.PAINTING_VARIANT.stream().forEach((vanillaVariant) -> {
      Identifier id = Registries.PAINTING_VARIANT.getId(vanillaVariant);
      RegistryKey<PaintingVariant> key = RegistryKey.of(Registries.PAINTING_VARIANT.getKey(), id);
      Optional<RegistryEntry.Reference<PaintingVariant>> maybeEntry = Registries.PAINTING_VARIANT.getEntry(key);

      if (maybeEntry.isEmpty()) {
        return;
      }

      RegistryEntry<PaintingVariant> entry = maybeEntry.get();
      boolean placeable = entry.isIn(PaintingVariantTags.PLACEABLE);
      String groupId = id.getNamespace() + (placeable ? "" : "_unplaceable");

      if (!this.allPaintings.containsKey(groupId)) {
        String groupName = !placeable ?
            "Minecraft: The Hidden Ones" :
            FabricLoader.getInstance()
                .getModContainer(groupId)
                .map((mod) -> mod.getMetadata().getName())
                .orElse(groupId);
        this.allPaintings.put(groupId, new Group(groupId, groupName, new ArrayList<>()));
      }

      this.allPaintings.get(groupId)
          .paintings()
          .add(new PaintingData(vanillaVariant, this.allPaintings.get(groupId).paintings().size()));
    });

    CustomPaintingManager paintingManager = CustomPaintingsClientMod.customPaintingManager;
    paintingManager.getPacks().forEach((pack) -> {
      String groupId = pack.id();
      String groupName = pack.name();

      if (!this.allPaintings.containsKey(groupId)) {
        this.allPaintings.put(groupId, new Group(groupId, groupName, new ArrayList<>()));
      }

      pack.paintings().forEach((painting) -> {
        this.allPaintings.get(groupId)
            .paintings()
            .add(
                new PaintingData(new Identifier(pack.id(), painting.id()), painting.index(), painting.width().orElse(1),
                    painting.height().orElse(1), painting.name().orElse(""), painting.artist().orElse("")
                ));
      });
    });

    this.allPaintings.values().forEach((group) -> {
      group.paintings().forEach((paintingData) -> {
        String sizeString = paintingData.width() + "x" + paintingData.height();
        if (!this.canStayHashMap.containsKey(sizeString)) {
          this.canStayHashMap.put(sizeString, this.canStay(paintingData));
        }
      });
    });
  }

  public boolean canStay() {
    return this.canStay(this.getCurrentPainting());
  }

  public boolean canStay(PaintingData paintingData) {
    String sizeString = paintingData.width() + "x" + paintingData.height();
    if (this.canStayHashMap.containsKey(sizeString)) {
      return this.canStayHashMap.get(sizeString);
    }
    boolean result = this.canStay(paintingData.getScaledWidth(), paintingData.getScaledHeight());
    this.canStayHashMap.put(sizeString, result);
    return result;
  }

  @SuppressWarnings("deprecation")
  public boolean canStay(int width, int height) {
    World world = Objects.requireNonNull(this.client.player).getWorld();
    Box boundingBox = this.getBoundingBox(width, height);

    if (!world.isSpaceEmpty(boundingBox)) {
      return false;
    }

    int blocksWidth = Math.max(1, width / 16);
    int blocksHeight = Math.max(1, height / 16);
    BlockPos pos = this.blockPos.offset(this.facing.getOpposite());
    Direction direction = this.facing.rotateYCounterclockwise();
    BlockPos.Mutable mutable = new BlockPos.Mutable();

    for (int x = 0; x < blocksWidth; x++) {
      for (int z = 0; z < blocksHeight; z++) {
        mutable.set(pos).move(direction, x - (blocksWidth - 1) / 2).move(Direction.UP, z - (blocksHeight - 1) / 2);
        BlockState blockState = world.getBlockState(mutable);

        if (!blockState.isSolid() && !AbstractRedstoneGateBlock.isRedstoneGate(blockState)) {
          return false;
        }
      }
    }

    Entity entity = world.getEntityById(this.paintingId);
    PaintingEntity currentPainting = entity instanceof PaintingEntity ? (PaintingEntity) entity : null;

    return world.getOtherEntities(currentPainting, boundingBox, DECORATION_PREDICATE).isEmpty();
  }

  private Box getBoundingBox(int width, int height) {
    double posX = this.blockPos.getX() + 0.5 - this.facing.getOffsetX() * 0.46875 +
        this.facing.rotateYCounterclockwise().getOffsetX() * this.offsetForEven(width);
    double posY = this.blockPos.getY() + 0.5 + this.offsetForEven(height);
    double posZ = this.blockPos.getZ() + 0.5 - this.facing.getOffsetZ() * 0.46875 +
        this.facing.rotateYCounterclockwise().getOffsetZ() * this.offsetForEven(width);

    double sizeX = (this.facing.getAxis() == Direction.Axis.Z ? width : 1) / 32D;
    double sizeY = height / 32D;
    double sizeZ = (this.facing.getAxis() == Direction.Axis.Z ? 1 : width) / 32D;

    return new Box(posX - sizeX, posY - sizeY, posZ - sizeZ, posX + sizeX, posY + sizeY, posZ + sizeZ);
  }

  private double offsetForEven(int size) {
    return size % 32 == 0 ? 0.5 : 0;
  }

  public record Group(String id, String name, ArrayList<PaintingData> paintings) {
  }

  @FunctionalInterface
  public interface PaintingChangeListener {
    void onPaintingChange(PaintingData paintingData);
  }
}
