import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attempt to write fast parser without regexp
 */
public interface FormulaParser {

     FormulaToken[] prototypes = {
            ConstToken.prototype, BinaryOpToken.prototype, ReferenceToken.prototype, IncToken.prototype};

    List<FormulaToken> parseTokens(String formulaString) throws InvalidFormulaException;

    int parseInt(CharSequence tokenBuf, int from, int to);

    static FormulaParser getInstance() {
//        return new RegexpFormulaParser();
        return new FastFormulaParser();
    }
}

abstract class AbstractFormulaParser implements FormulaParser {
    public int parseInt(CharSequence tokenBuf, int from, int to) {
        final int[] pow = {1, 10, 100, 1000, 10000, 100000};
        int result = 0;
        for (int i = to - 1; i >= from; i--) {
            char c = tokenBuf.charAt(i);
            if (c == '-') {
                result = -result;
            } else if (c >= '0' && c <= '9') {
                result += (c - '0') * pow[to - i - 1];
            }
        }
        return result;
    }
}

class FastFormulaParser extends AbstractFormulaParser {
    private static final int TOKEN_NUMBER = 0;
    private static final int TOKEN_OP = 1;
    private static final int TOKEN_REF = 2;
    private static final int TOKEN_INCREMENT = 3;
    private static final int TOKEN_SEPARATOR = 99;

    public List<FormulaToken> parseTokens(String formulaString) throws InvalidFormulaException {
        List<FormulaToken> result = new ArrayList<>();
        int currentToken = TOKEN_SEPARATOR;
        int len = formulaString.length();
        int tokenStartPos = 0, tokenLength = 0;
        for (int i = 0; i < len; i++) {
            char c = formulaString.charAt(i);
            int nextCharType;
            if (c == ' ') {
                nextCharType = TOKEN_SEPARATOR;
            } else if (c >= 'A' && c <= 'Z') {
                nextCharType = TOKEN_REF;
                tokenLength++;
            } else if (c >= '0' && c <= '9') {
                nextCharType = TOKEN_NUMBER;
                tokenLength++;
            } else if (c == '+' || c == '-' || c == '*' || c == '/') {
                nextCharType = TOKEN_OP;
                tokenLength++;
            } else {
                throw new InvalidFormulaException(formulaString);
            }

            if (nextCharType != TOKEN_SEPARATOR) {
                switch (currentToken) {
                    case TOKEN_SEPARATOR:
                        // New token started
                        if (nextCharType == TOKEN_OP && i < len-1) {
                            // Look forward for negative numbers and inc/dec
                            char next2Char = formulaString.charAt(i+1);
                            if (next2Char >= '0' && next2Char <= '9') {
                                currentToken = TOKEN_NUMBER;
                            } else {
                                currentToken = TOKEN_OP;
                            }
                        } else {
                            currentToken = nextCharType;
                        }
                        break;

                    case TOKEN_NUMBER:
                        // Only digits are acceptable in this mode
                        if (nextCharType != TOKEN_NUMBER) {
                            throw new InvalidFormulaException(formulaString);
                        }
                        break;

                    case TOKEN_OP:
                        if (nextCharType != TOKEN_OP || tokenLength > 2) {
                            // Operator is single character only except increment and decrement
                            throw new InvalidFormulaException(formulaString);
                        }
                        break;

                    case TOKEN_REF:
                        // All characters after first letter must be digits
                        if (nextCharType != TOKEN_NUMBER) {
                            throw new InvalidFormulaException(formulaString);
                        }
                        break;
                }
            }

            if (nextCharType == TOKEN_SEPARATOR || i == len-1) {
                if (currentToken != TOKEN_SEPARATOR) {
                    // End of token. Finalize and prepare to next one.

                    // Validation of full token
                    switch (currentToken) {
                        case TOKEN_REF:
                            if (tokenLength < 2) {
                                // Cell reference must be of at least one letter and one digit
                                throw new InvalidFormulaException(formulaString);
                            }
                            break;

                        case TOKEN_OP:
                            if (tokenLength == 2) {
                                // Validate inc/dec
                                char char1 = formulaString.charAt(tokenStartPos),
                                        char2 = formulaString.charAt(tokenStartPos+1);
                                if (char1 != char2 || (char1 != '+' && char1 != '-')) {
                                    // Only accept '++' and '--'
                                    throw new InvalidFormulaException(formulaString);
                                }
                                currentToken = TOKEN_INCREMENT;
                            }
                            break;
                    }

                    result.add(prototypes[currentToken].newToken(formulaString, tokenStartPos, tokenStartPos+tokenLength));
                    currentToken = TOKEN_SEPARATOR;
                }
                tokenStartPos = i+1;
                tokenLength = 0;
            }
        }

        if (result.isEmpty()) {
            throw new InvalidFormulaException(formulaString);
        }

        return result;
    }
}

class RegexpFormulaParser extends AbstractFormulaParser {
    private static final Pattern REGEXP_TOKENS =
            Pattern.compile("([-]?\\d+)|([-+*/])(?=\\s|$)|([A-Z]\\d+)|([+][+]|[-][-])|\\s+");

    @Override
    public List<FormulaToken> parseTokens(String formulaString) throws InvalidFormulaException {
        List<FormulaToken> tokens = new ArrayList<>();
        Matcher m = REGEXP_TOKENS.matcher(formulaString);
        int prevPos = 0;
        while (m.find()) {
            int currentPos = m.start();
            if (currentPos > prevPos) {
                // Some unknown characters detected which are not described in regexp.
                throw new InvalidFormulaException(formulaString);
            }
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) {
                    FormulaToken token = prototypes[i - 1].newToken(formulaString, m.start(i), m.end(i));
                    tokens.add(token);
                    break;
                }
            }
            prevPos = m.end();
        }
        if (tokens.isEmpty()) {
            throw new InvalidFormulaException(formulaString);
        }
        return tokens;
    }
}
class InvalidFormulaException extends IllegalArgumentException {
    public InvalidFormulaException(String formulaString) {
        super("Invalid formula: " + formulaString);
    }
}

