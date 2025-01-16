package me.roundaround.custompaintings;

import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;

import me.roundaround.custompaintings.entity.decoration.painting.CustomPaintingComponent;
import me.roundaround.custompaintings.entity.decoration.painting.PaintingDataComponent;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.util.Identifier;

public class CustomPaintingComponents implements EntityComponentInitializer {
	  public static final ComponentKey<PaintingDataComponent> PAINTING_DATA = 
			  ComponentRegistry.getOrCreate(Identifier.of(CustomPaintingsMod.MOD_ID, "painting_data"), PaintingDataComponent.class);
	
	@Override
	public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
		registry.registerFor(PaintingEntity.class, PAINTING_DATA, CustomPaintingComponent::new);
	}
}
