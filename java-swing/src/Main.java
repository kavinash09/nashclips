class Main {
    public static void main(String[] args) {
        try {
            new NashClip().run();
        } catch (java.io.IOException error) {
            System.err.println("Cannot start nashclip: " + error.getMessage());
            javax.swing.JOptionPane.showMessageDialog(null, error.getMessage(),
                    "Cannot start nashclip", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }
}
