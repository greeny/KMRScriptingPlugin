package dev.greeny.kmr.language.flow;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Flow-sensitive value analysis of one routine body, in the spirit of what the PHP plugin does for variables: walking
 * the statements in order, it learns what conditions and assignments say about variables, fields, constant-indexed
 * elements and deterministic calls ({@link KmrPlace}), and forgets it again where writes, loops or calls with unknown
 * effects may change them ({@link KmrPurity}). The result is a {@link KmrFlowState} for every statement and expression
 * of the body (the state right before it is evaluated), plus the deterministic calls that repeat an earlier,
 * still-valid evaluation.
 * <p>
 * Branches are joined by intersection, {@code exit}/{@code break}/{@code continue} end their path, and loop bodies
 * start from the entry state minus everything the body may write, so nothing learned in one iteration leaks into the
 * next. The analysis is per routine (globals are unknown on entry) and cached until the PSI changes.
 */
public final class KmrFlowAnalysis
{

	private static final Key<CachedValue<KmrFlowAnalysis>> KEY = Key.create("KmrPascalFlowAnalysis");

	private final KmrPascalRoutineDeclaration routine;
	private final Map<PsiElement, KmrFlowState> states = new HashMap<>();
	/** Deterministic call → the earlier evaluation whose value it repeats. */
	private final Map<KmrPascalExpression, KmrPascalExpression> repeated = new LinkedHashMap<>();

	private KmrFlowAnalysis(@NotNull KmrPascalRoutineDeclaration routine)
	{
		this.routine = routine;
	}

	/** The analysis of the routine's body (cached). */
	@NotNull
	public static KmrFlowAnalysis of(@NotNull KmrPascalRoutineDeclaration routine)
	{
		return CachedValuesManager.getCachedValue(routine, KEY, () -> {
			KmrFlowAnalysis analysis = new KmrFlowAnalysis(routine);
			analysis.run();
			return CachedValueProvider.Result.create(analysis, PsiModificationTracker.MODIFICATION_COUNT);
		});
	}

	/** The analysis of the routine containing the element, or null outside routines. */
	@Nullable
	public static KmrFlowAnalysis at(@Nullable PsiElement element)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(element, KmrPascalRoutineDeclaration.class, false);
		return routine == null ? null : of(routine);
	}

	@NotNull
	public KmrPascalRoutineDeclaration getRoutine()
	{
		return routine;
	}

	/** What holds right before the statement or expression is evaluated; null for code outside the body. */
	@Nullable
	public KmrFlowState stateBefore(@NotNull PsiElement element)
	{
		return states.get(element);
	}

	/** What is known about the value of the expression at its position, or null. */
	@Nullable
	public KmrFact valueOf(@NotNull KmrPascalExpression expression)
	{
		KmrPascalExpression subject = expression;
		PsiElement parent = expression.getParent();
		if (parent instanceof KmrPascalCallExpression && ((KmrPascalCallExpression) parent).getExpression() == expression) {
			subject = (KmrPascalExpression) parent; // the callee of States.UnitOwner(U): ask about the call
		}
		KmrFlowState state = states.get(subject);
		return state == null ? null : state.factOf(placeOf(subject));
	}

	/** Deterministic calls whose value an earlier evaluation on every path already has, mapped to that evaluation. */
	@NotNull
	public Map<KmrPascalExpression, KmrPascalExpression> repeatedEvaluations()
	{
		return Collections.unmodifiableMap(repeated);
	}

	/** All evaluations sharing the value of the given one: itself and the later repetitions, in text order. */
	@NotNull
	public List<KmrPascalExpression> evaluationsSharing(@NotNull KmrPascalExpression evaluation)
	{
		KmrPascalExpression first = repeated.getOrDefault(evaluation, evaluation);
		List<KmrPascalExpression> result = new ArrayList<>();
		result.add(first);
		for (Map.Entry<KmrPascalExpression, KmrPascalExpression> entry : repeated.entrySet()) {
			if (entry.getValue() == first) {
				result.add(entry.getKey());
			}
		}
		result.sort(Comparator.comparingInt(PsiElement::getTextOffset));
		return result;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// places
	// ---------------------------------------------------------------------------------------------------------------

	/** The place an expression reads, or null when its value is not followed (a computed index, an impure call, ...). */
	@Nullable
	public KmrPlace placeOf(@Nullable KmrPascalExpression expression)
	{
		KmrPascalExpression e = unparen(expression);
		if (e instanceof KmrPascalIdentifierExpression) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(e);
			if (target instanceof KmrPascalVarIdentifier || target instanceof KmrPascalParameterIdentifier) {
				return KmrPlace.variable(target, e.getText());
			}
			if (target == routine && routine instanceof KmrPascalFunctionDeclaration) {
				return KmrPlace.variable(routine, "Result");
			}
			if (target instanceof KmrPascalFieldIdentifier) {
				return withField((KmrPascalIdentifierExpression) e, (KmrPascalFieldIdentifier) target);
			}
			return null;
		}
		if (e instanceof KmrPascalMemberExpression) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(e);
			if (!(target instanceof KmrPascalFieldIdentifier)) {
				return null;
			}
			if (KmrPascalCallable.of(target) != null) {
				// States.GameTime: a parameterless API function used without parentheses
				return isCallee(e) ? null : callPlace(e, target, Collections.emptyList());
			}
			KmrPlace base = placeOf(((KmrPascalMemberExpression) e).getExpression());
			return base == null ? null : KmrPlace.field(base, target, ((KmrPascalMemberExpression) e).getReferenceName());
		}
		if (e instanceof KmrPascalIndexExpression) {
			List<KmrPascalExpression> children = ((KmrPascalIndexExpression) e).getExpressionList();
			if (children.size() < 2) {
				return null;
			}
			KmrPlace base = placeOf(children.get(0));
			if (base == null) {
				return null;
			}
			List<KmrConst> indexes = new ArrayList<>();
			for (int i = 1; i < children.size(); i++) {
				KmrConst index = KmrConstEvaluator.evaluate(children.get(i));
				if (index == null) {
					return null;
				}
				indexes.add(index);
			}
			return KmrPlace.index(base, indexes);
		}
		if (e instanceof KmrPascalCallExpression) {
			KmrPascalCallExpression call = (KmrPascalCallExpression) e;
			PsiElement target = KmrPascalTypeUtil.resolveSingle(call.getExpression());
			if (!KmrPurity.isDeterministicFunction(target)) {
				return null;
			}
			KmrPascalFunctionArgumentList arguments = call.getFunctionArgumentList();
			return callPlace(call.getExpression(), target, arguments == null ? Collections.emptyList() : arguments.getExpressionList());
		}
		return null;
	}

	@Nullable
	private KmrPlace callPlace(@NotNull KmrPascalExpression callee, @NotNull PsiElement target, @NotNull List<KmrPascalExpression> arguments)
	{
		List<String> keys = new ArrayList<>();
		Set<KmrPlace> dependencies = new LinkedHashSet<>();
		boolean gameState = KmrPurity.of(target) == KmrPurity.Kind.GAME_QUERY;
		StringBuilder text = new StringBuilder(collapse(callee.getText()));
		for (int i = 0; i < arguments.size(); i++) {
			KmrPascalExpression argument = arguments.get(i);
			Boolean argumentGameState = collectDependencies(argument, dependencies);
			if (argumentGameState == null) {
				return null;
			}
			gameState |= argumentGameState;
			keys.add(collapse(argument.getText()).toLowerCase(Locale.ROOT));
			text.append(i == 0 ? "(" : ", ").append(collapse(argument.getText()));
		}
		if (!arguments.isEmpty()) {
			text.append(')');
		}
		return KmrPlace.call(target, keys, dependencies, gameState, text.toString());
	}

	/**
	 * Adds every place the expression reads; returns whether it reads the game state, or null when its value is not
	 * a function of places and constants (an impure call, an unresolved name).
	 */
	@Nullable
	private Boolean collectDependencies(@NotNull KmrPascalExpression expression, @NotNull Set<KmrPlace> dependencies)
	{
		boolean gameState = false;
		List<KmrPascalExpression> all = new ArrayList<>(PsiTreeUtil.findChildrenOfType(expression, KmrPascalExpression.class));
		all.add(0, expression);
		for (KmrPascalExpression e : all) {
			if (e instanceof KmrPascalCallExpression) {
				KmrPlace place = placeOf(e);
				if (place == null) {
					return null;
				}
				dependencies.add(place);
				gameState |= place.gameState;
			} else if (e instanceof KmrPascalReferenceExpression) {
				if (isCallee(e) || isApiObject(e)) {
					continue;
				}
				PsiElement target = KmrPascalTypeUtil.resolveSingle(e);
				if (target == null) {
					return null;
				}
				KmrPlace place = placeOf(e);
				if (place != null) {
					dependencies.add(place);
					gameState |= place.gameState;
				} else if (e instanceof KmrPascalMemberExpression) {
					KmrPlace written = writtenPlaceOf(e);
					if (written != null) {
						dependencies.add(written);
					}
				}
			} else if (e instanceof KmrPascalIndexExpression && placeOf(e) == null) {
				KmrPlace written = writtenPlaceOf(e);
				if (written != null) {
					dependencies.add(written);
				}
			}
		}
		return gameState;
	}

	/** {@code Actions}, {@code States}, {@code Utils}: the API objects (variables of the stubs). */
	private static boolean isApiObject(@NotNull KmrPascalExpression expression)
	{
		if (!(expression instanceof KmrPascalIdentifierExpression)) {
			return false;
		}
		PsiElement target = KmrPascalTypeUtil.resolveSingle(expression);
		return target instanceof KmrPascalVarIdentifier && KmrPascalStubLibrary.isStubFile(target.getContainingFile());
	}

	private static boolean isCallee(@NotNull KmrPascalExpression expression)
	{
		PsiElement parent = expression.getParent();
		return parent instanceof KmrPascalCallExpression && ((KmrPascalCallExpression) parent).getExpression() == expression;
	}

	/** The field of a {@code with} subject an unqualified name refers to: {@code with Rec do X := 1} writes {@code Rec.X}. */
	@Nullable
	private KmrPlace withField(@NotNull KmrPascalIdentifierExpression reference, @NotNull KmrPascalFieldIdentifier field)
	{
		for (KmrPascalWithStatement with = PsiTreeUtil.getParentOfType(reference, KmrPascalWithStatement.class);
			 with != null;
			 with = PsiTreeUtil.getParentOfType(with, KmrPascalWithStatement.class)) {
			List<KmrPascalExpression> subjects = with.getExpressionList();
			for (int i = subjects.size() - 1; i >= 0; i--) {
				if (PsiTreeUtil.isAncestor(subjects.get(i), reference, false)) {
					break;
				}
				KmrPascalRecordType record = KmrPascalTypeUtil.recordTypeOf(subjects.get(i));
				if (record != null && PsiTreeUtil.isAncestor(record, field, true)) {
					KmrPlace base = placeOf(subjects.get(i));
					return base == null ? null : KmrPlace.field(base, field, reference.getReferenceName());
				}
			}
		}
		return null;
	}

	/** The place a write to the expression changes: its own, or for a computed index / unknown part the thing it belongs to. */
	@Nullable
	public KmrPlace writtenPlaceOf(@Nullable KmrPascalExpression expression)
	{
		KmrPascalExpression e = unparen(expression);
		KmrPlace place = placeOf(e);
		if (place != null) {
			return place.isCall() ? null : place;
		}
		if (e instanceof KmrPascalIndexExpression) {
			List<KmrPascalExpression> children = ((KmrPascalIndexExpression) e).getExpressionList();
			return children.isEmpty() ? null : writtenPlaceOf(children.get(0));
		}
		if (e instanceof KmrPascalMemberExpression) {
			return writtenPlaceOf(((KmrPascalMemberExpression) e).getExpression());
		}
		return null;
	}

	/** The constant value of the expression here: folded, or the exact value known for its place. */
	@Nullable
	public KmrConst constantOf(@Nullable KmrPascalExpression expression, @NotNull KmrFlowState state)
	{
		KmrConst constant = KmrConstEvaluator.evaluate(expression);
		if (constant != null) {
			return constant;
		}
		KmrFact fact = state.factOf(placeOf(expression));
		return fact == null ? null : fact.exact;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// conditions
	// ---------------------------------------------------------------------------------------------------------------

	/** Whether the condition holds in the state; UNKNOWN unless the state (or constant folding) decides it. */
	@NotNull
	public KmrTruth evaluate(@Nullable KmrPascalExpression condition, @NotNull KmrFlowState state)
	{
		KmrPascalExpression e = unparen(condition);
		if (e == null) {
			return KmrTruth.UNKNOWN;
		}
		KmrConst folded = KmrConstEvaluator.evaluate(e);
		if (folded != null) {
			return folded.is(KmrConst.Kind.BOOLEAN) ? KmrTruth.of(folded.asBoolean()) : KmrTruth.UNKNOWN;
		}
		if (!state.isReachable()) {
			return KmrTruth.UNKNOWN;
		}
		if (e instanceof KmrPascalUnaryExpression && KmrTypeProvider.operatorOf(e) == KmrPascalTypes.NOT) {
			return evaluate(((KmrPascalUnaryExpression) e).getExpression(), state).not();
		}
		IElementType logical = logicalOperator(e);
		if (logical != null) {
			List<KmrPascalExpression> operands = operands(e);
			KmrPascalExpression left = operands.get(0);
			KmrPascalExpression right = operands.get(1);
			if (logical == KmrPascalTypes.AND) {
				return evaluate(left, state).and(evaluate(right, assume(left, true, state)));
			}
			if (logical == KmrPascalTypes.OR) {
				return evaluate(left, state).or(evaluate(right, assume(left, false, state)));
			}
			return evaluate(left, state).xor(evaluate(right, state));
		}
		if (e instanceof KmrPascalComparisonExpression) {
			return evaluateComparison((KmrPascalComparisonExpression) e, state);
		}
		if (KmrTypeProvider.typeOf(e).is(KmrType.Kind.BOOLEAN)) {
			KmrFact fact = state.factOf(placeOf(e));
			return fact != null && fact.exact != null && fact.exact.is(KmrConst.Kind.BOOLEAN) ? KmrTruth.of(fact.exact.asBoolean()) : KmrTruth.UNKNOWN;
		}
		return KmrTruth.UNKNOWN;
	}

	@NotNull
	private KmrTruth evaluateComparison(@NotNull KmrPascalComparisonExpression comparison, @NotNull KmrFlowState state)
	{
		List<KmrPascalExpression> operands = comparison.getExpressionList();
		IElementType operator = KmrTypeProvider.operatorOf(comparison);
		if (operands.size() != 2 || operator == null) {
			return KmrTruth.UNKNOWN;
		}
		KmrPascalExpression left = operands.get(0);
		KmrPascalExpression right = operands.get(1);
		if (operator == KmrPascalTypes.IN) {
			KmrFact fact = state.factOf(placeOf(left));
			List<KmrConst> members = setMembers(right);
			if (fact == null || members == null) {
				return KmrTruth.UNKNOWN;
			}
			boolean allExcluded = true;
			for (KmrConst member : members) {
				KmrTruth equal = fact.test(KmrPascalTypes.EQ, member);
				if (equal == KmrTruth.TRUE) {
					return KmrTruth.TRUE;
				}
				allExcluded &= equal == KmrTruth.FALSE;
			}
			return allExcluded ? KmrTruth.FALSE : KmrTruth.UNKNOWN;
		}
		KmrConst leftConstant = constantOf(left, state);
		KmrConst rightConstant = constantOf(right, state);
		if (leftConstant != null && rightConstant != null) {
			return KmrConstEvaluator.compare(operator, leftConstant, rightConstant);
		}
		if (rightConstant != null) {
			KmrFact fact = state.factOf(placeOf(left));
			return fact == null ? KmrTruth.UNKNOWN : fact.test(operator, rightConstant);
		}
		if (leftConstant != null) {
			KmrFact fact = state.factOf(placeOf(right));
			return fact == null ? KmrTruth.UNKNOWN : fact.test(flip(operator), leftConstant);
		}
		return KmrTruth.UNKNOWN;
	}

	/** The state after learning that the condition is {@code truth}. */
	@NotNull
	public KmrFlowState assume(@Nullable KmrPascalExpression condition, boolean truth, @NotNull KmrFlowState state)
	{
		KmrPascalExpression e = unparen(condition);
		if (e == null || !state.isReachable()) {
			return state;
		}
		KmrConst folded = KmrConstEvaluator.evaluate(e);
		if (folded != null) {
			return folded.is(KmrConst.Kind.BOOLEAN) && folded.asBoolean() != truth ? KmrFlowState.UNREACHABLE : state;
		}
		if (e instanceof KmrPascalUnaryExpression && KmrTypeProvider.operatorOf(e) == KmrPascalTypes.NOT) {
			return assume(((KmrPascalUnaryExpression) e).getExpression(), !truth, state);
		}
		IElementType logical = logicalOperator(e);
		if (logical == KmrPascalTypes.AND || logical == KmrPascalTypes.OR) {
			List<KmrPascalExpression> operands = operands(e);
			KmrPascalExpression left = operands.get(0);
			KmrPascalExpression right = operands.get(1);
			boolean both = (logical == KmrPascalTypes.AND) == truth; // "and" true / "or" false: both operands are known
			if (both) {
				return assume(right, truth, assume(left, truth, state));
			}
			// "and" false / "or" true: either the left operand decided, or it did not and the right one did
			return KmrFlowState.join(assume(left, truth, state), assume(right, truth, assume(left, !truth, state)));
		}
		if (e instanceof KmrPascalComparisonExpression) {
			return assumeComparison((KmrPascalComparisonExpression) e, truth, state);
		}
		if (KmrTypeProvider.typeOf(e).is(KmrType.Kind.BOOLEAN)) {
			KmrPlace place = placeOf(e);
			return place == null ? state : narrow(state, place, KmrPascalTypes.EQ, KmrConst.of(truth), e);
		}
		return state;
	}

	@NotNull
	private KmrFlowState assumeComparison(@NotNull KmrPascalComparisonExpression comparison, boolean truth, @NotNull KmrFlowState state)
	{
		List<KmrPascalExpression> operands = comparison.getExpressionList();
		IElementType operator = KmrTypeProvider.operatorOf(comparison);
		if (operands.size() != 2 || operator == null) {
			return state;
		}
		KmrPascalExpression left = operands.get(0);
		KmrPascalExpression right = operands.get(1);
		if (operator == KmrPascalTypes.IN) {
			KmrPlace place = placeOf(left);
			List<KmrConst> members = setMembers(right);
			if (place == null || members == null) {
				return state;
			}
			if (truth) {
				KmrFlowState result = KmrFlowState.UNREACHABLE;
				for (KmrConst member : members) {
					result = KmrFlowState.join(result, narrow(state, place, KmrPascalTypes.EQ, member, comparison));
				}
				return members.isEmpty() ? KmrFlowState.UNREACHABLE : result;
			}
			KmrFlowState result = state;
			for (KmrConst member : members) {
				result = narrow(result, place, KmrPascalTypes.NOTEQ, member, comparison);
			}
			return result;
		}
		KmrConst rightConstant = constantOf(right, state);
		KmrPlace leftPlace = placeOf(left);
		if (leftPlace != null && rightConstant != null) {
			return narrow(state, leftPlace, truth ? operator : negate(operator), rightConstant, comparison);
		}
		KmrConst leftConstant = constantOf(left, state);
		KmrPlace rightPlace = placeOf(right);
		if (rightPlace != null && leftConstant != null) {
			IElementType flipped = flip(operator);
			return narrow(state, rightPlace, truth ? flipped : negate(flipped), leftConstant, comparison);
		}
		return state;
	}

	@NotNull
	private static KmrFlowState narrow(@NotNull KmrFlowState state, @NotNull KmrPlace place, @NotNull IElementType operator, @NotNull KmrConst value, @Nullable PsiElement origin)
	{
		KmrFact before = state.factOf(place);
		KmrFact after = KmrFact.narrow(before, operator, value, origin);
		return after == before ? state : state.with(place, after);
	}

	/** The constant members of a set literal, or null when one is not constant. */
	@Nullable
	private static List<KmrConst> setMembers(@NotNull KmrPascalExpression set)
	{
		KmrPascalExpression e = unparen(set);
		if (!(e instanceof KmrPascalArrayExpression)) {
			return null;
		}
		List<KmrConst> result = new ArrayList<>();
		for (KmrPascalExpression member : ((KmrPascalArrayExpression) e).getExpressionList()) {
			KmrConst constant = KmrConstEvaluator.evaluate(member);
			if (constant == null) {
				return null;
			}
			result.add(constant);
		}
		return result;
	}

	/** {@code and}/{@code or}/{@code xor} of two booleans (the same tokens on integers are bit operations). */
	@Nullable
	private static IElementType logicalOperator(@NotNull KmrPascalExpression expression)
	{
		if (!(expression instanceof KmrPascalMultiplicativeExpression) && !(expression instanceof KmrPascalAdditiveExpression)) {
			return null;
		}
		IElementType operator = KmrTypeProvider.operatorOf(expression);
		if (operator != KmrPascalTypes.AND && operator != KmrPascalTypes.OR && operator != KmrPascalTypes.XOR || operands(expression).size() != 2) {
			return null;
		}
		return KmrTypeProvider.typeOf(expression).is(KmrType.Kind.BOOLEAN) ? operator : null;
	}

	@NotNull
	private static List<KmrPascalExpression> operands(@NotNull KmrPascalExpression expression)
	{
		return expression instanceof KmrPascalMultiplicativeExpression ? ((KmrPascalMultiplicativeExpression) expression).getExpressionList()
			: ((KmrPascalAdditiveExpression) expression).getExpressionList();
	}

	/** The comparison that holds when {@code op} does not. */
	@NotNull
	static IElementType negate(@NotNull IElementType operator)
	{
		if (operator == KmrPascalTypes.EQ) return KmrPascalTypes.NOTEQ;
		if (operator == KmrPascalTypes.NOTEQ) return KmrPascalTypes.EQ;
		if (operator == KmrPascalTypes.LT) return KmrPascalTypes.GE;
		if (operator == KmrPascalTypes.GE) return KmrPascalTypes.LT;
		if (operator == KmrPascalTypes.GT) return KmrPascalTypes.LE;
		if (operator == KmrPascalTypes.LE) return KmrPascalTypes.GT;
		return operator;
	}

	/** The comparison with swapped operands. */
	@NotNull
	static IElementType flip(@NotNull IElementType operator)
	{
		if (operator == KmrPascalTypes.LT) return KmrPascalTypes.GT;
		if (operator == KmrPascalTypes.GT) return KmrPascalTypes.LT;
		if (operator == KmrPascalTypes.LE) return KmrPascalTypes.GE;
		if (operator == KmrPascalTypes.GE) return KmrPascalTypes.LE;
		return operator;
	}

	@Nullable
	static KmrPascalExpression unparen(@Nullable KmrPascalExpression expression)
	{
		KmrPascalExpression e = expression;
		while (e instanceof KmrPascalParenExpression && ((KmrPascalParenExpression) e).getExpression() != null) {
			e = ((KmrPascalParenExpression) e).getExpression();
		}
		return e;
	}

	@NotNull
	private static String collapse(@NotNull String text)
	{
		return text.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")").replace(" ,", ",").trim();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the walk
	// ---------------------------------------------------------------------------------------------------------------

	private void run()
	{
		KmrPascalStatementList body = routine.getStatementList();
		if (body != null) {
			new Walker().walkStatement(body, KmrFlowState.EMPTY);
		}
	}

	/** Writes performed inside a loop body, collected on a trial walk so the real walk can start without their facts. */
	private static final class KillLog
	{
		final Set<KmrPlace> places = new HashSet<>();
		boolean gameState;
		boolean globals;
		boolean all;

		void absorb(@NotNull KillLog inner)
		{
			places.addAll(inner.places);
			gameState |= inner.gameState;
			globals |= inner.globals;
			all |= inner.all;
		}
	}

	private final class Walker
	{
		/** > 0 while walking a loop body for its writes only: nothing is recorded. */
		private int trial;
		private final Deque<KillLog> logs = new ArrayDeque<>();

		private void record(@NotNull PsiElement element, @NotNull KmrFlowState state)
		{
			if (trial == 0) {
				states.put(element, state);
			}
		}

		// kills, logged for enclosing loops

		private KmrFlowState kill(@NotNull KmrFlowState state, @Nullable KmrPlace place)
		{
			if (place == null) {
				return state;
			}
			for (KillLog log : logs) {
				log.places.add(place);
			}
			return state.kill(place);
		}

		private KmrFlowState killGameState(@NotNull KmrFlowState state)
		{
			for (KillLog log : logs) {
				log.gameState = true;
			}
			return state.killGameState();
		}

		private KmrFlowState killGlobals(@NotNull KmrFlowState state)
		{
			for (KillLog log : logs) {
				log.globals = true;
			}
			return state.killGlobals(routine);
		}

		private KmrFlowState killAll(@NotNull KmrFlowState state)
		{
			for (KillLog log : logs) {
				log.all = true;
			}
			return state.killAll();
		}

		/** The entry state minus everything the loop (body and condition) may write, found by a trial walk. */
		private KmrFlowState loopEntry(@NotNull KmrFlowState entry, @NotNull java.util.function.Function<KmrFlowState, KmrFlowState> loopWalk)
		{
			KillLog log = new KillLog();
			logs.push(log);
			trial++;
			try {
				loopWalk.apply(entry);
			} finally {
				trial--;
				logs.pop();
			}
			for (KillLog outer : logs) {
				outer.absorb(log);
			}
			if (log.all) {
				return entry.killAll();
			}
			KmrFlowState state = entry;
			if (log.globals) {
				state = state.killGlobals(routine);
			}
			if (log.gameState) {
				state = state.killGameState();
			}
			for (KmrPlace place : log.places) {
				state = state.kill(place);
			}
			return state;
		}

		// statements

		private KmrFlowState walkBlock(@NotNull PsiElement block, @NotNull KmrFlowState state)
		{
			KmrFlowState s = state;
			for (PsiElement statement : KmrStatements.statementsOf(block)) {
				s = walkStatement(statement, s);
			}
			return s;
		}

		private KmrFlowState walkControlled(@Nullable PsiElement statement, @NotNull KmrFlowState state)
		{
			return statement == null ? state : walkStatement(statement, state);
		}

		private KmrFlowState walkStatement(@NotNull PsiElement statement, @NotNull KmrFlowState state)
		{
			record(statement, state);
			if (!state.isReachable()) {
				return state;
			}
			if (statement instanceof KmrPascalStatementList) {
				return walkBlock(statement, state);
			}
			if (statement instanceof KmrPascalAssignment) {
				return walkAssignment((KmrPascalAssignment) statement, state);
			}
			if (statement instanceof KmrPascalExpressionStatement) {
				return walkExpression(((KmrPascalExpressionStatement) statement).getExpression(), state);
			}
			if (statement instanceof KmrPascalIfStatement) {
				KmrPascalIfStatement ifStatement = (KmrPascalIfStatement) statement;
				KmrPascalExpression condition = ifStatement.getExpression();
				KmrFlowState s = walkExpression(condition, state);
				KmrFlowState thenState = walkControlled(KmrStatements.thenBranch(ifStatement), assume(condition, true, s));
				KmrFlowState elseState = walkControlled(KmrStatements.elseBranch(ifStatement), assume(condition, false, s));
				return KmrFlowState.join(thenState, elseState);
			}
			if (statement instanceof KmrPascalWhileStatement) {
				KmrPascalWhileStatement loop = (KmrPascalWhileStatement) statement;
				KmrPascalExpression condition = loop.getExpression();
				PsiElement body = KmrStatements.body(loop);
				KmrFlowState entry = loopEntry(state, s -> walkControlled(body, assume(condition, true, walkExpression(condition, s))));
				KmrFlowState head = walkExpression(condition, entry);
				KmrFlowState exit = walkControlled(body, assume(condition, true, head));
				return hasBreak(loop) ? entry : assume(condition, false, KmrFlowState.join(head, exit));
			}
			if (statement instanceof KmrPascalRepeatStatement) {
				KmrPascalRepeatStatement loop = (KmrPascalRepeatStatement) statement;
				KmrPascalExpression condition = loop.getExpression();
				KmrFlowState entry = loopEntry(state, s -> walkExpression(condition, walkBlock(loop, s)));
				KmrFlowState exit = walkExpression(condition, walkBlock(loop, entry));
				return hasBreak(loop) ? entry : assume(condition, true, exit);
			}
			if (statement instanceof KmrPascalForStatement) {
				return walkFor((KmrPascalForStatement) statement, state);
			}
			if (statement instanceof KmrPascalWithStatement) {
				KmrPascalWithStatement with = (KmrPascalWithStatement) statement;
				KmrFlowState s = state;
				for (KmrPascalExpression subject : with.getExpressionList()) {
					s = walkExpression(subject, s);
				}
				return walkControlled(KmrStatements.body(with), s);
			}
			if (statement instanceof KmrPascalCaseStatement) {
				return walkCase((KmrPascalCaseStatement) statement, state);
			}
			if (statement instanceof KmrPascalExitStatement || statement instanceof KmrPascalBreakStatement || statement instanceof KmrPascalContinueStatement) {
				return KmrFlowState.UNREACHABLE;
			}
			return state;
		}

		private KmrFlowState walkAssignment(@NotNull KmrPascalAssignment assignment, @NotNull KmrFlowState state)
		{
			List<KmrPascalExpression> sides = assignment.getExpressionList();
			if (sides.size() != 2) {
				return state;
			}
			KmrPascalExpression target = sides.get(0);
			KmrPascalExpression value = sides.get(1);
			KmrFlowState s = walkExpression(value, state);
			s = walkExpression(target, s);
			KmrConst constant = constantOf(value, s);
			KmrFact fact = constant != null ? KmrFact.exact(constant, assignment) : s.factOf(placeOf(value));
			s = kill(s, writtenPlaceOf(target));
			KmrPlace place = placeOf(target);
			if (place != null && !place.isCall() && fact != null) {
				s = s.with(place, fact.withOrigin(assignment));
			}
			record(target, s); // hovering the assigned variable shows its new value
			return s;
		}

		private KmrFlowState walkFor(@NotNull KmrPascalForStatement loop, @NotNull KmrFlowState state)
		{
			List<KmrPascalExpression> expressions = loop.getExpressionList();
			KmrPascalExpression variable = expressions.isEmpty() ? null : expressions.get(0);
			KmrFlowState s = state;
			for (int i = 1; i < expressions.size(); i++) {
				s = walkExpression(expressions.get(i), s);
			}
			if (variable != null) {
				s = walkExpression(variable, s);
			}
			KmrPlace place = placeOf(variable);
			PsiElement body = KmrStatements.body(loop);
			KmrFlowState before = kill(s, writtenPlaceOf(variable));
			KmrFlowState entry = loopEntry(before, e -> walkControlled(body, e));
			KmrFlowState inLoop = entry;
			if (place != null && expressions.size() == 3) {
				KmrConst from = KmrConstEvaluator.evaluate(expressions.get(1));
				KmrConst to = KmrConstEvaluator.evaluate(expressions.get(2));
				if (from != null && to != null && from.is(KmrConst.Kind.INTEGER) && to.is(KmrConst.Kind.INTEGER)) {
					boolean down = loop.getNode().findChildByType(KmrPascalTypes.DOWNTO) != null;
					long lo = down ? to.number : from.number;
					long hi = down ? from.number : to.number;
					if (lo <= hi) {
						inLoop = entry.with(place, KmrFact.range(lo, hi, loop));
					}
				}
			}
			walkControlled(body, inLoop);
			return entry;
		}

		private KmrFlowState walkCase(@NotNull KmrPascalCaseStatement caseStatement, @NotNull KmrFlowState state)
		{
			KmrPascalExpression selector = caseStatement.getExpression();
			KmrFlowState s = walkExpression(selector, state);
			KmrPlace place = placeOf(selector);
			KmrFlowState result = KmrFlowState.UNREACHABLE;
			KmrFlowState remaining = s;
			for (KmrPascalCaseBranch branch : caseStatement.getCaseBranchList()) {
				KmrFlowState branchState = KmrFlowState.UNREACHABLE;
				boolean narrowed = place != null;
				for (KmrPascalCaseLabel label : branch.getCaseLabelList()) {
					List<KmrPascalExpression> bounds = label.getExpressionList();
					KmrConst first = bounds.isEmpty() ? null : KmrConstEvaluator.evaluate(bounds.get(0));
					KmrConst second = bounds.size() < 2 ? null : KmrConstEvaluator.evaluate(bounds.get(1));
					if (place == null || first == null || bounds.size() > 1 && second == null) {
						narrowed = false;
						continue;
					}
					if (bounds.size() == 1) {
						branchState = KmrFlowState.join(branchState, narrow(s, place, KmrPascalTypes.EQ, first, label));
						remaining = narrow(remaining, place, KmrPascalTypes.NOTEQ, first, label);
					} else {
						KmrFlowState inRange = narrow(narrow(s, place, KmrPascalTypes.GE, first, label), place, KmrPascalTypes.LE, second, label);
						branchState = KmrFlowState.join(branchState, inRange);
					}
				}
				result = KmrFlowState.join(result, walkControlled(KmrStatements.body(branch), narrowed ? branchState : s));
			}
			List<PsiElement> elseStatements = KmrStatements.statementsOf(caseStatement);
			if (elseStatements.isEmpty()) {
				return KmrFlowState.join(result, remaining);
			}
			KmrFlowState elseState = remaining;
			for (PsiElement statement : elseStatements) {
				elseState = walkStatement(statement, elseState);
			}
			return KmrFlowState.join(result, elseState);
		}

		private boolean hasBreak(@NotNull PsiElement loop)
		{
			for (KmrPascalBreakStatement breakStatement : PsiTreeUtil.findChildrenOfType(loop, KmrPascalBreakStatement.class)) {
				PsiElement enclosing = PsiTreeUtil.getParentOfType(breakStatement, KmrPascalWhileStatement.class, KmrPascalRepeatStatement.class, KmrPascalForStatement.class);
				if (enclosing == loop) {
					return true;
				}
			}
			return false;
		}

		// expressions

		private KmrFlowState walkExpression(@Nullable KmrPascalExpression expression, @NotNull KmrFlowState state)
		{
			if (expression == null) {
				return state;
			}
			record(expression, state);
			if (!state.isReachable()) {
				return state;
			}
			IElementType logical = logicalOperator(expression);
			if (logical == KmrPascalTypes.AND || logical == KmrPascalTypes.OR) {
				List<KmrPascalExpression> operands = operands(expression);
				KmrFlowState afterLeft = walkExpression(operands.get(0), state);
				// the right operand only runs when the left one did not decide (PascalScript short-circuits)
				KmrFlowState afterRight = walkExpression(operands.get(1), assume(operands.get(0), logical == KmrPascalTypes.AND, afterLeft));
				return KmrFlowState.join(afterLeft, afterRight);
			}
			if (expression instanceof KmrPascalCallExpression) {
				return walkCall((KmrPascalCallExpression) expression, state);
			}
			KmrFlowState s = state;
			for (PsiElement child = expression.getFirstChild(); child != null; child = child.getNextSibling()) {
				if (child instanceof KmrPascalExpression) {
					s = walkExpression((KmrPascalExpression) child, s);
				}
			}
			if (expression instanceof KmrPascalReferenceExpression && !isCallee(expression) && !(expression.getParent() instanceof KmrPascalAddressExpression)) {
				// "Other;" / "X := GetCount;" / "States.GameTime": a routine called without parentheses
				PsiElement target = KmrPascalTypeUtil.resolveSingle(expression);
				if (KmrPascalCallable.of(target) != null && target != routine) {
					s = applyCallEffects(target, Collections.emptyList(), s);
					s = noteEvaluation(expression, s);
				}
			}
			return s;
		}

		private KmrFlowState walkCall(@NotNull KmrPascalCallExpression call, @NotNull KmrFlowState state)
		{
			KmrPascalExpression callee = call.getExpression();
			KmrFlowState s = walkExpression(callee, state);
			KmrPascalFunctionArgumentList argumentList = call.getFunctionArgumentList();
			List<KmrPascalExpression> arguments = argumentList == null ? Collections.emptyList() : argumentList.getExpressionList();
			for (KmrPascalExpression argument : arguments) {
				s = walkExpression(argument, s);
			}
			PsiElement target = KmrPascalTypeUtil.resolveSingle(callee);
			if (target instanceof KmrPascalTypeDeclaration) {
				return s; // TKMUnitType(1): a cast
			}
			if (target == null && callee instanceof KmrPascalIdentifierExpression
				&& KmrTypeProvider.builtinType(((KmrPascalIdentifierExpression) callee).getReferenceName()) != null) {
				return s; // Integer(x): a cast
			}
			s = applyCallEffects(target, arguments, s);
			return noteEvaluation(call, s);
		}

		/** What a call does to the state: writes to by-reference arguments, then whatever the callee's kind allows. */
		private KmrFlowState applyCallEffects(@Nullable PsiElement target, @NotNull List<KmrPascalExpression> arguments, @NotNull KmrFlowState state)
		{
			KmrFlowState s = state;
			KmrPascalCallable callable = KmrPascalCallable.of(target);
			if (callable == null) {
				return killAll(s);
			}
			List<KmrPascalParameterIdentifier> parameters = callable.getParameterIdentifiers();
			for (int i = 0; i < Math.min(parameters.size(), arguments.size()); i++) {
				PsiElement group = parameters.get(i).getParent();
				if (group instanceof KmrPascalParameterDeclaration && KmrPurity.isByReference((KmrPascalParameterDeclaration) group)) {
					s = kill(s, writtenPlaceOf(arguments.get(i)));
				}
			}
			switch (KmrPurity.of(target)) {
				case GAME_CHANGE:
					s = killGameState(s);
					break;
				case SCRIPT_ROUTINE:
					s = killGameState(killGlobals(s));
					break;
				case UNKNOWN:
					if (!(target instanceof KmrPascalFieldIdentifier && KmrPascalStubLibrary.isStubFile(target.getContainingFile()))) {
						s = killAll(s); // a procedural variable of the script; KaMRandom itself changes nothing
					}
					break;
				default:
					break;
			}
			return s;
		}

		/** Remembers a deterministic call's evaluation, or reports it as a repetition of an earlier one. */
		private KmrFlowState noteEvaluation(@NotNull KmrPascalExpression call, @NotNull KmrFlowState state)
		{
			KmrPlace place = placeOf(call);
			if (place == null) {
				return state;
			}
			KmrPascalExpression earlier = state.evaluationOf(place);
			if (earlier != null && earlier != call) {
				if (trial == 0) {
					repeated.put(call, repeated.getOrDefault(earlier, earlier));
				}
				return state;
			}
			return state.evaluated(place, call);
		}
	}

}
