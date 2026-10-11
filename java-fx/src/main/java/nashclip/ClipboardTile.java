package nashclip;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

/** One history entry. It reports actions to the app rather than changing storage itself. */
final class ClipboardTile extends VBox {
    private final Text content;

    ClipboardTile(String value, int index, boolean pinned, Runnable onPin, Runnable onUse, Runnable onDelete) {
        getStyleClass().add("clip-tile");
        setSpacing(10);
        setPadding(new Insets(12));
        setPrefWidth(220);
        setMinWidth(220);
        setMaxWidth(220);

        Label kind = new Label(pinned ? "PINNED" : "≡  TEXT");
        kind.getStyleClass().add("tile-kind");
        kind.setMaxWidth(Double.MAX_VALUE);
        Label number = new Label(String.format("%02d", index + 1));
        number.getStyleClass().add("tile-number");
        javafx.scene.control.ToggleButton pin = new javafx.scene.control.ToggleButton("⌖");
        pin.getStyleClass().add("pin-button");
        pin.setSelected(pinned);
        pin.setAccessibleText(pinned ? "Unpin this clip" : "Pin this clip (up to 5)");
        pin.setTooltip(new javafx.scene.control.Tooltip(pin.getAccessibleText()));
        pin.setOnAction(event -> onPin.run());
        Button delete = new Button("×");
        delete.getStyleClass().add("delete-button");
        delete.setAccessibleText("Delete clip " + (index + 1));
        delete.setTooltip(new javafx.scene.control.Tooltip("Delete this clip"));
        delete.setOnAction(event -> onDelete.run());
        HBox heading = new HBox(6, kind, number, pin, delete);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(kind, Priority.ALWAYS);

        content = new Text(HistoryPolicy.preview(value));
        content.getStyleClass().add("tile-text");
        content.setWrappingWidth(196);

        int characters = value.codePointCount(0, value.length());
        Label length = new Label(characters + (characters == 1 ? " character" : " characters"));
        length.getStyleClass().add("tile-number");
        length.setMaxWidth(Double.MAX_VALUE);
        Button use = new Button("Use  ↗");
        use.getStyleClass().add("use-button");
        use.setAccessibleText("Use clip " + (index + 1));
        use.setTooltip(new javafx.scene.control.Tooltip("Paste into the previous app"));
        use.setOnAction(event -> onUse.run());
        HBox footer = new HBox(8, length, use);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(length, Priority.ALWAYS);

        getChildren().addAll(heading, content, footer);
    }

    double measuredHeight() {
        return 94 + Math.max(48, content.getLayoutBounds().getHeight());
    }
}
