package me.roundaround.custompaintings.client.registry;

import me.roundaround.custompaintings.CustomPaintingsMod;
import me.roundaround.custompaintings.config.CustomPaintingsConfig;
import me.roundaround.custompaintings.registry.ImageStore;
import me.roundaround.custompaintings.resource.Image;
import me.roundaround.custompaintings.util.CustomId;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.*;
import net.minecraft.util.Util;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class CacheManager {
  private static CacheManager instance = null;

  private UUID serverId = null;

  private CacheManager() {
  }

  public static CacheManager getInstance() {
    if (instance == null) {
      instance = new CacheManager();
    }
    return instance;
  }

  public static void runBackgroundClean() {
    CompletableFuture.runAsync(() -> {
      try {
        Path dataFile = getDataFile(getCacheDir());

        if (Files.notExists(dataFile) || !Files.isRegularFile(dataFile)) {
          return;
        }

        NbtCompound nbt = NbtIo.readCompressed(dataFile, NbtSizeTracker.ofUnlimitedBytes());
        CacheData data = CacheData.fromNbt(nbt);

        trimExpired(data);
      } catch (Exception ignored) {
        // TODO: Handle exception
      }
    }, Util.getIoWorkerExecutor());
  }

  public CacheRead loadFromFile(
      UUID serverId, HashSet<CustomId> requestedImageIds
  ) {
    this.serverId = serverId;
    Path cacheDir = getCacheDir();
    Path dataFile = getDataFile(cacheDir);

    if (Files.notExists(dataFile) || !Files.isRegularFile(dataFile)) {
      return null;
    }

    NbtCompound nbt;
    try {
      nbt = NbtIo.readCompressed(dataFile, NbtSizeTracker.ofUnlimitedBytes());
    } catch (IOException e) {
      CustomPaintingsMod.LOGGER.warn("Failed to load cache data");
      return null;
    }

    CacheData data = CacheData.fromNbt(nbt);
    if (!data.byServer().containsKey(this.serverId)) {
      return null;
    }

    final long expired = Util.getEpochTimeMs() - getTtlMs();
    ServerCacheData server = data.byServer().get(this.serverId);

    if (server.lastAccess() <= expired) {
      return null;
    }

    Set<String> requestedPackIds = requestedImageIds.stream().map(CustomId::pack).collect(Collectors.toSet());
    HashMap<CustomId, String> requestedHashes = new HashMap<>();

    for (PackCacheData pack : server.packs()) {
      if (requestedPackIds.contains(pack.packId())) {
        for (ImageCacheData image : pack.images()) {
          if (image.lastAccess() > expired && requestedImageIds.contains(image.id())) {
            requestedHashes.put(image.id(), image.hash());
          }
        }
      }
    }

    HashMap<CustomId, Image> images = new HashMap<>();

    requestedHashes.forEach((id, hash) -> {
      Image image = loadImage(cacheDir, hash);
      if (image == null || image.isEmpty()) {
        return;
      }
      images.put(id, image);
    });

    String combinedHash = server.combinedHash();

    // Note to self: run in this thread and block to avoid any potential of multiple threads accessing the file at the
    // same time (i.e. saveToFile or trimExpired).
    touchCache(data, serverId, combinedHash, Map.copyOf(requestedHashes));

    return new CacheRead(images, requestedHashes, combinedHash);
  }

  public void saveToFile(ImageStore images, String combinedHash) throws IOException {
    if (this.serverId == null) {
      return;
    }

    final long now = Util.getEpochTimeMs();

    Path cacheDir = getCacheDir();
    if (Files.notExists(cacheDir)) {
      Files.createDirectories(cacheDir);
    }

    Path dataFile = getDataFile(cacheDir);
    HashMap<String, PackCacheData> packs = new HashMap<>();

    images.forEach((id, image, hash) -> {
      try {
        String packId = id.pack();
        PackCacheData pack = packs.computeIfAbsent(packId, (k) -> PackCacheData.empty(packId));

        if (hash == null) {
          CustomPaintingsMod.LOGGER.warn("Failed to save image to cache: {}", id);
          return;
        }

        ImageIO.write(image.toBufferedImage(), "png", cacheDir.resolve(hash + ".png").toFile());
        pack.images().add(new ImageCacheData(id, hash, now));
      } catch (IOException e) {
        CustomPaintingsMod.LOGGER.warn(e);
        CustomPaintingsMod.LOGGER.warn("Failed to save image to cache: {}", id);
      }
    });

    NbtCompound nbt;
    if (Files.notExists(dataFile)) {
      nbt = new NbtCompound();
    } else {
      try {
        nbt = NbtIo.readCompressed(dataFile, NbtSizeTracker.ofUnlimitedBytes());
      } catch (IOException e) {
        CustomPaintingsMod.LOGGER.warn("Failed to read existing cache data before writing");
        nbt = new NbtCompound();
      }
    }

    CacheData data = CacheData.fromNbt(nbt);
    data.byServer()
        .put(this.serverId, new ServerCacheData(this.serverId, combinedHash, new ArrayList<>(packs.values()), now));

    images.getHashes().forEach((id, hash) -> {
      ArrayList<HashCacheData> hashData = data.byHash().computeIfAbsent(hash, (k) -> new ArrayList<>());
      hashData.removeIf((datum) -> datum.serverId().equals(this.serverId));
      hashData.add(new HashCacheData(this.serverId, now));
    });

    NbtIo.writeCompressed(data.toNbt(), dataFile);

    CompletableFuture.runAsync(() -> trimExpired(data), Util.getIoWorkerExecutor());
  }

  public void clear() throws IOException {
    Path cacheDir = getCacheDir();
    if (Files.notExists(cacheDir)) {
      return;
    }
    Files.walkFileTree(cacheDir, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  public CacheStats getStats() {
    Path cacheDir = getCacheDir();
    Path dataFile = getDataFile(cacheDir);

    NbtCompound nbt;
    if (Files.notExists(dataFile)) {
      nbt = new NbtCompound();
    } else {
      try {
        nbt = NbtIo.readCompressed(dataFile, NbtSizeTracker.ofUnlimitedBytes());
      } catch (IOException e) {
        // TODO: Handle exception
        throw new RuntimeException(e);
      }
    }
    CacheData data = CacheData.fromNbt(nbt);
    int servers = data.byServer().size();
    int images = data.byHash().size();
    int shared = (int) data.byHash().entrySet().stream().filter((entry) -> entry.getValue().size() > 1).count();

    final var bytes = new Object() {
      long value = 0;
    };
    try {
      Files.walkFileTree(cacheDir, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          bytes.value += attrs.size();
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          // Handle the error if a file cannot be accessed (optional)
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      // TODO: Handle exception
      throw new RuntimeException(e);
    }

    return new CacheStats(servers, images, shared, bytes.value);
  }

  private static Path getCacheDir() {
    return FabricLoader.getInstance().getGameDir().resolve("data").resolve(CustomPaintingsMod.MOD_ID).resolve("cache");
  }

  private static Path getDataFile(Path cacheDir) {
    return cacheDir.resolve("data.dat");
  }

  private static void deleteImage(Path cacheDir, String hash) throws IOException {
    Path path = cacheDir.resolve(hash + ".png");
    if (Files.notExists(path) || !Files.isRegularFile(path)) {
      return;
    }
    Files.delete(path);
  }

  private static Image loadImage(Path cacheDir, String hash) {
    Path path = cacheDir.resolve(hash + ".png");
    if (Files.notExists(path) || !Files.isRegularFile(path)) {
      return Image.empty();
    }

    try {
      return Image.read(Files.newInputStream(path));
    } catch (IOException e) {
      return Image.empty();
    }
  }

  private static long getTtlMs() {
    return 1000L * 60 * 60 * 24 * CustomPaintingsConfig.getInstance().cacheTtl.getValue();
  }

  private static void touchCache(
      CacheData data, UUID serverId, String combinedHash, Map<CustomId, String> touchedImages
  ) {
    final long now = Util.getEpochTimeMs();

    ServerCacheData server = getAndIfPresentOrCompute(data.byServer(), serverId,
        (existing) -> existing.setLastAccess(now),
        () -> new ServerCacheData(serverId, combinedHash, new ArrayList<>(), now)
    );

    HashMap<String, HashMap<CustomId, String>> imagesByPack = new HashMap<>();
    touchedImages.forEach((id, hash) -> {
      HashMap<CustomId, String> packImages = imagesByPack.computeIfAbsent(id.pack(), (unused) -> new HashMap<>());
      packImages.put(id, hash);
    });

    imagesByPack.forEach((packId, packImages) -> {
      PackCacheData pack = findOrCompute(server.packs(), (p) -> Objects.equals(p.packId(), packId),
          () -> new PackCacheData(packId, new ArrayList<>())
      );

      packImages.forEach((id, hash) -> {
        ifPresentOrCompute(pack.images(), (i) -> i.id().equals(id), (image) -> image.setLastAccess(now),
            () -> new ImageCacheData(id, hash, now)
        );
      });
    });

    touchedImages.forEach((id, hash) -> {
      Optional.ofNullable(data.byHash().get(hash)).ifPresent((usages) -> {
        ifPresentOrCompute(usages, (usage) -> usage.serverId().equals(serverId), (usage) -> usage.setLastAccess(now),
            () -> new HashCacheData(serverId, now)
        );
      });
    });

    try {
      NbtIo.writeCompressed(data.toNbt(), getDataFile(getCacheDir()));
    } catch (IOException e) {
      CustomPaintingsMod.LOGGER.warn(String.format("Failed to update access time in cache for server %s", serverId), e);
    }
  }

  private static void trimExpired(CacheData data) {
    Path cacheDir = getCacheDir();
    final long now = Util.getEpochTimeMs();
    final long ttl = getTtlMs();
    final long expired = now - ttl;

    if (Files.notExists(cacheDir)) {
      return;
    }

    Path dataFile = getDataFile(cacheDir);
    HashSet<String> remainingHashes = new HashSet<>();

    try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(cacheDir)) {
      directoryStream.forEach((path) -> {
        if (path.equals(dataFile)) {
          return;
        }

        String filename = path.getFileName().toString();
        if (!filename.toLowerCase().endsWith(".png")) {
          return;
        }

        String hash = filename.substring(0, filename.length() - 4);

        if (!data.byHash().containsKey(hash)) {
          try {
            deleteImage(cacheDir, hash);
          } catch (IOException e) {
            CustomPaintingsMod.LOGGER.warn(String.format("Failed to delete stale cached image %s.png", hash), e);
          }
          return;
        }

        ArrayList<HashCacheData> servers = data.byHash().get(hash);
        servers.removeIf((server) -> server.lastAccess() < expired);

        if (servers.isEmpty()) {
          data.byHash().remove(hash);
          try {
            deleteImage(cacheDir, hash);
          } catch (IOException e) {
            CustomPaintingsMod.LOGGER.warn(String.format("Failed to delete stale cached image %s.png", hash), e);
          }
          return;
        }

        remainingHashes.add(hash);
      });
    } catch (IOException e) {
      CustomPaintingsMod.LOGGER.warn("Failed to access cache directory for cleaning", e);
      return;
    }

    data.byServer().entrySet().removeIf((entry) -> {
      ServerCacheData server = entry.getValue();
      if (server.lastAccess() < expired) {
        return true;
      }

      ArrayList<PackCacheData> packs = server.packs();
      if (packs.isEmpty()) {
        return true;
      }

      packs.removeIf((pack) -> {
        ArrayList<ImageCacheData> images = pack.images();
        images.removeIf((image) -> !remainingHashes.contains(image.hash()) || image.lastAccess() < expired);
        return images.isEmpty();
      });

      return packs.isEmpty();
    });

    try {
      NbtIo.writeCompressed(data.toNbt(), dataFile);
    } catch (IOException e) {
      CustomPaintingsMod.LOGGER.warn("Failed to write trimmed cache data file", e);
    }
  }

  private static <T, U> U getAndIfPresentOrCompute(
      Map<T, U> map, T key, Consumer<U> ifPresent, Supplier<U> compute
  ) {
    U value = map.get(key);
    if (value != null) {
      ifPresent.accept(value);
      return value;
    }
    return compute.get();
  }

  private static <T> T findOrCompute(Collection<T> collection, Predicate<T> predicate, Supplier<T> compute) {
    return collection.stream().filter(predicate).findAny().orElseGet(() -> {
      T computed = compute.get();
      collection.add(computed);
      return computed;
    });
  }

  private static <T> void ifPresentOrCompute(
      Collection<T> collection, Predicate<T> predicate, Consumer<T> ifPresent, Supplier<T> compute
  ) {
    collection.stream().filter(predicate).findAny().ifPresentOrElse(ifPresent, () -> collection.add(compute.get()));
  }

  private record CacheData(int version, HashMap<UUID, ServerCacheData> byServer,
                           HashMap<String, ArrayList<HashCacheData>> byHash) {
    private static final String NBT_VERSION = "Version";
    private static final String NBT_BY_SERVER = "ByServer";
    private static final String NBT_BY_HASH = "ByHash";

    public static CacheData fromNbt(NbtCompound nbt) {
      int version = nbt.contains(NBT_VERSION, NbtElement.INT_TYPE) ? nbt.getInt(NBT_VERSION) : 1;
      NbtCompound serversNbt = nbt.contains(NBT_BY_SERVER, NbtElement.COMPOUND_TYPE) ?
          nbt.getCompound(NBT_BY_SERVER) :
          new NbtCompound();
      HashMap<UUID, ServerCacheData> byServer = new HashMap<>();
      for (String key : serversNbt.getKeys()) {
        UUID serverId = UUID.fromString(key);
        byServer.put(serverId, ServerCacheData.fromNbt(serversNbt.getCompound(key)));
      }

      NbtCompound hashesNbt = nbt.contains(NBT_BY_HASH, NbtElement.COMPOUND_TYPE) ?
          nbt.getCompound(NBT_BY_HASH) :
          new NbtCompound();
      HashMap<String, ArrayList<HashCacheData>> byHash = new HashMap<>();
      for (String hash : hashesNbt.getKeys()) {
        ArrayList<HashCacheData> items = byHash.computeIfAbsent(hash, (k) -> new ArrayList<>());
        NbtList list = hashesNbt.getList(hash, NbtElement.COMPOUND_TYPE);
        int size = list.size();
        for (int i = 0; i < size; i++) {
          items.add(HashCacheData.fromNbt(list.getCompound(i)));
        }
      }

      return new CacheData(version, byServer, byHash);
    }

    public NbtCompound toNbt() {
      NbtCompound nbt = new NbtCompound();
      nbt.putInt(NBT_VERSION, this.version);
      NbtCompound serversNbt = new NbtCompound();
      this.byServer.forEach((serverId, server) -> serversNbt.put(serverId.toString(), server.toNbt()));
      nbt.put(NBT_BY_SERVER, serversNbt);
      NbtCompound hashesNbt = new NbtCompound();
      this.byHash.forEach((hash, data) -> {
        NbtList list = new NbtList();
        data.forEach((datum) -> list.add(datum.toNbt()));
        hashesNbt.put(hash, list);
      });
      nbt.put(NBT_BY_HASH, hashesNbt);
      return nbt;
    }
  }

  private static final class HashCacheData {
    private static final String NBT_ID = "Id";
    private static final String NBT_LAST_ACCESS = "LastAccess";
    private final UUID serverId;
    private long lastAccess;

    private HashCacheData(UUID serverId, long lastAccess) {
      this.serverId = serverId;
      this.lastAccess = lastAccess;
    }

    public static HashCacheData fromNbt(NbtCompound nbt) {
      UUID serverId = nbt.getUuid(NBT_ID);
      long lastAccess = nbt.getLong(NBT_LAST_ACCESS);
      return new HashCacheData(serverId, lastAccess);
    }

    public NbtCompound toNbt() {
      NbtCompound nbt = new NbtCompound();
      nbt.putUuid(NBT_ID, this.serverId);
      nbt.putLong(NBT_LAST_ACCESS, this.lastAccess);
      return nbt;
    }

    public UUID serverId() {
      return this.serverId;
    }

    public long lastAccess() {
      return this.lastAccess;
    }

    public void setLastAccess(long lastAccess) {
      this.lastAccess = lastAccess;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this)
        return true;
      if (obj == null || obj.getClass() != this.getClass())
        return false;
      var that = (HashCacheData) obj;
      return Objects.equals(this.serverId, that.serverId) && this.lastAccess == that.lastAccess;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.serverId, this.lastAccess);
    }
  }

  private static final class ServerCacheData {
    private static final String NBT_ID = "Id";
    private static final String NBT_COMBINED_HASH = "CombinedHash";
    private static final String NBT_PACKS = "Packs";
    private static final String NBT_LAST_ACCESS = "LastAccess";

    private final UUID serverId;
    private final String combinedHash;
    private final ArrayList<PackCacheData> packs;

    private long lastAccess;

    private ServerCacheData(UUID serverId, String combinedHash, ArrayList<PackCacheData> packs, long lastAccess) {
      this.serverId = serverId;
      this.combinedHash = combinedHash;
      this.packs = packs;
      this.lastAccess = lastAccess;
    }

    public static ServerCacheData fromNbt(NbtCompound nbt) {
      UUID serverId = nbt.getUuid(NBT_ID);
      String combinedHash = nbt.getString(NBT_COMBINED_HASH);
      ArrayList<PackCacheData> packs = new ArrayList<>();
      NbtList list = nbt.getList(NBT_PACKS, NbtElement.COMPOUND_TYPE);
      int size = list.size();
      for (int i = 0; i < size; i++) {
        packs.add(PackCacheData.fromNbt(list.getCompound(i)));
      }
      long lastAccess = nbt.getLong(NBT_LAST_ACCESS);
      return new ServerCacheData(serverId, combinedHash, packs, lastAccess);
    }

    public NbtCompound toNbt() {
      NbtCompound nbt = new NbtCompound();
      nbt.putUuid(NBT_ID, this.serverId);
      nbt.putString(NBT_COMBINED_HASH, this.combinedHash);
      NbtList list = new NbtList();
      this.packs.forEach((pack) -> list.add(pack.toNbt()));
      nbt.put(NBT_PACKS, list);
      nbt.putLong(NBT_LAST_ACCESS, this.lastAccess);
      return nbt;
    }

    public UUID serverId() {
      return this.serverId;
    }

    public String combinedHash() {
      return this.combinedHash;
    }

    public ArrayList<PackCacheData> packs() {
      return this.packs;
    }

    public long lastAccess() {
      return this.lastAccess;
    }

    public void setLastAccess(long lastAccess) {
      this.lastAccess = lastAccess;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this)
        return true;
      if (obj == null || obj.getClass() != this.getClass())
        return false;
      var that = (ServerCacheData) obj;
      return Objects.equals(this.serverId, that.serverId) && Objects.equals(this.combinedHash, that.combinedHash) &&
             Objects.equals(this.packs, that.packs) && this.lastAccess == that.lastAccess;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.serverId, this.combinedHash, this.packs, this.lastAccess);
    }

  }

  private record PackCacheData(String packId, ArrayList<ImageCacheData> images) {
    private static final String NBT_ID = "Id";
    private static final String NBT_IMAGES = "Images";

    public static PackCacheData empty(String packId) {
      return new PackCacheData(packId, new ArrayList<>());
    }

    public static PackCacheData fromNbt(NbtCompound nbt) {
      String packId = nbt.getString(NBT_ID);
      ArrayList<ImageCacheData> images = new ArrayList<>();
      NbtList list = nbt.getList(NBT_IMAGES, NbtElement.COMPOUND_TYPE);
      int size = list.size();
      for (int i = 0; i < size; i++) {
        images.add(ImageCacheData.fromNbt(list.getCompound(i), packId));
      }
      return new PackCacheData(packId, images);
    }

    public NbtCompound toNbt() {
      NbtCompound nbt = new NbtCompound();
      nbt.putString(NBT_ID, this.packId);
      NbtList list = new NbtList();
      this.images.forEach((image) -> list.add(image.toNbt()));
      nbt.put(NBT_IMAGES, list);
      return nbt;
    }
  }

  private static final class ImageCacheData {
    private static final String NBT_ID = "Id";
    private static final String NBT_HASH = "Hash";
    private static final String NBT_LAST_ACCESS = "LastAccess";

    private final CustomId id;
    private final String hash;

    private long lastAccess;

    private ImageCacheData(CustomId id, String hash, long lastAccess) {
      this.id = id;
      this.hash = hash;
      this.lastAccess = lastAccess;
    }

    public static ImageCacheData fromNbt(NbtCompound nbt, String packId) {
      return new ImageCacheData(
          new CustomId(packId, nbt.getString(NBT_ID)), nbt.getString(NBT_HASH), nbt.getLong(NBT_LAST_ACCESS));
    }

    public NbtCompound toNbt() {
      NbtCompound nbt = new NbtCompound();
      nbt.putString(NBT_ID, this.id.resource());
      nbt.putString(NBT_HASH, this.hash);
      nbt.putLong(NBT_LAST_ACCESS, this.lastAccess);
      return nbt;
    }

    public CustomId id() {
      return this.id;
    }

    public String hash() {
      return this.hash;
    }

    public long lastAccess() {
      return this.lastAccess;
    }

    public void setLastAccess(long lastAccess) {
      this.lastAccess = lastAccess;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this)
        return true;
      if (obj == null || obj.getClass() != this.getClass())
        return false;
      var that = (ImageCacheData) obj;
      return Objects.equals(this.id, that.id) && Objects.equals(this.hash, that.hash) &&
             this.lastAccess == that.lastAccess;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.id, this.hash, this.lastAccess);
    }
  }

  public record CacheStats(int servers, int images, int shared, long bytes) {
  }
}
