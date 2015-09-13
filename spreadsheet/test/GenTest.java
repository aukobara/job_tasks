import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;

public class GenTest {
    public final static int WIDTH = 100000;
    public final static int HEIGHT = 26;

    public static void main(String[] args) throws IOException {
        PrintWriter testFile = null;
        try {
            testFile = new PrintWriter(new BufferedWriter(new FileWriter("test.txt")));
            Random rand = new Random(System.currentTimeMillis());
            testFile.println(String.format("%d %d", WIDTH, HEIGHT));
            for (int r = 0; r < HEIGHT; r++) {
                for (int c = 0; c < WIDTH; c++) {
                    final int current = r*WIDTH + c;
                    if (rand.nextFloat() < 0.3 || current >= WIDTH*HEIGHT - 4 || current <= 3) {
                        testFile.println(Integer.toString(rand.nextInt()));
                    } else {
                        final StringBuilder formulaString = new StringBuilder(100);
                        final StringBuilder opString = new StringBuilder(100);

                        int from, to;
                        if (current % 2 == 0) {
                            // Forward links
                            from = current + 2;
                            to = WIDTH*HEIGHT;
                        } else {
                            // Backward links
                            from = 1;
                            to = current;
                        }
                        rand.ints(rand.nextInt(10)+1, 0, (to - from)/2 - 1).
                            map(n -> from + n*2).
                            forEach(n -> {
                                formulaString.append(Spreadsheet.getCellName(n/WIDTH, n%WIDTH)).append(' ');
                                opString.append(" +");
                            });
                        testFile.println(formulaString.toString() + opString.substring(0, opString.length()-2));
                    }
                }
            }
        } finally {
            if (testFile != null) {
                testFile.close();
            }
        }
    }
}
