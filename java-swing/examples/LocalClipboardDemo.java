import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// 1. Define the complex custom object
class VisualElement {
    String text;
    Color color;
    List<Point> points;

    public VisualElement(String text, Color color, List<Point> points) {
        this.text = text;
        this.color = color;
        this.points = points;
    }

    @Override
    public String toString() {
        return "VisualElement{text='" + text + "', color=" + color + ", pointsCount=" + points.size() + "}";
    }
}

// 2. Create a Transferable wrapper to hold our custom object
class VisualElementTransferable implements Transferable {
    // Define a custom DataFlavor specifically for our local object type
    public static final DataFlavor VISUAL_ELEMENT_FLAVOR = 
        new DataFlavor(VisualElement.class, "Local Visual Element");

    private final VisualElement element;

    public VisualElementTransferable(VisualElement element) {
        this.element = element;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{VISUAL_ELEMENT_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return VISUAL_ELEMENT_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
        if (!isDataFlavorSupported(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return element; // Returns the live object directly
    }
}

// 3. Build the UI application demonstrating the local transfer
public class LocalClipboardDemo extends JFrame {
    // Create an application-wide Local Clipboard (separate from the OS clipboard)
    private final Clipboard localClipboard = new Clipboard("MyInternalClipboard");
    private VisualElement sourceElement;

    public LocalClipboardDemo() {
        setTitle("Java Local Clipboard Demonstration");
        setSize(600, 300);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new GridLayout(1, 2, 10, 10));

        // Create sample data for our complex object
        List<Point> samplePoints = new ArrayList<>();
        samplePoints.add(new Point(10, 20));
        samplePoints.add(new Point(30, 40));
        sourceElement = new VisualElement("Custom UI Vector Block", Color.MAGENTA, samplePoints);

        // Panel 1: The Copier Window
        JPanel panelSource = new JPanel();
        panelSource.setBorder(BorderFactory.createTitledBorder("Source Window"));
        JLabel sourceLabel = new JLabel("Object to copy: " + sourceElement.text);
        JButton copyButton = new JButton("Copy Object Internally");
        
        copyButton.addActionListener(e -> {
            VisualElementTransferable transferable = new VisualElementTransferable(sourceElement);
            localClipboard.setContents(transferable, null);
            JOptionPane.showMessageDialog(this, "Complex object copied to Local Clipboard!");
        });
        
        panelSource.add(sourceLabel);
        panelSource.add(copyButton);

        // Panel 2: The Paster Window
        JPanel panelTarget = new JPanel();
        panelTarget.setBorder(BorderFactory.createTitledBorder("Target Window"));
        JTextArea outputArea = new JTextArea(5, 22);
        outputArea.setEditable(false);
        JButton pasteButton = new JButton("Paste Object");

        pasteButton.addActionListener(e -> {
            Transferable contents = localClipboard.getContents(null);
            if (contents != null && contents.isDataFlavorSupported(VisualElementTransferable.VISUAL_ELEMENT_FLAVOR)) {
                try {
                    // Extract the exact object reference safely
                    VisualElement pastedElement = (VisualElement) contents.getTransferData(VisualElementTransferable.VISUAL_ELEMENT_FLAVOR);
                    outputArea.setText("Successfully Received Object:\n" + pastedElement.toString());
                } catch (Exception ex) {
                    outputArea.setText("Paste failed: " + ex.getMessage());
                }
            } else {
                outputArea.setText("Local clipboard is empty or has incompatible data.");
            }
        });

        panelTarget.add(new JScrollPane(outputArea));
        panelTarget.add(pasteButton);

        add(panelSource);
        add(panelTarget);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LocalClipboardDemo().setVisible(true));
    }
}
