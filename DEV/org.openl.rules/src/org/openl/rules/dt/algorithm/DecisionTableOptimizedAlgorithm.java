/**
 * Created Jul 8, 2007
 */
package org.openl.rules.dt.algorithm;

import java.util.*;

import org.openl.binding.BindingDependencies;
import org.openl.binding.IBindingContext;
import org.openl.binding.impl.cast.IOpenCast;
import org.openl.domain.IDomain;
import org.openl.domain.IIntIterator;
import org.openl.domain.IIntSelector;
import org.openl.rules.binding.RulesBindingDependencies;
import org.openl.rules.dt.DecisionTable;
import org.openl.rules.dt.DecisionTableRuleNode;
import org.openl.rules.dt.IBaseCondition;
import org.openl.rules.dt.algorithm.evaluator.*;
import org.openl.rules.dt.data.ConditionOrActionParameterField;
import org.openl.rules.dt.element.ICondition;
import org.openl.rules.dt.index.IRuleIndex;
import org.openl.rules.dt.type.*;
import org.openl.rules.helpers.DateRange;
import org.openl.rules.helpers.NumberUtils;
import org.openl.rules.helpers.StringRange;
import org.openl.source.IOpenSourceCodeModule;
import org.openl.syntax.exception.SyntaxNodeException;
import org.openl.syntax.exception.SyntaxNodeExceptionUtils;
import org.openl.types.IAggregateInfo;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenField;
import org.openl.types.IParameterDeclaration;
import org.openl.types.java.JavaOpenClass;
import org.openl.util.ClassUtils;
import org.openl.util.StringUtils;
import org.openl.vm.IRuntimeEnv;
import org.openl.vm.Tracer;

/**
 * The basic algorithm for decision table (DT) evaluation is straightforward (let's consider table with conditions and
 * actions as columns and rules as rows - you remember that OpenL Tablets allow both this and transposed orientation):
 *
 * <ol>
 * <li>For each rule (row) from the top to the bottom of the table evaluate conditions from the left to the right</li>
 * <li>If all conditions are true, execute all the actions in the rule from the left to the right, if any condition is
 * false stop evaluating conditions, go to the next rule</li>
 * <li>If the action is non-empty return action then return the value of the action (stops the evaluation)</li>
 * <li>If no rules left then return <code>null</code></li>
 * </ol>
 * The logic of the algorithm must be kept intact in all optimization implementations, unless some permutations are
 * explicitly allowed.
 *
 * <p>
 * The goal of optimizations is to decrease the number of condition checking required to determine which rule actions
 * need to be executed. Action optimizations are not considered, even though some improvements could be possible.
 * Sometimes the best results may be achieved by changing the order of conditions(columns) and rules(rows) but these
 * approaches will change the algorithm's logic and must be used with caution. We are not going to implement these
 * approaches at this point, but will give some guidelines to users on how to re-arrange rule tables to achieve better
 * performance.
 *
 * <p>
 * Out of the class of optimization algorithms that do not change the order of conditions or rules we are going to
 * consider ones that optimize condition checking in one condition (column) at the time. In decision table with multiple
 * conditions the algorithm will create a tree of the optimized nodes. The time of the of the tree traversing will equal
 * the sum of times required to calculate each node.
 *
 * <p>
 * The optimization algorithms that deal with single condition can be classified as follows:
 * <li>Condition Sharing algorithm.<br>
 * Merge all the rows that share the same condition data into one node. Then calculate condition only once for all rules
 * that share the same condition. The algorithm can be extended to achieve even better results if we allow the
 * pseudo-data keyword <i>else</i>. The advantage of this algorithm is in it's universal applicability - it does not
 * depend on the nature of condition expression. The disadvantage is in it's low performance improvement - it's expected
 * average performance is n/s - where n is the total number of conditions and s = n/u where u is the number of unique
 * conditions. We see that the more unique conditions the column has, the less is performance advantage of this method.
 *
 * <p>
 * <li>Indexing<br>
 * Calculate condition input value and determine which rules to fire using some kind of index. The performance will be
 * determined by the index speed. For example, if index is implemented as a HashMap (for equality checks) the
 * performance is expected to be constant, if as a TreeMap - log (u), where u is number of unique conditions.
 * Interestingly, for indexing algorithms, the more is the number of shared conditions or empty conditions, the less is
 * performance improvement - quite the opposite to the Condition Sharing algorithm.<br>
 *
 * <h3>Applicability of the Algorithms</h3>
 *
 * <p>
 * Both algorithms are single-condition(column) based. Both work only if rule condition expression does not change it's
 * value during the course of DT evaluation. In other words, the Decision Table rules do not change attributes that
 * participate in condition evaluation. For indexing the additional requirement is necessary - the index value should be
 * known at compile time. It excludes conditions with dynamic formulas as cell values.
 *
 * <p>
 * If a Decision Table does not conform to these assumptions, you should not use optimizations. It is also recommended
 * that you take another look at your design and ask yourself: was it really necessary to produce such a twisted
 * logic?</span>
 *
 *
 * <h3>Explicit Indexing Optimization</h3>
 * <p>
 * Generally speaking, it would be nice to have system automatically apply optimizations, when appropriate, would not
 * it? In reality there are always the cases where one does not want optimization happen for some reason, for example
 * condition calls a function with side effects.. This would require us to provide some facility to suppress
 * optimization on column and/or table level, and this might unnecessary complicate DT structure.
 *
 * <p>
 * Fortunately, we were lucky to come up with an approach that gives the developer an explicit control over (indexing)
 * optimization at the same time <i>reducing</i> the total amount of the code in the condition. To understand the
 * approach, one needs to relize that<br/>
 * a) indexing is possible only for some very well defined operations, like equality or range checks<br/>
 * and<br/>
 * b) the index has to be calculated in advance
 *
 * <p>
 * For example we have condition
 * <p/>
 * <code>driver.type == type</code>
 * <p/>
 *
 * where <code>driver.type</code> is tested value and <code>type</code> is rule condition parameter against which the
 * tested value is being checked. We could parse the code and figure it out automatically - but we decided to simplify
 * our task by putting a bit more responsibility (and control too) into developer's hands. If the condition expression
 * will be just
 * <p/>
 * <code>driver.type</code>
 * <p/>
 * the parser will easily recognize that it does not depend on rule parameters at all. This can be used as the hint that
 * the condition needs to be optimized. The structure of parameters will determine the type of index using this table:
 *
 * <table border="1">
 * <tbody>
 * <tr>
 * <th>Expr Type</th>
 * <th>N Params</th>
 * <th>Param Type</th>
 * <th>Condition</th>
 * <th>Index Performance</th>
 * </tr>
 * <tr>
 * <td>any T x</td>
 * <td align="center">1</td>
 * <td>T value</td>
 * <td><code>x == value</code></td>
 * <td>Constant(HashMap) performance</td>
 * </tr>
 * <tr>
 * <td>any T x</td>
 * <td align="center">1</td>
 * <td>T[] ary</td>
 * <td><code>contains(ary, x)</code></td>
 * <td>Constant(HashMap) performance</td>
 * </tr>
 * <tr>
 * <td>Comparable T x</td>
 * <td align="center">2</td>
 * <td>T min, T max</td>
 * <td><code>min &lt;= x &&amp; x &lt;  max</code></td>
 * <td><code>log(n)</code>(TreeMap) performance</td>
 * </tr>
 * <tr>
 * <td>any T x</td>
 * <td align="center">2</td>
 * <td>&lt;in|not in&gt; Enum isIn, T[] ary</td>
 * <td><code>isIn == in ? contains(ary, x) : !contains(ary, x) </code></td>
 * <td>Constant(HashMap) performance</td>
 * </tr>
 *
 * </tbody>
 * </table>
 *
 * The OpenL Tablets Decision Table Optimizer will automatically recognize these conditions and create indexes for
 * them.<br/>
 * The advantages of the suggested approach are summarized here:
 *
 * <ul>
 * <li>there is less code to type and therefore less to read, less places to make typos etc.</li>
 * <li>there is less to compile and parse, the less work for the compiler or optimizer to determine programmer's
 * intentions == better performance and less errors
 * <li>optimization algorithm is easy to turn on or off for any condition, it is easy to predict what kind of
 * optimization will be used for particular kind of data, there would not be any "black box magic". Eventually we are
 * going not only publish all the optimization algorithms, but also provide formula-based estimates for their expected
 * performance. This will provide the basis for static compile-time performance analysis</li>
 * </ul>
 * <p>
 *
 * @author sshor
 */
public class DecisionTableOptimizedAlgorithm implements IDecisionTableAlgorithm {

    private static final int MAX_INDEXED_ARRAY_SIZE = 1;

    /**
     * There is one evaluator per condition in DT
     */
    private final ConditionToEvaluatorHolder[] evaluators;
    private final IRuleIndex indexRoot;
    private final IndexInfo info;
    private BindingDependencies dependencies;

    DecisionTableOptimizedAlgorithm(IConditionEvaluator[] evaluators, DecisionTable table, IndexInfo info) {
        this.evaluators = initEvaluators(evaluators, table, info);
        this.info = info;
        this.indexRoot = buildIndex(info);
        this.dependencies = new RulesBindingDependencies();
        table.updateDependency(dependencies);
    }

    static IRangeAdaptor<? extends Object, ? extends Comparable<?>> getRangeAdaptor(IOpenClass methodType,
            IOpenClass paramType) {
        if (NumberUtils.isNonFloatPointType(methodType.getInstanceClass()) && isIntRangeType(paramType)) {
            return IntRangeAdaptor.getInstance();
        }
        if (NumberUtils.isFloatPointType(methodType.getInstanceClass()) && isIntRangeType(paramType)) {
            return DoubleRangeForIntRangeAdaptor.getInstance();
        }
        if (NumberUtils.isNumberType(methodType.getInstanceClass()) && isDoubleRangeType(paramType)) {
            return DoubleRangeAdaptor.getInstance();
        }
        if (isCharType(methodType) && isCharRangeType(paramType)) {
            return CharRangeAdaptor.getInstance();
        }
        if (isDateType(methodType) && isDateRangeType(paramType)) {
            return DateRangeAdaptor.getInstance();
        }
        if (isStringType(methodType) && isStringRangeType(paramType)) {
            return StringRangeAdaptor.getInstance();
        }
        return null;
    }

    private static boolean isDoubleRangeType(IOpenClass type) {
        return org.openl.rules.helpers.DoubleRange.class.equals(type.getInstanceClass());
    }

    private static boolean isIntRangeType(IOpenClass type) {
        return org.openl.rules.helpers.IntRange.class.equals(type.getInstanceClass());
    }

    private static boolean isCharRangeType(IOpenClass type) {
        return org.openl.rules.helpers.CharRange.class.equals(type.getInstanceClass());
    }

    private static boolean isStringRangeType(IOpenClass type) {
        return StringRange.class.equals(type.getInstanceClass());
    }

    private static boolean isDateRangeType(IOpenClass type) {
        return DateRange.class.equals(type.getInstanceClass());
    }

    private static boolean isCharType(IOpenClass type) {
        return ClassUtils.isAssignable(type.getInstanceClass(), Character.class);
    }

    private static boolean isStringType(IOpenClass type) {
        return ClassUtils.isAssignable(type.getInstanceClass(), CharSequence.class);
    }

    private static boolean isDateType(IOpenClass type) {
        return ClassUtils.isAssignable(type.getInstanceClass(), Date.class);
    }

    // TODO to do - fix _NO_PARAM_ issue
    @SuppressWarnings("unchecked")
    public static IConditionEvaluator makeEvaluator(ICondition condition,
            IOpenClass methodType,
            IBindingContext bindingContext) throws SyntaxNodeException {

        if (condition.hasFormulas()) {
            return new DefaultConditionEvaluator();
        }

        IParameterDeclaration[] params = condition.getParams();

        if (params.length == 1) {
            IOpenClass paramType = params[0].getType();

            IOpenCast paramToExpressionOpenCast = bindingContext.getCast(paramType, methodType);
            paramToExpressionOpenCast = paramToExpressionOpenCast != null && paramToExpressionOpenCast
                .isImplicit() ? paramToExpressionOpenCast : null;

            IOpenCast methodParameterToParamOpenCast = bindingContext.getCast(methodType, paramType);
            methodParameterToParamOpenCast = methodParameterToParamOpenCast != null && methodParameterToParamOpenCast
                .isImplicit() ? methodParameterToParamOpenCast : null;

            if (paramToExpressionOpenCast != null) {
                return condition.getNumberOfEmptyRules(0) > 1
                                                              ? new EqualsIndexedEvaluatorV2(paramToExpressionOpenCast,
                                                                  methodParameterToParamOpenCast)
                                                              : new EqualsIndexedEvaluator(paramToExpressionOpenCast,
                                                                  methodParameterToParamOpenCast);
            }

            IAggregateInfo aggregateInfo = paramType.getAggregateInfo();

            if (aggregateInfo.isAggregate(paramType) && aggregateInfo.getComponentType(paramType)
                .isAssignableFrom(methodType)) {
                return condition.getNumberOfEmptyRules(0) > 1
                                                              ? new ContainsInArrayIndexedEvaluatorV2(
                                                                  paramToExpressionOpenCast,
                                                                  methodParameterToParamOpenCast)
                                                              : new ContainsInArrayIndexedEvaluator(
                                                                  paramToExpressionOpenCast,
                                                                  methodParameterToParamOpenCast);
            }

            IRangeAdaptor<? extends Object, ? extends Comparable<?>> rangeAdaptor = getRangeAdaptor(methodType,
                paramType);

            if (rangeAdaptor != null) {
                return new CombinedRangeIndexEvaluator(
                    (IRangeAdaptor<Object, ? extends Comparable<Object>>) rangeAdaptor,
                    1,
                    methodParameterToParamOpenCast);
            }

            if (methodParameterToParamOpenCast != null) {
                return condition.getNumberOfEmptyRules(0) > 1
                                                              ? new EqualsIndexedEvaluatorV2(paramToExpressionOpenCast,
                                                                  methodParameterToParamOpenCast)
                                                              : new EqualsIndexedEvaluator(paramToExpressionOpenCast,
                                                                  methodParameterToParamOpenCast);
            }

            if (JavaOpenClass.BOOLEAN.equals(methodType) || JavaOpenClass.getOpenClass(Boolean.class)
                .equals(methodType)) {
                return new DefaultConditionEvaluator();
            }

        } else if (params.length == 2) {
            IOpenClass paramType0 = params[0].getType();
            IOpenClass paramType1 = params[1].getType();

            IOpenCast methodParameterToParamOpenCast = bindingContext.getCast(methodType, paramType0);
            methodParameterToParamOpenCast = methodParameterToParamOpenCast != null && methodParameterToParamOpenCast
                .isImplicit() ? methodParameterToParamOpenCast : null;

            if (methodParameterToParamOpenCast != null && Objects.equals(paramType0, paramType1)) {
                Class<?> clazz = methodType.getInstanceClass();
                if (clazz == byte.class || clazz == short.class || clazz == int.class || clazz == long.class || clazz == float.class || clazz == double.class || Comparable.class
                    .isAssignableFrom(clazz)) {
                    return new CombinedRangeIndexEvaluator(null, 2, methodParameterToParamOpenCast);
                }
            }

            IAggregateInfo aggregateInfo = paramType1.getAggregateInfo();
            if (aggregateInfo.isAggregate(paramType1) && aggregateInfo.getComponentType(paramType1) == methodType) {
                BooleanTypeAdaptor booleanTypeAdaptor = BooleanAdaptorFactory.getAdaptor(paramType0);

                if (booleanTypeAdaptor != null) {
                    return new ContainsInOrNotInArrayIndexedEvaluator(booleanTypeAdaptor);
                }
            }

            if (JavaOpenClass.BOOLEAN.equals(methodType) || JavaOpenClass.getOpenClass(Boolean.class)
                .equals(methodType)) {
                return new DefaultConditionEvaluator();
            }
        }

        List<String> names = new ArrayList<>();
        for (IParameterDeclaration parameterDeclaration : params) {
            String name = parameterDeclaration.getType().getName();
            names.add(name);
        }

        String message = String.format(
            "Cannot build an evaluator for condition '%s' with parameters '%s' and method parameter '%s'.",
            condition.getName(),
            StringUtils.join(names, ", "),
            methodType.getName());

        throw SyntaxNodeExceptionUtils.createError(message, null, null, condition.getUserDefinedExpressionSource());
    }

    private ConditionToEvaluatorHolder[] initEvaluators(IConditionEvaluator[] evaluators,
            DecisionTable table,
            IndexInfo info) {
        if (table.getNumberOfConditions() <= info.fromCondition || info.fromCondition > info.toCondition) {
            return new ConditionToEvaluatorHolder[0];
        } else {
            List<ConditionToEvaluatorHolder> evalToConds = new ArrayList<>(evaluators.length);
            for (int j = info.fromCondition; j <= info.toCondition; j++) {
                IConditionEvaluator eval = evaluators[j];
                ICondition condition = table.getCondition(j);
                if (eval instanceof AContainsInArrayIndexedEvaluator) {
                    int maxArrayLength = ((AContainsInArrayIndexedEvaluator) eval).getMaxArrayLength(condition,
                        info.makeRuleIterator());
                    if (maxArrayLength > MAX_INDEXED_ARRAY_SIZE) {
                        // make condition as not indexed
                        eval = new ConditionEvaluatorDecoratorAsNotIndexed(eval);
                    }
                }
                ConditionToEvaluatorHolder pair = new ConditionToEvaluatorHolder(condition, eval, info);
                evalToConds.add(pair);
            }
            Collections.sort(evalToConds);
            return evalToConds.toArray(new ConditionToEvaluatorHolder[0]);
        }
    }

    private IRuleIndex buildIndex(IndexInfo info) {
        if (evaluators.length == 0) {
            return null;
        }
        ConditionToEvaluatorHolder firstPair = evaluators[0];
        if (!firstPair.canIndex()) {
            return null;
        }
        IRuleIndex indexRoot = firstPair.makeIndex(info.makeRuleIterator());
        indexNodes(indexRoot, 1, info);
        return indexRoot;
    }

    private void indexNodes(IRuleIndex index, int condN, IndexInfo info) {
        if (index == null || condN >= evaluators.length) {
            return;
        }

        ConditionToEvaluatorHolder pair = evaluators[condN];
        if (!pair.canIndex()) {
            return;
        }

        for (DecisionTableRuleNode node : index.nodes()) {
            indexNode(node, condN, info);
        }
        indexNode(index.getEmptyOrFormulaNodes(), condN, info);
    }

    private void indexNode(DecisionTableRuleNode node, int condN, IndexInfo info) {
        ConditionToEvaluatorHolder pair = evaluators[condN];
        IRuleIndex nodeIndex = pair.makeIndex(node.getRulesIterator());
        node.setNextIndex(nodeIndex);

        indexNodes(nodeIndex, condN + 1, info);
    }

    private Object evaluateTestValue(ICondition condition, Object target, Object[] dtparams, IRuntimeEnv env) {
        return condition.getEvaluator().invoke(target, dtparams, env);
    }

    /**
     * Clears condition's param values.
     *
     * Memory optimization: clear condition values because this values will be used in index(only if it condition is not
     * used).
     */
    @Override
    public void removeParamValuesForIndexedConditions() {
        for (ConditionToEvaluatorHolder pair : evaluators) {
            if (pair.isIndexed()) {
                if (!isDependecyOnConditionExists(pair.getCondition())) {
                    pair.getCondition().clearParamValues();
                }
            } else {
                IConditionEvaluator decoratedEvaluator = new ConditionEvaluatorDecoratorAsNotIndexed(
                    pair.getEvaluator());
                pair.setEvaluator(decoratedEvaluator);
                break;
            }
        }
        // we do not need dependencies after clearing conditions
        dependencies = null;
    }

    private boolean isDependecyOnConditionExists(ICondition condition) {
        for (IOpenField field : dependencies.getFieldsMap().values()) {
            if (field instanceof ConditionOrActionParameterField && ((ConditionOrActionParameterField) field)
                .getConditionOrAction() == condition) {
                return true;
            }
        }
        return false;
    }

    /**
     * This method produces the iterator over the set of rules in DT. It has to retain the order of the rules.
     *
     * An optimized algorithm has 2 distinct steps:
     *
     * 1) Create initial discriminate rules set using indexing in initial conditions.
     *
     * 2) Iterate over the initial set using remaining conditions as selectors; not-optimized algorithm has the whole
     * rules set as initial.
     *
     * Performance. From the algorithm definition it is clear, that step 1 of algorithm is performed with constant or
     * near constant speed with regard to the number of the rules. The performance of the part 2 is largely dependent
     * the size of the resulting rules set. The order of initial indexed conditions does not seem to affect performance
     * much (//TODO this statement needs verification)
     *
     * @return iterator over <b>rule indexes</b> - integer iterator.
     */
    @Override
    public IIntIterator checkedRules(Object target, Object[] params, IRuntimeEnv env) {
        IIntIterator iterator = null;
        int conditionNumber = 0;

        if (indexRoot == null) {
            iterator = info.makeRuleIterator();
        } else {
            IRuleIndex index = indexRoot;
            DecisionTableRuleNode node = null;
            while (conditionNumber < evaluators.length) {
                ICondition condition = evaluators[conditionNumber].getCondition();
                index = Tracer.wrap(this, index, condition);
                Object testValue = evaluateTestValue(condition, target, params, env);

                node = index.findNode(testValue, node);
                Tracer.put(this, "index", condition, node, true);

                if (!node.hasIndex()) {
                    iterator = node.getRulesIterator();
                    conditionNumber += 1;
                    break;
                }

                index = node.getNextIndex();
                conditionNumber++;
            }
        }

        while (conditionNumber < evaluators.length) {
            ConditionToEvaluatorHolder pair = evaluators[conditionNumber];
            ICondition condition = pair.getCondition();
            IConditionEvaluator evaluator = pair.getEvaluator();

            IIntSelector sel = evaluator.getSelector(condition, target, params, env);
            sel = Tracer.wrap(this, sel, condition);

            iterator = iterator.select(sel);
            conditionNumber++;
        }

        return iterator;
    }

    private static final class ConditionEvaluatorDecoratorAsNotIndexed implements IConditionEvaluator {

        IConditionEvaluator decorate;

        public ConditionEvaluatorDecoratorAsNotIndexed(IConditionEvaluator decorate) {
            this.decorate = Objects.requireNonNull(decorate, "decorate cannot be null");
        }

        @Override
        public IRuleIndex makeIndex(ICondition condition, IIntIterator it) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isIndexed() {
            return false;
        }

        @Override
        public int countUniqueKeys(ICondition cond, IIntIterator it) {
            return 0;
        }

        @Override
        public IIntSelector getSelector(ICondition condition, Object target, Object[] dtparams, IRuntimeEnv env) {
            return decorate.getSelector(condition, target, dtparams, env);
        }

        @Override
        public IDomain<? extends Object> getRuleParameterDomain(IBaseCondition condition) throws DomainCanNotBeDefined {
            return decorate.getRuleParameterDomain(condition);
        }

        @Override
        public String getOptimizedSourceCode() {
            return decorate.getOptimizedSourceCode();
        }

        @Override
        public void setOptimizedSourceCode(String code) {
            decorate.setOptimizedSourceCode(code);
        }

        @Override
        public IOpenSourceCodeModule getFormalSourceCode(IBaseCondition condition) {
            return decorate.getFormalSourceCode(condition);
        }

        @Override
        public IDomain<? extends Object> getConditionParameterDomain(int paramIdx,
                IBaseCondition condition) throws DomainCanNotBeDefined {
            return decorate.getConditionParameterDomain(paramIdx, condition);
        }

        @Override
        public int getPriority() {
            return IConditionEvaluator.DECORATOR_CONDITION_PRIORITY;
        }
    }

    private static class ConditionToEvaluatorHolder implements Comparable<ConditionToEvaluatorHolder> {

        private final IndexInfo localInfo;
        private int uniqueKeysSize = -1;
        private final ICondition condition;
        private IConditionEvaluator evaluator;

        ConditionToEvaluatorHolder(ICondition condition, IConditionEvaluator evaluator, IndexInfo localInfo) {
            this.condition = condition;
            this.evaluator = evaluator;
            this.localInfo = localInfo;
        }

        public ICondition getCondition() {
            return condition;
        }

        public IConditionEvaluator getEvaluator() {
            return evaluator;
        }

        public void setEvaluator(IConditionEvaluator evaluator) {
            this.evaluator = evaluator;
        }

        public boolean canIndex() {
            return evaluator.isIndexed() && !condition.hasFormulas();
        }

        public IRuleIndex makeIndex(IIntIterator it) {
            return evaluator.makeIndex(condition, it);
        }

        public boolean isIndexed() {
            return evaluator.isIndexed();
        }

        @Override
        public int compareTo(ConditionToEvaluatorHolder o) {
            if (this.isEqualsIndex() && o.isEqualsIndex()) {
                return Integer.compare(this.getUniqueKeysSize(), o.getUniqueKeysSize());
            }
            return Integer.compare(this.evaluator.getPriority(), o.evaluator.getPriority());
        }

        private boolean isEqualsIndex() {
            return this.evaluator instanceof EqualsIndexedEvaluator || this.evaluator instanceof ContainsInArrayIndexedEvaluator;
        }

        private int getUniqueKeysSize() {
            if (uniqueKeysSize < 0) {
                uniqueKeysSize = evaluator.countUniqueKeys(condition, localInfo.makeRuleIterator());
            }
            return uniqueKeysSize;
        }
    }
}
