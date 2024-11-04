package me.roundaround.custompaintings.server.registry;

import me.roundaround.custompaintings.CustomPaintingsMod;
import me.roundaround.custompaintings.entity.decoration.painting.PackData;
import me.roundaround.custompaintings.registry.CustomPaintingRegistry;
import me.roundaround.custompaintings.resource.*;
import me.roundaround.custompaintings.server.network.ImagePacketQueue;
import me.roundaround.custompaintings.server.network.ServerNetworking;
import me.roundaround.roundalib.util.PathAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ServerPaintingRegistry extends CustomPaintingRegistry {
  private static final String META_FILENAME = "custompaintings.json";
  private static final String PACK_PNG = "pack.png";
  private static final String ICON_PNG = "icon.png";
  private static final String LOG_NO_META = "Found Custom Paintings pack \"{}\" without a {} file, skipping...";
  private static final String LOG_META_PARSE_FAIL = "Failed to parse {} from \"{}\", skipping...";
  private static final String LOG_NO_PAINTINGS = "No paintings found in \"{}\", skipping...";
  private static final String LOG_NO_ICON = "Missing icon.png file for {}";
  private static final String LOG_LEGACY_ICON = "Deprecated/legacy pack.png file found for {}. Rename to icon.png";
  private static final String LOG_ICON_READ_FAIL = "Failed to read icon.png file for {}";
  private static final String LOG_MISSING_PAINTING = "Missing custom painting image file for {}";
  private static final String LOG_LARGE_IMAGE = "Image file for {} is too large, skipping";
  private static final String LOG_PAINTING_READ_FAIL = "Failed to read custom painting image file for {}";
  private static final int MAX_SIZE = 1 << 24;

  private static ServerPaintingRegistry instance = null;

  private final HashMap<Identifier, Boolean> finishedMigrations = new HashMap<>();

  private MinecraftServer server;
  private boolean safeMode = false;

  private ServerPaintingRegistry() {
    ServerLifecycleEvents.SERVER_STOPPED.register((server) -> {
      if (server == this.server) {
        this.clear();
      }
    });
  }

  public static void init(MinecraftServer server) {
    ServerPaintingRegistry registry = getInstance();
    if (server == null || !server.isRunning()) {
      return;
    }

    registry.setServer(server);
  }

  public static ServerPaintingRegistry getInstance() {
    if (instance == null) {
      instance = new ServerPaintingRegistry();
    }
    return instance;
  }

  @Override
  public void clear() {
    super.clear();
    this.finishedMigrations.clear();
    this.server = null;
  }

  public void setServer(MinecraftServer server) {
    if (this.server != null && this.server != server) {
      this.clear();
    }
    this.server = server;
    this.sendSummaryToAll();
  }

  public void firstLoadPaintingPacks(boolean safeMode) {
    this.safeMode = safeMode;
    if (this.safeMode) {
      return;
    }
    LoadResult loadResult = this.loadPaintingPacks();
    this.setPacks(loadResult.packs());
    this.setImages(loadResult.images());
  }

  public void reloadPaintingPacks(Consumer<MinecraftServer> onSucceed) {
    if (this.server == null || !this.server.isRunning()) {
      return;
    }

    this.safeMode = false;
    CompletableFuture.supplyAsync(this::loadPaintingPacks, Util.getIoWorkerExecutor()).thenAcceptAsync((loadResult) -> {
      this.setPacks(loadResult.packs());
      this.setImages(loadResult.images());
      this.sendSummaryToAll();
      onSucceed.accept(this.server);
    }, this.server);
  }

  public void sendSummaryToAll() {
    ServerNetworking.sendSummaryPacketToAll(
        this.server, this.packsList, this.combinedImageHash, this.finishedMigrations, this.safeMode);
  }

  public void sendSummaryToPlayer(ServerPlayerEntity player) {
    ServerNetworking.sendSummaryPacket(
        player, this.packsList, this.combinedImageHash, this.finishedMigrations, this.safeMode);
  }

  public void checkPlayerHashes(ServerPlayerEntity player, Map<Identifier, String> hashes) {
    HashMap<Identifier, Image> images = new HashMap<>();
    this.imageHashes.forEach((id, hash) -> {
      if (hash.equals(hashes.get(id))) {
        return;
      }
      Image image = this.images.get(id);
      if (image == null) {
        return;
      }
      images.put(id, image);
    });

    CustomPaintingsMod.LOGGER.info(
        "{} needs to download {} image(s). Sending to client.", player.getName().getString(), images.size());
    long timer = Util.getMeasuringTimeMs();
    ImagePacketQueue.getInstance().add(player, images);

    DecimalFormat format = new DecimalFormat("0.##");
    CustomPaintingsMod.LOGGER.info("Sent {} images to {} in {}s", images.size(), player.getName().getString(),
        format.format((Util.getMeasuringTimeMs() - timer) / 1000.0)
    );
  }

  public void markMigrationFinished(Identifier migrationId, boolean succeeded) {
    this.finishedMigrations.put(migrationId, succeeded);
  }

  private LoadResult loadPaintingPacks() {
    CustomPaintingsMod.LOGGER.info("Loading painting packs");
    Path packsDir = PathAccessor.getInstance().getPerWorldModDir(CustomPaintingsMod.MOD_ID);

    if (packsDir == null || Files.notExists(packsDir)) {
      CustomPaintingsMod.LOGGER.info("Unable to locate packs directory, skipping");
      return LoadResult.empty();
    }

    try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(packsDir)) {
      HashMap<String, PackData> packs = new HashMap<>();
      HashMap<Identifier, Image> images = new HashMap<>();
      directoryStream.forEach((path) -> {
        PackReadResult result = readAsPack(path);
        if (result == null) {
          return;
        }

        PackResource resource = result.pack();
        packs.put(resource.id(), resource.toData(result.packFileUid()));
        images.putAll(result.images);
      });

      CustomPaintingsMod.LOGGER.info("Loaded {} pack(s) with {} painting(s)", packs.size(),
          packs.values().stream().mapToInt((pack) -> pack.paintings().size()).sum()
      );
      return new LoadResult(packs, images);
    } catch (IOException e) {
      CustomPaintingsMod.LOGGER.warn(e);
      CustomPaintingsMod.LOGGER.warn("An error occurred trying to load painting packs. Skipping...");
      return LoadResult.empty();
    }
  }

  private static PackReadResult readAsPack(Path path) {
    try {
      BasicFileAttributes fileAttributes = Files.readAttributes(
          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

      if (fileAttributes.isDirectory()) {
        return readDirectoryAsPack(path);
      }

      if (fileAttributes.isRegularFile()) {
        return readZipAsPack(path);
      }
    } catch (Exception e) {
      CustomPaintingsMod.LOGGER.warn(e);
      CustomPaintingsMod.LOGGER.warn("Error reading Custom Paintings pack \"{}\", skipping...", path.getFileName());
      return null;
    }

    return null;
  }

  private static PackReadResult readZipAsPack(Path path) {
    String filename = path.getFileName().toString();

    if (!filename.endsWith(".zip")) {
      CustomPaintingsMod.LOGGER.warn("Found non-zip Custom Paintings file \"{}\", skipping...", filename);
      return null;
    }

    if (path.getFileSystem() != FileSystems.getDefault()) {
      CustomPaintingsMod.LOGGER.warn(
          "Found zip Custom Paintings file \"{}\" outside the system's default file system, skipping...", filename);
      return null;
    }

    try (ZipFile zip = new ZipFile(path.toFile())) {
      String folderPrefix = ResourceUtil.getFolderPrefix(zip);
      if (!folderPrefix.isBlank()) {
        CustomPaintingsMod.LOGGER.info("Folder-in-zip detected in \"{}\", adjusting paths", filename);
      }

      ZipEntry zipMeta = zip.getEntry(folderPrefix + META_FILENAME);
      if (zipMeta == null) {
        CustomPaintingsMod.LOGGER.warn(LOG_NO_META, filename, META_FILENAME);
        return null;
      }

      PackResource pack;
      try (InputStream stream = zip.getInputStream(zipMeta)) {
        pack = CustomPaintingsMod.GSON.fromJson(new InputStreamReader(stream), PackResource.class);
      } catch (Exception e) {
        CustomPaintingsMod.LOGGER.warn(e);
        CustomPaintingsMod.LOGGER.warn(LOG_META_PARSE_FAIL, META_FILENAME, filename);
        return null;
      }

      if (pack.paintings().isEmpty()) {
        CustomPaintingsMod.LOGGER.warn(LOG_NO_PAINTINGS, filename);
        return null;
      }

      HashMap<Identifier, Image> images = new HashMap<>();

      ZipEntry zipIconImage = getIconZipEntry(zip, folderPrefix, pack.id());
      if (zipIconImage != null) {
        try (InputStream stream = zip.getInputStream(zipIconImage)) {
          BufferedImage image = ImageIO.read(stream);
          if (image == null) {
            throw new IOException("BufferedImage is null");
          }

          images.put(PackIcons.identifier(pack.id()), Image.read(image));
        } catch (IOException e) {
          CustomPaintingsMod.LOGGER.warn(e);
          CustomPaintingsMod.LOGGER.warn(LOG_ICON_READ_FAIL, pack.id());
        }
      }

      pack.paintings().forEach((painting) -> {
        Identifier id = new Identifier(pack.id(), painting.id());
        ZipEntry zipImage = ResourceUtil.getImageZipEntry(zip, folderPrefix, "images", painting.id() + ".png");
        if (zipImage == null) {
          CustomPaintingsMod.LOGGER.warn(LOG_MISSING_PAINTING, id);
          return;
        }

        try (InputStream stream = zip.getInputStream(zipImage)) {
          BufferedImage image = ImageIO.read(stream);
          if (image == null) {
            throw new IOException("BufferedImage is null");
          }

          long size = (long) image.getWidth() * image.getHeight();
          if (size > MAX_SIZE) {
            CustomPaintingsMod.LOGGER.warn(LOG_LARGE_IMAGE, id);
            return;
          }

          images.put(id, Image.read(image));
        } catch (IOException e) {
          CustomPaintingsMod.LOGGER.warn(e);
          CustomPaintingsMod.LOGGER.warn(LOG_PAINTING_READ_FAIL, id);
        }
      });

      long lastModified = ResourceUtil.lastModified(path);
      long fileSize = ResourceUtil.fileSize(path);
      String packFileUid = PackFileUid.create(true, filename, lastModified, fileSize);

      return new PackReadResult(packFileUid, pack, images);
    } catch (IOException e) {
      CustomPaintingsMod.LOGGER.warn(e);
      CustomPaintingsMod.LOGGER.warn("Failed to load Custom Paintings pack \"{}\", skipping...", filename);
    }

    return null;
  }

  private static ZipEntry getIconZipEntry(ZipFile zip, String folderPrefix, String packId) {
    ZipEntry entry = ResourceUtil.getImageZipEntry(zip, folderPrefix, ICON_PNG);
    if (entry == null) {
      entry = ResourceUtil.getImageZipEntry(zip, folderPrefix, PACK_PNG);
      if (entry == null) {
        CustomPaintingsMod.LOGGER.warn(LOG_NO_ICON, packId);
      } else {
        CustomPaintingsMod.LOGGER.warn(LOG_LEGACY_ICON, packId);
      }
    }
    return entry;
  }

  private static PackReadResult readDirectoryAsPack(Path path) {
    String dirname = path.getFileName().toString();

    if (!Files.isRegularFile(path.resolve(META_FILENAME), LinkOption.NOFOLLOW_LINKS)) {
      CustomPaintingsMod.LOGGER.warn(LOG_NO_META, dirname, META_FILENAME);
      return null;
    }

    PackResource pack;
    try {
      pack = CustomPaintingsMod.GSON.fromJson(Files.newBufferedReader(path.resolve(META_FILENAME)), PackResource.class);
    } catch (Exception e) {
      CustomPaintingsMod.LOGGER.warn(e);
      CustomPaintingsMod.LOGGER.warn(LOG_META_PARSE_FAIL, META_FILENAME, dirname);
      return null;
    }

    if (pack.paintings().isEmpty()) {
      CustomPaintingsMod.LOGGER.warn(LOG_NO_PAINTINGS, dirname);
      return null;
    }

    HashMap<Identifier, Image> images = new HashMap<>();

    Path iconImagePath = getIconPath(path, pack.id());
    if (iconImagePath != null) {
      try {
        BufferedImage image = ImageIO.read(Files.newInputStream(iconImagePath, LinkOption.NOFOLLOW_LINKS));
        if (image == null) {
          throw new IOException("BufferedImage is null");
        }

        images.put(PackIcons.identifier(pack.id()), Image.read(image));
      } catch (IOException e) {
        CustomPaintingsMod.LOGGER.warn(e);
        CustomPaintingsMod.LOGGER.warn(LOG_ICON_READ_FAIL, pack.id());
      }
    }

    pack.paintings().forEach((painting) -> {
      Identifier id = new Identifier(pack.id(), painting.id());
      Path imagePath = path.resolve("images").resolve(painting.id() + ".png");
      if (!Files.exists(imagePath)) {
        CustomPaintingsMod.LOGGER.warn(LOG_MISSING_PAINTING, id);
        return;
      }

      try {
        BufferedImage image = ImageIO.read(Files.newInputStream(imagePath, LinkOption.NOFOLLOW_LINKS));
        if (image == null) {
          throw new IOException("BufferedImage is null");
        }

        long size = (long) image.getWidth() * image.getHeight();
        if (size > MAX_SIZE) {
          CustomPaintingsMod.LOGGER.warn(LOG_LARGE_IMAGE, id);
          return;
        }

        images.put(id, Image.read(image));
      } catch (IOException e) {
        CustomPaintingsMod.LOGGER.warn(e);
        CustomPaintingsMod.LOGGER.warn(LOG_PAINTING_READ_FAIL, id);
      }
    });

    long lastModified = ResourceUtil.lastModified(path);
    long fileSize = ResourceUtil.fileSize(path);
    String packFileUid = PackFileUid.create(false, dirname, lastModified, fileSize);

    return new PackReadResult(packFileUid, pack, images);
  }

  private static Path getIconPath(Path parent, String packId) {
    Path path = parent.resolve(ICON_PNG);
    if (!Files.exists(path)) {
      path = parent.resolve(PACK_PNG);
      if (!Files.exists(path)) {
        CustomPaintingsMod.LOGGER.warn(LOG_NO_ICON, packId);
        return null;
      } else {
        CustomPaintingsMod.LOGGER.warn(LOG_LEGACY_ICON, packId);
      }
    }
    return path;
  }

  private record PackReadResult(String packFileUid, PackResource pack, HashMap<Identifier, Image> images) {
  }

  private record LoadResult(HashMap<String, PackData> packs, HashMap<Identifier, Image> images) {
    public static LoadResult empty() {
      return new LoadResult(new HashMap<>(0), new HashMap<>(0));
    }
  }
}
