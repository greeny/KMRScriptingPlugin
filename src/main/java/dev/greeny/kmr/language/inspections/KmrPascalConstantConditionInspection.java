package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.flow.*;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conditions the flow analysis decides: {@code if a = False} inside {@code if a = True}, {@code if States.UnitOwner(U) = 3}
 * repeated without an {@code Actions} call in between, {@code if Found} right after {@code Found := False}, and
 * self-contradicting ones like {@code (A = 1) and (A = 2)}. Conditions that fold to a constant ({@code if DEBUG then},
 * {@code while True do}) are left alone. The whole condition is reported when the flow decides it, otherwise the
 * and/or operands it decides.
 */
public class KmrPascalConstantConditionInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				KmrPascalExpression condition = conditionOf(element);
				if (condition != null && !KmrPascalInspectionUtil.shouldSkip(element)) {
					KmrFlowAnalysis analysis = KmrFlowAnalysis.at(condition);
					if (analysis != null) {
						check(condition, analysis, holder);
					}
				}
			}
		};
	}

	/** The condition of an if/while/repeat statement; other elements have none. */
	private static KmrPascalExpression conditionOf(@NotNull PsiElement element)
	{
		if (element instanceof KmrPascalIfStatement) {
			return ((KmrPascalIfStatement) element).getExpression();
		}
		if (element instanceof KmrPascalWhileStatement) {
			return ((KmrPascalWhileStatement) element).getExpression();
		}
		if (element instanceof KmrPascalRepeatStatement) {
			return ((KmrPascalRepeatStatement) element).getExpression();
		}
		return null;
	}

	/**
	 * Reports the smallest part of the condition the flow decides: an operand of and/or/not when one of them is
	 * decided, otherwise the condition as a whole. Returns whether something was reported.
	 */
	private static boolean check(@NotNull KmrPascalExpression condition, @NotNull KmrFlowAnalysis analysis, @NotNull ProblemsHolder holder)
	{
		KmrPascalExpression inner = condition;
		while (inner instanceof KmrPascalParenExpression && ((KmrPascalParenExpression) inner).getExpression() != null) {
			inner = ((KmrPascalParenExpression) inner).getExpression();
		}
		if (inner instanceof KmrPascalUnaryExpression && KmrTypeProvider.operatorOf(inner) == KmrPascalTypes.NOT) {
			KmrPascalExpression operand = ((KmrPascalUnaryExpression) inner).getExpression();
			return operand != null && check(operand, analysis, holder);
		}
		if (inner instanceof KmrPascalMultiplicativeExpression || inner instanceof KmrPascalAdditiveExpression) {
			IElementType operator = KmrTypeProvider.operatorOf(inner);
			if (operator == KmrPascalTypes.AND || operator == KmrPascalTypes.OR || operator == KmrPascalTypes.XOR) {
				List<KmrPascalExpression> operands = inner instanceof KmrPascalMultiplicativeExpression
					? ((KmrPascalMultiplicativeExpression) inner).getExpressionList() : ((KmrPascalAdditiveExpression) inner).getExpressionList();
				boolean reported = false;
				for (KmrPascalExpression operand : operands) {
					reported |= check(operand, analysis, holder); // each operand has its own recorded state (the left one assumed)
				}
				if (reported) {
					return true;
				}
			}
		}
		KmrFlowState state = analysis.stateBefore(condition);
		if (state == null || !state.isReachable()) {
			return false;
		}
		KmrTruth truth = analysis.evaluate(condition, state);
		if (!truth.isKnown() || KmrConstEvaluator.evaluate(condition) != null) {
			return false; // undecided, or a constant on its own (DEBUG flags, "while True")
		}
		String reason = reason(condition, analysis, state);
		holder.registerProblem(condition, "Condition '" + condition.getText() + "' is always " + (truth == KmrTruth.TRUE ? "true" : "false") + reason,
			ProblemHighlightType.WARNING);
		return true;
	}

	/** {@code  (a is True here)}: the known values the decision rests on. */
	@NotNull
	private static String reason(@NotNull KmrPascalExpression condition, @NotNull KmrFlowAnalysis analysis, @NotNull KmrFlowState state)
	{
		Set<KmrPlace> seen = new LinkedHashSet<>();
		List<String> parts = new ArrayList<>();
		List<KmrPascalExpression> expressions = new ArrayList<>();
		expressions.add(condition);
		expressions.addAll(PsiTreeUtil.findChildrenOfType(condition, KmrPascalExpression.class));
		for (KmrPascalExpression expression : expressions) {
			KmrPlace place = analysis.placeOf(expression);
			KmrFact fact = state.factOf(place);
			if (place != null && fact != null && seen.add(place)) {
				parts.add(place.text + " is " + fact.describe());
			}
		}
		return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + " here)";
	}

}
