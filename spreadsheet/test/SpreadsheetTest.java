import org.junit.Test;

import java.io.*;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Spreadsheet application tests.
 */
public class SpreadsheetTest {
    @Test
    public void testInputHeader() {
        SpreadsheetReader reader = new SpreadsheetReader("3 2");
        assertEquals(3, reader.getWidth());
        assertEquals(2, reader.getHeight());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInputNonComplete() {
        SpreadsheetReader reader = new SpreadsheetReader("3 2");
        reader.build(Stream.of("1", "2", "3", "4", "5").iterator());
    }

    private Spreadsheet buildTestSpreadsheet(String header, String... lines) {
        SpreadsheetReader reader = new SpreadsheetReader(header);
        return reader.build(Arrays.asList(lines).iterator());
    }

    @Test
    public void testBuildFromInput() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("3 2", "1", "2", "3", "4", "5", "6");
        assertEquals(3, spreadsheet.getWidth());
        assertEquals(2, spreadsheet.getHeight());
        assertEquals(1d, spreadsheet.calcCellValue(0, 0), 0);
        assertEquals(2d, spreadsheet.calcCellValue(0, 1), 0);
        assertEquals(3d, spreadsheet.calcCellValue(0, 2), 0);
        assertEquals(4d, spreadsheet.calcCellValue(1, 0), 0);
        assertEquals(5d, spreadsheet.calcCellValue(1, 1), 0);
        assertEquals(6d, spreadsheet.calcCellValue(1, 2), 0);
    }

    @Test
    public void testCalcConst() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("1 1", "1");
        double value = spreadsheet.calcCellValue(0, 0);
        assertEquals(1d, value, 0);
    }

    @Test
    public void testCalcOpPlus() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("1 1", "1 2 +");
        double value = spreadsheet.calcCellValue(0, 0);
        assertEquals(3d, value, 0);
    }

    @Test
    public void testFormulaConst() {
        Formula formula = new Formula("1");
        assertEquals(1d, formula.calc(), 0);
    }

    @Test
    public void testFormulaOpSimple() {
        assertEquals(3d, new Formula("1 2 +").calc(), 0);
        assertEquals(6d, new Formula("2 3 *").calc(), 0);
        assertEquals(1d, new Formula("4 3 -").calc(), 0);
        assertEquals(20d / 3, new Formula("20 3 /").calc(), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormulaInvalidOrder() {
        new Formula("1 + 2").calc();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormulaOpsWithoutOperands() {
        new Formula("+ +").calc();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormulaIncWithoutOperands() {
        new Formula("++").calc();
    }

    @Test
    public void testFormulaOpComplex() {
        Formula formula = new Formula("20 3 / 2 +");
        assertEquals(20d / 3 + 2, formula.calc(), 0);
    }

    @Test
    public void testFormulaNegativeConst() {
        Formula formula = new Formula("1 2 - -3 +");
        assertEquals(-4d, formula.calc(), 0);
    }

    @Test
    public void testFormulaIncrement() {
        Formula formula = new Formula("2 3 ++ +");
        assertEquals(6d, formula.calc(), 0);
    }

    @Test
    public void testFormulaDecrement() {
        Formula formula = new Formula("3 -- 5 + ++");
        assertEquals(8d, formula.calc(), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormulaInvalidRef() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("2 2", "1", "2", "3", "4");
        Formula formula = new Formula("C3");
        assertEquals(4d, formula.calc(spreadsheet), 0);
    }

    @Test
    public void testFormulaRef() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("2 2", "1", "2", "3", "4");
        assertEquals(1d, new Formula("A1").calc(spreadsheet), 0);
        assertEquals(2d, new Formula("A2").calc(spreadsheet), 0);
        assertEquals(3d, new Formula("B1").calc(spreadsheet), 0);
        assertEquals(4d, new Formula("B2").calc(spreadsheet), 0);
    }

    @Test
    public void testFormulaAll() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("2 2", "20", "10", "4", "3");
        Formula formula = new Formula("A1 B2 / 2 +");
        assertEquals(20d / 3 + 2, formula.calc(spreadsheet), 0);
    }

    @Test
    public void testSpreadsheetFormulaDependency() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("2 2", "A2", "20", "A1 B2 / 2 +", "3");
        assertEquals(20d / 3 + 2, spreadsheet.calcCellValue(1, 0), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormulaInvalid() {
        Formula formula = new Formula("1.0");
        formula.calc();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSpreadsheetFormulaCyclicDependency() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("2 2", "A2", "B2", "A1 B2 / 2 +", "A1");
        spreadsheet.calcCellValue(1, 0);
    }

    @Test
    public void testSpreadsheetFormulaCyclicDependencyDoubleRef() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("3 3", "A2", "A3 B2 +", "B3",
                                                              "C1 C2 +", "B1", "B2 C3 +",
                                                              "1", "C1", "C2 C1 +");
        spreadsheet.calcCellValue(0, 0);
    }

    @Test
    public void testSpreadsheetFormulaDoubleRef() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("2 2", "A2 B2 +", "B1 B2 +", "B2 B2 +", "1");
        assertEquals(4d, spreadsheet.calcCellValue(0, 0), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testReferenceToSelf() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("1 1", "A1");
        spreadsheet.calcCellValue(0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testReferenceToSelfIndirect() {
        Spreadsheet spreadsheet = buildTestSpreadsheet("2 1", "A2 A1");
        spreadsheet.calcCellValue(0, 0);
    }

}
