import java.io.*;

/**
 * This is a job-interview task for Redmart.
 * It is a simple implementation of spreadsheet calculator aka excel
 */
public class Spreadsheet {

    private final double[][] values;
    private final Formula[][] matrix;
    private final int width, height;

    public Spreadsheet(SpreadsheetReader reader) {
        this(reader.getHeight(), reader.getWidth());
    }

    public Spreadsheet(int height, int width) {
        this.height = height;
        this.width = width;
        matrix = new Formula[height][width];
        values = new double[height][width];
    }

    public int getHeight() {
        return height;
    }

    public int getWidth() { return width; }

    public Formula getCellFormula(int row, int column) {
        return this.matrix[row][column];
    }

    public void setCellFormula(int row, int column, String formulaString) {
        Formula formula = new Formula(formulaString);
        if (formula.hasDependencies()) {
            this.matrix[row][column] = formula;
        } else {
            this.values[row][column] = formula.calc();
        }
    }

    public double calcCellValue(int row, int column) {
        Formula formula = this.matrix[row][column];
        if (formula == null) {
            return this.values[row][column];
        }
        double value = formula.calc(this);
        this.values[row][column] = value;
        this.matrix[row][column] = null;
        return value;
    }

    /**
     * Convenience method returns cell reference in spreadsheet format.
     * (0, 0) -> "A1", (0, 1) -> "A2", (1, 0) -> "B1" and so on.
     */
    public static String getCellName(int row, int column) {
        return String.format("%s%d", (char) ('A' + row), column + 1);
    }

    public static void main(String[] args) throws IOException {
        // 0. Simulate input stream from args for testing in IDE
        if (args.length >= 1) {
            String fileName = args[0];
            System.setIn(new FileInputStream(fileName));
        }

        long start = System.currentTimeMillis();

        // 1. Read spreadsheet from stdin
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        if (!input.ready()) {
            log("Please pass spreadsheet.txt into stdin");
            System.exit(-1);
        }

        // 2. Build spreadsheet object
        String header = input.readLine();
        SpreadsheetReader reader = new SpreadsheetReader(header);
        Spreadsheet spreadsheet = reader.build(input.lines().iterator());

        long startCalc = System.currentTimeMillis();
        log(String.format("Spreadsheet loaded into memory: %dms. Free memory: %d",
                startCalc - start, Runtime.getRuntime().freeMemory()));

        // 3. Calculate and output cells
        System.setOut(new PrintStream(new BufferedOutputStream(System.out, 64000)));
        System.out.println(String.format("%d %d", spreadsheet.getWidth(), spreadsheet.getHeight()));
        for (int r = 0; r < spreadsheet.getHeight(); r++) {
            for (int c = 0; c < spreadsheet.getWidth(); c++) {
                try {
                    double cellValue = spreadsheet.calcCellValue(r, c);
                    System.out.println(String.format("%.5f", cellValue));
                } catch (Exception e) {
                    log("Error detected while calculating formula in cell " + Spreadsheet.getCellName(r, c));
                    log(e.getMessage());
                    System.exit(-1);
                }
            }
//            log(String.format("Row %c passed. Time elapsed: %dms. Free memory: %d", 'A'+r,
//                    System.currentTimeMillis()-startCalc, Runtime.getRuntime().freeMemory()));
        }

        long end = System.currentTimeMillis();
        log(String.format("Time elapsed: %dms, including calc: %dms. Free memory: %d",
                end - start, end - startCalc, Runtime.getRuntime().freeMemory()));
    }

    public static void log(String msg) {
        System.err.println(msg);
    }
}
