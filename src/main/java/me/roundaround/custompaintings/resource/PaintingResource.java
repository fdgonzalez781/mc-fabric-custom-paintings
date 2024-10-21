package me.roundaround.custompaintings.resource;

import me.roundaround.custompaintings.entity.decoration.painting.PaintingData;
import net.minecraft.util.Identifier;

public record PaintingResource(String id, String name, String artist, Integer height, Integer width) {
  public PaintingData toData(String packId) {
    return new PaintingData(new Identifier(packId, this.id()), this.width(), this.height(), this.name(), this.artist());
  }
}
