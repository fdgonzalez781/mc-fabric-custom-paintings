package me.roundaround.custompaintings.client.gui.screen;

import me.roundaround.custompaintings.client.gui.widget.LoadingButtonWidget;
import me.roundaround.custompaintings.client.gui.widget.SpriteWidget;
import me.roundaround.custompaintings.client.network.ClientNetworking;
import me.roundaround.custompaintings.client.registry.ClientPaintingRegistry;
import me.roundaround.custompaintings.entity.decoration.painting.PackData;
import me.roundaround.custompaintings.resource.PackIcons;
import me.roundaround.roundalib.client.gui.GuiUtil;
import me.roundaround.roundalib.client.gui.layout.FillerWidget;
import me.roundaround.roundalib.client.gui.layout.linear.LinearLayoutWidget;
import me.roundaround.roundalib.client.gui.layout.screen.ThreeSectionLayoutWidget;
import me.roundaround.roundalib.client.gui.util.Alignment;
import me.roundaround.roundalib.client.gui.widget.FlowListWidget;
import me.roundaround.roundalib.client.gui.widget.ParentElementEntryListWidget;
import me.roundaround.roundalib.client.gui.widget.drawable.LabelWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;

public class PacksScreen extends Screen implements PacksLoadedListener {
  private static final int BUTTON_HEIGHT = ButtonWidget.DEFAULT_HEIGHT;
  private static final int BUTTON_WIDTH = ButtonWidget.DEFAULT_WIDTH;

  private final ThreeSectionLayoutWidget layout = new ThreeSectionLayoutWidget(this);
  private final Screen parent;

  private PackList list;
  private LoadingButtonWidget reloadButton;

  public PacksScreen(Screen parent) {
    super(Text.of("Painting Packs"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    assert this.client != null;

    this.layout.addHeader(this.textRenderer, this.title);

    this.list = this.layout.addBody(
        new PackList(this.client, this.layout, () -> ClientPaintingRegistry.getInstance().getPacks().values()));

    // TODO: i18n
    this.reloadButton = this.layout.addFooter(
        new LoadingButtonWidget(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Reload packs"), (b) -> this.reloadPacks()));
    this.layout.addFooter(ButtonWidget.builder(ScreenTexts.DONE, (b) -> this.close()).width(BUTTON_WIDTH).build());

    this.layout.forEachChild(this::addDrawableChild);
    this.initTabNavigation();
  }

  @Override
  protected void initTabNavigation() {
    this.layout.refreshPositions();
  }

  @Override
  public void close() {
    Objects.requireNonNull(this.client).setScreen(this.parent);
  }

  @Override
  public void onPacksLoaded() {
    this.reloadButton.setLoading(false);
    this.list.reFetchPacks();
  }

  private void reloadPacks() {
    this.reloadButton.setLoading(true);
    Util.getIoWorkerExecutor().execute(ClientNetworking::sendReloadPacket);
  }

  private static class PackList extends ParentElementEntryListWidget<PackList.Entry> {
    private final Supplier<Collection<PackData>> packsSupplier;

    public PackList(
        MinecraftClient client, ThreeSectionLayoutWidget layout, Supplier<Collection<PackData>> packsSupplier
    ) {
      super(client, layout);

      this.packsSupplier = packsSupplier;
      this.init();
    }

    public void reFetchPacks() {
      this.clearEntries();
      this.init();
    }

    private void init() {
      Collection<PackData> packs = this.packsSupplier.get();
      if (packs.isEmpty()) {
        this.addEntry(EmptyEntry.factory(this.client.textRenderer));
      }
      for (PackData pack : packs) {
        this.addEntry(PackEntry.factory(this.client.textRenderer, pack));
      }
    }

    private static abstract class Entry extends ParentElementEntryListWidget.Entry {
      protected Entry(int index, int left, int top, int width, int contentHeight) {
        super(index, left, top, width, contentHeight);
      }
    }

    private static class EmptyEntry extends Entry {
      private static final int HEIGHT = 36;
      private static final Text MESSAGE = Text.translatable("custompaintings.migrate.none");

      private final LabelWidget label;

      protected EmptyEntry(int index, int left, int top, int width, TextRenderer textRenderer) {
        super(index, left, top, width, HEIGHT);

        this.label = LabelWidget.builder(textRenderer, MESSAGE)
            .position(this.getContentCenterX(), this.getContentCenterY())
            .dimensions(this.getContentWidth(), this.getContentHeight())
            .alignSelfCenterX()
            .alignSelfCenterY()
            .alignTextCenterX()
            .alignTextCenterY()
            .hideBackground()
            .showShadow()
            .build();

        this.addDrawable(this.label);
      }

      public static FlowListWidget.EntryFactory<EmptyEntry> factory(TextRenderer textRenderer) {
        return (index, left, top, width) -> new EmptyEntry(index, left, top, width, textRenderer);
      }

      @Override
      public void refreshPositions() {
        this.label.batchUpdates(() -> {
          this.label.setPosition(this.getContentCenterX(), this.getContentCenterY());
          this.label.setDimensions(this.getContentWidth(), this.getContentHeight());
        });
      }
    }

    private static class PackEntry extends Entry {
      private static final int HEIGHT = 36;
      private static final int PACK_ICON_SIZE = 32;

      protected PackEntry(
          int index, int left, int top, int width, TextRenderer textRenderer, PackData pack
      ) {
        super(index, left, top, width, HEIGHT);

        LinearLayoutWidget layout = this.addLayout(
            LinearLayoutWidget.horizontal().spacing(GuiUtil.PADDING).defaultOffAxisContentAlign(Alignment.CENTER),
            (self) -> {
              self.setPositionAndDimensions(
                  this.getContentLeft(), this.getContentTop(), this.getContentWidth(), this.getContentHeight());
            }
        );

        layout.add(SpriteWidget.create(ClientPaintingRegistry.getInstance().getSprite(PackIcons.identifier(pack.id()))),
            (parent, self) -> {
              self.setDimensions(PACK_ICON_SIZE, PACK_ICON_SIZE);
            }
        );

        layout.add(FillerWidget.empty());

        LinearLayoutWidget textSection = LinearLayoutWidget.vertical().spacing(GuiUtil.PADDING);
        textSection.add(LabelWidget.builder(textRenderer, Text.of(pack.name()))
            .alignTextLeft()
            .overflowBehavior(LabelWidget.OverflowBehavior.SCROLL)
            .hideBackground()
            .showShadow()
            .build(), (parent, self) -> self.setWidth(parent.getWidth()));
        layout.add(textSection, (parent, self) -> {
          int textSectionWidth = this.getContentWidth();
          textSectionWidth -= (parent.getChildren().size() - 1) * parent.getSpacing();
          for (Widget widget : parent.getChildren()) {
            if (widget != self) {
              textSectionWidth -= widget.getWidth();
            }
          }
          self.setWidth(textSectionWidth);
        });

        layout.forEachChild(this::addDrawableChild);
      }

      public static FlowListWidget.EntryFactory<PackEntry> factory(
          TextRenderer textRenderer, PackData pack
      ) {
        return (index, left, top, width) -> new PackEntry(index, left, top, width, textRenderer, pack);
      }
    }
  }
}