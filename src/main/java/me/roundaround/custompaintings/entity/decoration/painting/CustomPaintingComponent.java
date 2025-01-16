package me.roundaround.custompaintings.entity.decoration.painting;

import me.roundaround.custompaintings.util.CustomId;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper.WrapperLookup;

public class CustomPaintingComponent implements PaintingDataComponent {
	private final PaintingEntity painting;
	private PaintingData value;
	
	public CustomPaintingComponent(PaintingEntity painting) {
		this.painting = painting;
		this.value = PaintingData.EMPTY;
	}
	
	@Override
	public void readFromNbt(NbtCompound tag, WrapperLookup registryLookup) {
		this.value = PaintingData.read(tag);
	}

	@Override
	public void writeToNbt(NbtCompound tag, WrapperLookup registryLookup) {
		// TODO Auto-generated method stub
	    if (this.value.isEmpty()) {
	        return;
	    }

	    tag.putString("id", this.value.id().toString());
	    tag.putInt("width", this.value.width());
	    tag.putInt("height", this.value.height());
	    tag.putString("name", this.value.name() == null ? "" : this.value.name());
	    tag.putString("artist", this.value.artist() == null ? "" : this.value.artist());
	    tag.putBoolean("vanilla", this.value.vanilla());
	}

	@Override
	public PaintingData getValue() {
		// TODO Auto-generated method stub
		return this.value;
	}

}
