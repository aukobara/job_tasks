import java.util.*;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

/**
 * Spreadsheet Formula representation.
 */
public class Formula {

    protected static final FormulaParser parser = FormulaParser.getInstance();

    private double value;
    private boolean valueSet;

    private Boolean hasDependencies;

    // Cached tokens
    private List<FormulaToken> tokens;
    // Cached dependencies to other formulas
    private Set<Formula> dependencies;

    // Next two fields are used internally during sorting phase only.
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
        if (tokens != null) {
            return String.join(" ", tokens.stream().map(FormulaToken::toString).toArray(String[]::new));
        } else {
            return "<unknown>";
        }
    }

    public boolean isCalculated() {
        return valueSet;
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

    public double calc(final Spreadsheet spreadsheet, boolean preCalcDependencies) {
        if(valueSet) {
            return value;
        }

        // Check this formula is not in another cell calculation
        assert !this.visited && this.dependentFrom == null;

        if (preCalcDependencies && hasDependencies()) {
            // This formula has dependencies on other spreadsheet's cells.
            // It is required to calculate them before in topological order to avoid deep recursion
            // and to check cyclic dependencies.
            calcDependencies(spreadsheet);
        }

        // Make stack of tokens to calculate RPN. Formula tokens must pop and recursively calculate
        // their sub-expressions from context.
        Deque<FormulaToken> context = new ArrayDeque<>(tokens);
        double value = context.pollLast().calc(context, spreadsheet);
        if (!context.isEmpty()) {
            throw new IllegalArgumentException("Formula has incorrect order or missed operator: " + this);
        }
        this.value = value;
        this.valueSet = true;
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
                if (dep.isCalculated() || !dep.hasDependencies()) {
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

        if (this.dependentFrom != null) {
            // Detected reference to top node from itself or one of child nodes
            throw new IllegalArgumentException("Cyclic dependency detected");
        }

        Deque<Formula> orderedFormulas = new LinkedList<>();
        Deque<Formula> upperFormulas = new ArrayDeque<>();
        upperFormulas.add(this);
        while (!upperFormulas.isEmpty()) {
            Formula next = upperFormulas.poll();
            orderedFormulas.addFirst(next);

            // Due to node has been promoted to sorted dependent nodes become next candidates.
            // Clear their references to promoted node.
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

        Deque<Formula> childFormulas = new ArrayDeque<>();
        childFormulas.add(this);
        while (!childFormulas.isEmpty()) {
            Formula next = childFormulas.poll();
            if (next.dependentFrom != null) {
                throw new IllegalArgumentException("Cyclic dependency detected");
            }
            next.getDependencies(spreadsheet).stream().
                    filter(f -> !f.visited && !f.isCalculated() && f.hasDependencies()).
                    forEach(f -> {
                        childFormulas.add(f);
                        f.visited = true;
                    });
        }

        // Now everything is ready to calculate all nodes in topological order.
        // Nested calculations should not calculate their dependencies (preCalcDependencies=false)
        // because whole tree will be calculated here.
        orderedFormulas.forEach(f -> f.calc(spreadsheet, false));
    }

    public boolean hasDependencies() {
        if (this.hasDependencies == null) {
            this.hasDependencies = this.tokens.stream().anyMatch(token -> token instanceof ReferenceToken);
        }
        return hasDependencies;
    }

    public Set<Formula> getDependencies(Spreadsheet spreadsheet) {
        if (this.dependencies == null) {
            if (hasDependencies()) {
                this.dependencies = tokens.stream().filter(token -> token instanceof ReferenceToken).
                        map(token -> ((ReferenceToken) token).getReferencedFormula(spreadsheet)).
                        filter(token -> token != null).
                        collect(Collectors.toSet());
            }
        }
        return this.dependencies;
    }

    private List<FormulaToken> parse(String original) {
        if (this.tokens == null) {
            this.tokens = parser.parseTokens(original);
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

    public FormulaToken newToken(CharSequence formulaString, int startPos, int endPos) {
        FormulaToken token;
        try {
            token = this.getClass().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        token.setTokenString(formulaString, startPos, endPos);
        return token;
    }

    /**
     * Implementations should parse string to internal binary format and don't keep formulaString.
     *
     * @param formulaString whole formula that contains this token
     * @param startPos token start position in formula (inclusive)
     * @param endPos token end position in formula (exclusive)
     */
    protected abstract void setTokenString(CharSequence formulaString, int startPos, int endPos);

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
    protected void setTokenString(CharSequence formulaString, int startPos, int endPos) {
        value = Formula.parser.parseInt(formulaString, startPos, endPos);
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

class IncToken extends FormulaToken {
    static final FormulaToken prototype = new IncToken();
    private boolean negative;

    @Override
    protected void setTokenString(CharSequence formulaString, int startPos, int endPos) {
        negative = formulaString.charAt(startPos) == '-';
    }

    @Override
    public String toString() {
        return negative? "--": "++";
    }

    @Override
    public double calc(Deque<FormulaToken> context, Spreadsheet spreadsheet) {
        if (context.size() < 1) {
            throw new IllegalArgumentException("Insufficient number of arguments for operator: " + toString());
        }
        return context.pollLast().calc(context, spreadsheet) + (negative? -1: 1);
    }
}

class BinaryOpToken extends FormulaToken {
    static final FormulaToken prototype = new BinaryOpToken();

    private enum OPERATOR {
        PLUS('+', (left, right) -> left + right),
        MINUS('-', (left, right) -> left - right),
        MULTIPLY('*', (left, right) -> left * right),
        DIVIDE('/', (left, right) -> left / right);

        private static Map<Character, OPERATOR> opMap = new HashMap<>();

        static {
            opMap.put(PLUS.opChar, PLUS);
            opMap.put(MINUS.opChar, MINUS);
            opMap.put(MULTIPLY.opChar, MULTIPLY);
            opMap.put(DIVIDE.opChar, DIVIDE);
        }

        public static OPERATOR parse(char opChar) {
            OPERATOR op = opMap.get(opChar);
            if (op == null) {
                throw new IllegalArgumentException("Invalid operator: " + opChar);
            }
            return op;
        }

        private final Character opChar;
        private final DoubleBinaryOperator opFunc;

        OPERATOR(Character opChar, DoubleBinaryOperator opFunc) {
            this.opChar = opChar;
            this.opFunc = opFunc;
        }

        @Override
        public String toString() {
            return "" + opChar;
        }

        public double calc(double left, double right) {
            return opFunc.applyAsDouble(left, right);
        }
    }

    private OPERATOR op;

    @Override
    protected void setTokenString(CharSequence formulaString, int startPos, int endPos) {
        op = OPERATOR.parse(formulaString.charAt(startPos));
    }

    @Override
    public double calc(Deque<FormulaToken> context, Spreadsheet spreadsheet) {
        if (context.size() < 2) {
            throw new IllegalArgumentException("Insufficient number of arguments for operator: " + toString());
        }
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
    protected void setTokenString(CharSequence formulaString, int startPos, int endPos) {
        refColumn = Formula.parser.parseInt(formulaString, startPos, endPos) - 1;
        refRow = formulaString.charAt(startPos) - 'A';
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
