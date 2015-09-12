import java.util.*;
import java.util.function.DoubleBinaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spreadsheet Formula representation.
 */
public class Formula {
    private static final Pattern REGEXP_TOKENS = Pattern.compile("(?<const>\\d+)|(?<op>[-+*/])|(?<ref>[A-Z]\\d+)|\\s+");
    private static final FormulaToken[] tokenPrototypes = {ConstToken.prototype, BinaryOpToken.prototype, ReferenceToken.prototype};

    private Double value;

    // Cached tokens
    private Deque<FormulaToken> tokens;
    private Set<Formula> dependencies;

    private Set<Formula> dependentFrom;

    private boolean visited = false;

    public Formula(String original) {
        parse(original);
    }

    @Override
    public String toString() {
        return getText();
    }

    public String getText() {
        return String.join(" ", tokens.stream().map(FormulaToken::toString).toArray(String[]::new));
    }

    public boolean isCalculated() {
        return value != null;
    }

    public boolean hasDependencies(Spreadsheet spreadsheet) {
        return !getDependencies(spreadsheet).isEmpty();
    }

    /**
     * Independent formula without spreadsheet.
     */
    public double calc() {
        return calc(null);
    }

    public double calc(Spreadsheet spreadsheet) {
        return calc(spreadsheet, true);
    }

    public double calc(final Spreadsheet spreadsheet, boolean precalcDependencies) {
        if (value == null) {
            if (precalcDependencies && hasDependencies(spreadsheet)) {
                // This formula has dependencies on other spreadsheet's cells.
                // It is required to calculate them before in topological order to avoid deep recursion
                // and to check cyclic dependencies.
                calcDependencies(spreadsheet);
            }

            // Make stack of tokens to calculate RPN. Formula tokens must pop and recursively calculate
            // their sub-expressions from context.
            Deque<FormulaToken> context = new ArrayDeque<>(tokens);
            value = context.pollLast().calc(context, spreadsheet);
            if (!context.isEmpty()) {
                throw new IllegalArgumentException("Formula has incorrect order or missed operator: " + this);
            }
        }
        return value;
    }

    /**
     * This method implements Direct Acyclic Graph topological sort simple algorithm.
     * First, it requires to build reverse edges from nodes to their predecessors.
     * Field dependentFrom keeps set of such edges for each node under current one (recursively).
     * THIS FIELD IS TEMPORARY AND NOT THREAD-SAFE. It must be cleared after sorting.
     * If one of dependencies still has references after sorting such situation considered
     * as cyclic dependency and exception is raised.
     */
    private void calcDependencies(Spreadsheet spreadsheet) {
        Deque<Formula> formulas = new ArrayDeque<>();
        formulas.add(this);
        while (!formulas.isEmpty()) {
            Formula next = formulas.pollFirst();
            next.visited = true;
            Set<Formula> dependencies = next.getDependencies(spreadsheet);
            for (Formula dep : dependencies) {
                if (dep.isCalculated() || !dep.hasDependencies(spreadsheet)) {
                    // Simple node. Skip.
                    continue;
                }
                Set<Formula> depSet = dep.dependentFrom;
                if (depSet == null) {
                    depSet = new HashSet<>();
                    dep.dependentFrom = depSet;
                }
                depSet.add(next);
                if (dep.visited) {
                    continue;
                }
                formulas.addLast(dep);
            }
        }

        Deque<Formula> orderedFormulas = new LinkedList<>();
        Deque<Formula> upperFormulas = new ArrayDeque<>();
        upperFormulas.add(this);
        while (!upperFormulas.isEmpty()) {
            Formula next = upperFormulas.poll();
            orderedFormulas.addFirst(next);

            // Due to node has been promoted to sorted dependent nodes become next candidates.
            // Cleat their references to promoted node.
            Set<Formula> dependencies = next.getDependencies(spreadsheet);
            for (Formula dep : dependencies) {
                dep.visited = false;
                Set<Formula> depSet = dep.dependentFrom;
                if (depSet != null) {
                    depSet.remove(next);
                    if (depSet.isEmpty()) {
                        dep.dependentFrom = null;
                        upperFormulas.add(dep);
                    }
                }
            }
        }
        // Do not pre-calculate current node - it will be fully calculated in own caller method.
        orderedFormulas.pollLast().visited = false;

        formulas = new ArrayDeque<>();
        formulas.add(this);
        while (!formulas.isEmpty()) {
            Formula next = formulas.poll();
            if (next.dependentFrom != null) {
                throw new IllegalArgumentException("Cyclic dependency detected");
            }
            if (next.isCalculated() || !next.hasDependencies(spreadsheet)) {
                continue;
            }
            formulas.addAll(next.getDependencies(spreadsheet));
        }

        // Now everything is ready to calculate all nodes in topological order.
        // Nested calculations should not calculate their dependencies (precalcDependencies=false)
        // because whole tree will be calculated here.
        orderedFormulas.forEach(f -> f.calc(spreadsheet, false));
    }

    public Set<Formula> getDependencies(Spreadsheet spreadsheet) {
        if (this.dependencies == null) {
            this.dependencies = tokens.stream().filter(token -> token instanceof ReferenceToken).
                    map(token -> ((ReferenceToken) token).getReferencedFormula(spreadsheet)).
                    collect(Collectors.toSet());
        }
        return this.dependencies;
    }

    private Deque<FormulaToken> parse(String original) {
        if (this.tokens == null) {
            Deque<FormulaToken> tokens = new ArrayDeque<>();
            Matcher m = REGEXP_TOKENS.matcher(original);
            int prevPos = 0;
            while (m.find()) {
                int currentPos = m.start();
                if (currentPos > prevPos) {
                    // Some unknown characters detected which are not described in regexp.
                    throw new IllegalArgumentException("Invalid formula: " + original);
                }
                for (int i = 1; i <= m.groupCount(); i++) {
                    if (m.group(i) != null) {
                        FormulaToken token = tokenPrototypes[i - 1].newToken(m.group(i));
                        tokens.add(token);
                        break;
                    }
                }
                prevPos = m.end();
            }
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("Invalid formula: " + original);
            }
            this.tokens = tokens;
        }
        return this.tokens;
    }
}

/**
 * Class represents separate components of formula expression, such as numbers, operators and references.
 * All implementations must be memory-lean as much as possible and keep translated (binary) information only,
 * that is sufficient for calculations.
 */
abstract class FormulaToken {

    public FormulaToken newToken(String tokenString) {
        FormulaToken token;
        try {
            token = this.getClass().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        token.setTokenString(tokenString);
        return token;
    }

    /**
     * Implementations should parse string to internal binary format and release tokenString.
     *
     * @param tokenString expression component
     */
    protected abstract void setTokenString(String tokenString);

    /**
     * Calculate single expression component using expression context with next parts.
     * According to RPN all operands should be available in tail of context (right to left).
     *
     * @param context     remaining expression components on left side from current.
     * @param spreadsheet where cell with expression is located. May be used to resolve references to other cells.
     * @return value
     */
    public abstract double calc(Deque<FormulaToken> context, Spreadsheet spreadsheet);

    @Override
    public abstract String toString();
}

class ConstToken extends FormulaToken {
    static final FormulaToken prototype = new ConstToken();

    private int value;

    @Override
    protected void setTokenString(String tokenString) {
        value = Integer.parseInt(tokenString);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }

    @Override
    public double calc(Deque<FormulaToken> context, Spreadsheet spreadsheet) {
        return value;
    }
}

class BinaryOpToken extends FormulaToken {
    static final FormulaToken prototype = new BinaryOpToken();

    private enum OPERATOR {
        PLUS("+", (left, right) -> left + right),
        MINUS("-", (left, right) -> left - right),
        MULTIPLY("*", (left, right) -> left * right),
        DIVIDE("/", (left, right) -> left / right);

        private static Map<String, OPERATOR> opMap = new HashMap<>();

        static {
            opMap.put(PLUS.opString, PLUS);
            opMap.put(MINUS.opString, MINUS);
            opMap.put(MULTIPLY.opString, MULTIPLY);
            opMap.put(DIVIDE.opString, DIVIDE);
        }

        public static OPERATOR parse(String opString) {
            OPERATOR op = opMap.get(opString);
            if (op == null) {
                throw new IllegalArgumentException("Invalid operator: " + opString);
            }
            return op;
        }

        private final String opString;
        private final DoubleBinaryOperator opFunc;

        OPERATOR(String opString, DoubleBinaryOperator opFunc) {
            this.opString = opString;
            this.opFunc = opFunc;
        }

        @Override
        public String toString() {
            return opString;
        }

        public double calc(double left, double right) {
            return opFunc.applyAsDouble(left, right);
        }
    }

    private OPERATOR op;

    @Override
    protected void setTokenString(String tokenString) {
        op = OPERATOR.parse(tokenString);
    }

    @Override
    public double calc(Deque<FormulaToken> context, Spreadsheet spreadsheet) {
        double right = context.pollLast().calc(context, spreadsheet);
        double left = context.pollLast().calc(context, spreadsheet);
        return op.calc(left, right);
    }

    @Override
    public String toString() {
        return op.toString();
    }
}

class ReferenceToken extends FormulaToken {
    static final FormulaToken prototype = new ReferenceToken();
    private int refColumn = -1, refRow = -1;

    @Override
    protected void setTokenString(String tokenString) {
        refColumn = Integer.parseInt(tokenString.substring(1)) - 1;
        refRow = tokenString.charAt(0) - 'A';
    }

    @Override
    public String toString() {
        return Spreadsheet.getCellName(refRow, refColumn);
    }

    @Override
    public double calc(Deque<FormulaToken> context, Spreadsheet spreadsheet) {
        assert spreadsheet != null;
        validateRef(spreadsheet);
        return spreadsheet.calcCellValue(refRow, refColumn);
    }

    private void validateRef(Spreadsheet spreadsheet) {
        if (refColumn >= spreadsheet.getWidth() || refRow >= spreadsheet.getHeight()) {
            throw new IllegalArgumentException("Invalid cell reference: " + toString());
        }
    }

    public Formula getReferencedFormula(Spreadsheet spreadsheet) {
        validateRef(spreadsheet);
        return spreadsheet.getCellFormula(refRow, refColumn);
    }
}
