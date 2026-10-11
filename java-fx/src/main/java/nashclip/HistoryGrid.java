package nashclip;

import java.util.function.IntFunction;

import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Places each tile below the shortest column, with a responsive column count. */
final class HistoryGrid extends ScrollPane {
    private static final int TILE_WIDTH = 220;
    private static final int GAP = 12;

    private final ObservableList<String> history;
    private final IntFunction<ClipboardTile> tileAt;
    private int columnsCount = 3;

    HistoryGrid(ObservableList<String> history, IntFunction<ClipboardTile> tileAt) {
        this.history = history;
        this.tileAt = tileAt;
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        getStyleClass().add("history-scroll");
        refresh();
    }

    void setAvailableWidth(double windowWidth) {
        double available = windowWidth - 28 - 9;
        int count = Math.max(1, Math.min(3, (int) ((available + GAP) / (TILE_WIDTH + GAP))));
        if (columnsCount != count) {
            columnsCount = count;
            refresh();
        }
    }

    void refresh() {
        if (history.isEmpty()) {
            Label title = new Label("A little space for your next idea");
            title.getStyleClass().add("empty-title");
            Label hint = new Label("Copy some text. It will appear here, ready to use again.");
            hint.getStyleClass().add("empty-hint");
            VBox empty = new VBox(12, title, hint);
            empty.setAlignment(Pos.CENTER);
            empty.setPadding(new Insets(42, 0, 42, 0));
            setContent(empty);
            return;
        }

        HBox columns = new HBox(GAP);
        columns.setAlignment(Pos.TOP_CENTER);
        columns.setPadding(new Insets(2, 0, 12, 0));
        VBox[] stacks = new VBox[columnsCount];
        double[] heights = new double[columnsCount];
        for (int column = 0; column < columnsCount; column++) {
            stacks[column] = new VBox(GAP);
            stacks[column].setPrefWidth(TILE_WIDTH);
            columns.getChildren().add(stacks[column]);
        }
        for (int index = 0; index < history.size(); index++) {
            ClipboardTile tile = tileAt.apply(index);
            int shortest = 0;
            for (int column = 1; column < columnsCount; column++) {
                if (heights[column] < heights[shortest]) shortest = column;
            }
            if (!stacks[shortest].getChildren().isEmpty()) heights[shortest] += GAP;
            stacks[shortest].getChildren().add(tile);
            heights[shortest] += tile.measuredHeight();
        }
        StackPane canvas = new StackPane(columns);
        canvas.setAlignment(Pos.TOP_CENTER);
        setContent(canvas);
        setVvalue(0);
    }
}
