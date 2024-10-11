package me.roundaround.custompaintings.resource.legacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.roundaround.custompaintings.CustomPaintingsMod;
import me.roundaround.custompaintings.client.gui.screen.ConvertPromptScreen;
import me.roundaround.custompaintings.resource.Image;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.*;
import net.minecraft.resource.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Collectors;

public class LegacyPaintingPackChecker extends SinglePreparationResourceReloader<LegacyPaintingPackChecker.LoadResult> implements
    IdentifiableResourceReloadListener {
  private static final Gson GSON = new GsonBuilder().create();
  private static final int MAX_SIZE = 1 << 24;

  @Override
  public Identifier getFabricId() {
    return new Identifier(CustomPaintingsMod.MOD_ID, "custom_paintings");
  }

  @Override
  protected LoadResult prepare(ResourceManager manager, Profiler profiler) {
    CustomPaintingsMod.LOGGER.info("Loading legacy painting resource packs...");

    Path ignoredPacksDatFile = FabricLoader.getInstance()
        .getGameDir()
        .resolve("data")
        .resolve(CustomPaintingsMod.MOD_ID)
        .resolve("legacy_ignored.dat");
    HashSet<String> ignoredPacks = readIgnoredPacks(ignoredPacksDatFile);

    HashMap<String, LegacyPackWrapper> packs = new HashMap<>();
    HashMap<Identifier, Image> images = new HashMap<>();

    manager.streamResourcePacks()
        .filter(
            (resourcePack) -> resourcePack instanceof ZipResourcePack || resourcePack instanceof DirectoryResourcePack)
        .filter((resourcePack) -> resourcePack.getNamespaces(ResourceType.CLIENT_RESOURCES)
            .stream()
            .anyMatch((namespace) -> !Identifier.DEFAULT_NAMESPACE.equals(namespace) &&
                                     !Identifier.REALMS_NAMESPACE.equals(namespace)))
        .forEach(((resourcePack) -> {
          LegacyPackResource pack;
          InputSupplier<InputStream> streamSupplier = resourcePack.openRoot("custompaintings.json");

          if (streamSupplier == null) {
            // Not a custom paintings pack, or failed to open json file for reading
            return;
          }

          try (InputStream stream = streamSupplier.get()) {
            pack = GSON.fromJson(new InputStreamReader(stream), LegacyPackResource.class);

            if (ignoredPacks.contains(pack.id())) {
              // Already acknowledged; skip
              return;
            }
            if (packs.containsKey(pack.id())) {
              // Skip duplicates
              return;
            }

            packs.put(pack.id(), new LegacyPackWrapper(resourcePack.getInfo().title().getString(), pack));

            HashMap<Identifier, Identifier> imageIdToPath = new HashMap<>();

            resourcePack.getNamespaces(ResourceType.CLIENT_RESOURCES)
                .stream()
                .filter((namespace) -> !Identifier.DEFAULT_NAMESPACE.equals(namespace) &&
                                       !Identifier.REALMS_NAMESPACE.equals(namespace))
                .forEach((namespace) -> resourcePack.findResources(ResourceType.CLIENT_RESOURCES, namespace,
                    "textures/painting", (id, supplier) -> {
                      if (!id.getPath().endsWith(".png")) {
                        return;
                      }

                      String path = id.getPath();
                      String paintingId = path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.'));

                      Identifier idInNewPack = new Identifier(id.getNamespace(), paintingId);
                      imageIdToPath.put(idInNewPack, id);
                    }
                ));

            pack.paintings().forEach((painting) -> {
              Identifier id = new Identifier(pack.id(), painting.id());
              if (!imageIdToPath.containsKey(id)) {
                return;
              }

              InputSupplier<InputStream> imageStreamSupplier = resourcePack.open(
                  ResourceType.CLIENT_RESOURCES, imageIdToPath.get(id));
              if (imageStreamSupplier == null) {
                return;
              }

              try (InputStream imageInputStream = imageStreamSupplier.get()) {
                BufferedImage image = ImageIO.read(imageInputStream);
                if (image == null) {
                  return;
                }

                long size = (long) image.getWidth() * image.getHeight();
                if (size > MAX_SIZE) {
                  // Image too big for new format
                  return;
                }

                images.put(id, Image.read(image));
              } catch (Exception e) {
                // Silently ignore image
              }
            });
          } catch (Exception e) {
            CustomPaintingsMod.LOGGER.error("Error reading custom painting pack, skipping...", e);
          }
        }));

    return new LoadResult(packs, images);
  }

  @Override
  protected void apply(LoadResult prepared, ResourceManager manager, Profiler profiler) {
    MinecraftClient client = MinecraftClient.getInstance();
    client.setScreen(new ConvertPromptScreen(client.currentScreen, prepared.packs, prepared.images));
  }

  private static HashSet<String> readIgnoredPacks(Path ignoredPacksDatFile) {
    if (Files.notExists(ignoredPacksDatFile)) {
      return new HashSet<>(0);
    }

    try {
      NbtCompound data = NbtIo.readCompressed(ignoredPacksDatFile, NbtSizeTracker.ofUnlimitedBytes());
      NbtList values = data.getList("values", NbtElement.STRING_TYPE);
      return values.stream().map(NbtElement::asString).collect(Collectors.toCollection(HashSet::new));
    } catch (IOException e) {
      CustomPaintingsMod.LOGGER.warn("Could not read {}", ignoredPacksDatFile.toAbsolutePath(), e);
    }

    return new HashSet<>(0);
  }

  public record LoadResult(HashMap<String, LegacyPackWrapper> packs, HashMap<Identifier, Image> images) {
  }
}
