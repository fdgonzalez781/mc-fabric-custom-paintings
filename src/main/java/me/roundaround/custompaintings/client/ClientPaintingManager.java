package me.roundaround.custompaintings.client;

import me.roundaround.custompaintings.client.registry.ClientPaintingRegistry;
import me.roundaround.custompaintings.entity.decoration.painting.PaintingData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.HashMap;

public class ClientPaintingManager implements AutoCloseable {
  private static ClientPaintingManager instance = null;

  public static ClientPaintingManager getInstance() {
    if (instance == null) {
      instance = new ClientPaintingManager();
    }
    return instance;
  }

  private final HashMap<Integer, PaintingData> cachedData = new HashMap<>();

  private ClientPaintingManager() {
    ClientEntityEvents.ENTITY_LOAD.register(((entity, world) -> {
      if (!(entity instanceof PaintingEntity painting)) {
        return;
      }

      PaintingData paintingData = this.cachedData.get(painting.getId());
      if (paintingData != null) {
        this.setPaintingData(painting, paintingData);
      }
    }));
  }

  public void trySetPaintingData(World world, int paintingId, Identifier dataId) {
    PaintingData paintingData = ClientPaintingRegistry.getInstance().get(dataId);
    if (paintingData == null || paintingData.isEmpty()) {
      return;
    }

    Entity entity = world.getEntityById(paintingId);
    if (!(entity instanceof PaintingEntity painting)) {
      this.cachedData.put(paintingId, paintingData);
      return;
    }

    this.setPaintingData(painting, paintingData);
  }

  @Override
  public void close() {
    this.cachedData.clear();
  }

  private void setPaintingData(PaintingEntity painting, PaintingData paintingData) {
    if (paintingData.isVanilla()) {
      painting.setVariant(paintingData.id());
    }
    painting.setCustomData(paintingData);
    this.cachedData.remove(painting.getId());
  }
}