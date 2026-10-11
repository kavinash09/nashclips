package nashclip;

import javafx.animation.*;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Unmanaged overlay: one size notice, no extra tile or layout space. */
final class SizeNotice extends StackPane implements AutoCloseable {
    private final Label detail = new Label();
    private final Stage stage;
    private final BorderPane root;
    private final ChangeListener<Number> widthListener;
    private final ChangeListener<Boolean> showing;
    private Animation animation;
    private int seconds;
    private boolean closed;

    SizeNotice(Stage stage, BorderPane root) {
        this.stage = stage;
        this.root = root;
        setManaged(false);
        setPickOnBounds(false);
        setVisible(false);
        Label title = new Label("Last copied clip was too large");
        title.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;");
        detail.setStyle("-fx-text-fill: #ffe1e1; -fx-font-size: 11px;");
        Label icon = new Label("!");
        icon.setStyle("-fx-text-fill: #ffb4b4; -fx-font-size: 24px; -fx-font-weight: bold;");
        VBox words = new VBox(4, title, detail);
        HBox.setHgrow(words, Priority.ALWAYS);
        Button dismiss = new Button("×");
        dismiss.setAccessibleText("Dismiss size notice");
        dismiss.setTooltip(new javafx.scene.control.Tooltip("Dismiss size notice"));
        dismiss.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 18px;");
        dismiss.setOnAction(event -> dismiss());
        HBox card = new HBox(12, icon, words, dismiss);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(440);
        card.setStyle("-fx-background-color: #702225; -fx-background-radius: 12; -fx-padding: 12 14;");
        getChildren().add(card);
        root.getChildren().add(this);
        prefWidthProperty().bind(root.widthProperty().subtract(32));
        widthListener = (observable, before, after) ->
                resizeRelocate(16, 62, Math.max(0, after.doubleValue() - 32), 72);
        root.widthProperty().addListener(widthListener);
        resizeRelocate(16, 62, Math.max(0, root.getWidth() - 32), 72);
        showing = (observable, before, visible) -> {
            if (visible && isVisible()) begin(); else stopAnimation();
        };
        stage.showingProperty().addListener(showing);
    }

    void showSize(int units, int seconds) {
        if (closed) return;
        this.seconds = seconds;
        detail.setText(String.format(java.util.Locale.ROOT,
                "%.1f KiB text · 128 KiB limit. Not saved.", units / 512.0));
        setVisible(true);
        if (stage.isShowing()) begin();
    }

    private void begin() {
        if (closed) return;
        stopAnimation();
        setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), this);
        fadeIn.setToValue(1);
        if (seconds == 0) animation = fadeIn;
        else {
            PauseTransition hold = new PauseTransition(Duration.millis(seconds * 1000 - 450));
            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), this);
            fadeOut.setToValue(0);
            animation = new SequentialTransition(fadeIn, hold, fadeOut);
            animation.setOnFinished(event -> dismiss());
        }
        animation.play();
    }

    private void stopAnimation() { if (animation != null) animation.stop(); animation = null; }
    private void dismiss() { stopAnimation(); setVisible(false); detail.setText(""); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        dismiss();
        stage.showingProperty().removeListener(showing);
        root.widthProperty().removeListener(widthListener);
        prefWidthProperty().unbind();
        root.getChildren().remove(this);
    }
}
