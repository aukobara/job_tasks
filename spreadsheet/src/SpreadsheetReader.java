import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read Spreadsheet data from formatted string input stream.
 * Sample usage:
 * // 1. Init reader by header of spreadsheet of 3 rows and 2 columns.
 * SpreadsheetReader reader = new SpreadsheetReader("3 2");
 * // 2. Pass stream of cell formulas
 * reader.readLines(Stream.of("1", "2", "3", "4", "5", "6"));
 * // 3. Build Spreadsheet
 * spreadsheet = reader.build();
 */
public class SpreadsheetReader {
    public static final Pattern REGEX_HEADER = Pattern.compile("^(?<width>\\d+)\\s+(?<height>\\d+)$");

    private int width, height;

    public SpreadsheetReader(String header) {
        Matcher m = REGEX_HEADER.matcher(header);
        if (!m.find()) {
            throw new IllegalArgumentException(String.format(
                    "Invalid spreadsheet header format: %s", header));
        }
        this.width = Integer.parseInt(m.group("width"));
        this.height = Integer.parseInt(m.group("height"));
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Spreadsheet build(Iterator<String> lineIterator) {
        Spreadsheet spreadsheet = new Spreadsheet(this);
        int cellNumber = 0, size = this.width * this.height;

        while (lineIterator.hasNext() && cellNumber < size) {
            String formula = lineIterator.next();
            spreadsheet.setCellFormula(cellNumber / this.width, cellNumber % this.width, formula);
            cellNumber++;
        }
        if (cellNumber != size) {
            throw new IllegalArgumentException(String.format(
                    "Input table is of incorrect size. Must be: %d cells, but got: %d",
                    size, cellNumber));
        }
        return spreadsheet;
    }
}
