package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.types.KmrKind;
import dev.greeny.kmr.language.types.KmrKindInference;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

import static com.intellij.codeInspection.options.OptPane.checkbox;
import static com.intellij.codeInspection.options.OptPane.pane;

/**
 * KMR-specific guards on integers that mean different things (kinds: unit ID, house ID, group ID, hand index, X/Y
 * coordinate, legacy type ids, ticks): a value of one kind used where another is expected, swapped X/Y, literals
 * where an id is expected, arithmetic on ids, comparisons between kinds, and variables that receive conflicting
 * kinds. Silent whenever a kind is unknown. Every rule can be switched off separately.
 */
public class KmrPascalKindInspection extends LocalInspectionTool
{

	public boolean reportArgumentMismatch = true;
	public boolean reportAssignmentMismatch = true;
	public boolean reportConflictingAssignments = true;
	public boolean reportLiteralIds = true;
	public boolean reportArithmeticOnIds = true;
	public boolean reportComparisonMismatch = true;
	public boolean reportSwappedCoordinates = true;
	public boolean reportLegacyTypeIds = true;

	@Override
	public @NotNull OptPane getOptionsPane()
	{
		return pane(
			checkbox("reportArgumentMismatch", "Argument of a different kind than the parameter"),
			checkbox("reportAssignmentMismatch", "Assignment of a different kind than the variable"),
			checkbox("reportConflictingAssignments", "Variable, field or parameter receiving values of several kinds"),
			checkbox("reportLiteralIds", "Integer literal where a unit/house/group ID is expected"),
			checkbox("reportArithmeticOnIds", "Arithmetic (+, -, *, div, mod, Inc, Dec) on ids"),
			checkbox("reportComparisonMismatch", "Comparison between different kinds, ordering of ids"),
			checkbox("reportSwappedCoordinates", "X coordinate used as Y or vice versa"),
			checkbox("reportLegacyTypeIds", "Integer type id passed to a function that has an enum-based variant")
		);
	}

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (!(element instanceof KmrPascalAssignment) && !(element instanceof KmrPascalForStatement) && !(element instanceof KmrPascalCallExpression)
					&& !(element instanceof KmrPascalComparisonExpression) && !(element instanceof KmrPascalAdditiveExpression)
					&& !(element instanceof KmrPascalMultiplicativeExpression)) {
					return;
				}
				if (KmrPascalInspectionUtil.shouldSkip(element)) {
					return;
				}
				if (element instanceof KmrPascalAssignment) {
					List<KmrPascalExpression> sides = ((KmrPascalAssignment) element).getExpressionList();
					if (sides.size() == 2) {
						checkStore(sides.get(0), sides.get(1), holder);
					}
				} else if (element instanceof KmrPascalForStatement) {
					List<KmrPascalExpression> parts = ((KmrPascalForStatement) element).getExpressionList();
					if (parts.size() >= 3) {
						checkStore(parts.get(0), parts.get(1), holder);
						checkStore(parts.get(0), parts.get(2), holder);
					}
				} else if (element instanceof KmrPascalCallExpression) {
					checkCall((KmrPascalCallExpression) element, holder);
				} else if (element instanceof KmrPascalComparisonExpression) {
					checkComparison((KmrPascalComparisonExpression) element, holder);
				} else {
					checkArithmetic((KmrPascalExpression) element, holder);
				}
			}
		};
	}

	// ---------------------------------------------------------------------------------------------------------------
	// assignments (X := e, for X := a to b)
	// ---------------------------------------------------------------------------------------------------------------

	private void checkStore(@NotNull KmrPascalExpression target, @NotNull KmrPascalExpression value, @NotNull ProblemsHolder holder)
	{
		KmrKind valueKind = KmrKindInference.kindOf(value);
		KmrKindInference.Slot slot = KmrKindInference.slotOf(target);
		KmrKindInference.Result inferred = slot == null ? null : KmrKindInference.resultOf(slot);
		if (inferred != null && inferred.conflict) {
			if (reportConflictingAssignments && valueKind != null) {
				holder.registerProblem(value, "Conflicting kinds are assigned to '" + slot.name() + "': " + valueKind.withArticle() + " here, "
					+ others(inferred, valueKind) + " elsewhere");
			}
			return;
		}
		KmrKind targetKind = KmrKindInference.kindOf(target);
		if (targetKind == null) {
			return;
		}
		boolean declared = inferred == null || inferred.sources.isEmpty(); // not (only) flow-inferred
		if (valueKind != null && !valueKind.compatible(targetKind)) {
			if (isSwap(targetKind, valueKind)) {
				if (reportSwappedCoordinates) {
					holder.registerProblem(value, "Cannot assign " + valueKind.withArticle() + " to '" + name(target) + "', " + targetKind.withArticle() + " (swapped X/Y?)");
				}
			} else if (reportAssignmentMismatch) {
				holder.registerProblem(value, "Cannot assign " + valueKind.withArticle() + " to '" + name(target) + "', " + targetKind.withArticle());
			}
		} else if (valueKind == null && declared && reportLiteralIds && !targetKind.allowsLiterals && isPositiveLiteral(value)) {
			holder.registerProblem(value, "Integer literal assigned to '" + name(target) + "', " + targetKind.withArticle());
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// calls
	// ---------------------------------------------------------------------------------------------------------------

	private void checkCall(@NotNull KmrPascalCallExpression call, @NotNull ProblemsHolder holder)
	{
		KmrPascalFunctionArgumentList arguments = call.getFunctionArgumentList();
		if (arguments == null) {
			return;
		}
		List<KmrPascalExpression> values = arguments.getExpressionList();
		checkIncDec(call, values, holder);
		PsiElement target = KmrPascalTypeUtil.resolveSingle(call.getExpression());
		KmrPascalCallable callable = KmrPascalCallable.of(target);
		if (callable == null) {
			return;
		}
		List<KmrPascalParameterIdentifier> parameters = callable.getParameterIdentifiers();
		String callee = String.valueOf(callable.getName());
		boolean legacyHintGiven = false;
		for (int i = 0; i < Math.min(parameters.size(), values.size()); i++) {
			KmrPascalParameterIdentifier parameter = parameters.get(i);
			KmrPascalExpression value = values.get(i);
			KmrPascalArgumentModifier modifier = ((KmrPascalParameterDeclaration) parameter.getParent()).getArgumentModifier();
			boolean byReference = modifier != null && !modifier.getText().equalsIgnoreCase("const");
			if (byReference) {
				// the parameter's kind flows into the argument variable: an assignment in the other direction
				checkStore(value, parameter, callee, holder);
				continue;
			}
			KmrKindInference.Result inferred = KmrKindInference.resultOf(parameter, 0);
			KmrKind valueKind = KmrKindInference.kindOf(value);
			if (inferred.conflict) {
				if (reportConflictingAssignments && valueKind != null) {
					holder.registerProblem(value, "Conflicting kinds are passed as '" + parameter.getName() + "' of '" + callee + "': " + valueKind.withArticle()
						+ " here, " + others(inferred, valueKind) + " elsewhere");
				}
				continue;
			}
			KmrKind expected = KmrKindInference.kindOfDeclaration(parameter, 0);
			if (expected == null) {
				continue;
			}
			if (valueKind != null && !valueKind.compatible(expected)) {
				if (isSwap(expected, valueKind)) {
					if (reportSwappedCoordinates) {
						holder.registerProblem(value, "Argument '" + parameter.getName() + "' of '" + callee + "' expects " + expected.withArticle() + ", got " + valueKind.withArticle() + " (swapped X/Y?)");
					}
				} else if (reportArgumentMismatch) {
					holder.registerProblem(value, "Argument '" + parameter.getName() + "' of '" + callee + "' expects " + expected.withArticle() + ", got " + valueKind.withArticle());
				}
			} else if (valueKind == null && reportLiteralIds && !expected.allowsLiterals && isPositiveLiteral(value)) {
				holder.registerProblem(value, "Integer literal passed as '" + parameter.getName() + "' of '" + callee + "', " + expected.withArticle());
			}
			if (!legacyHintGiven && reportLegacyTypeIds && expected.policy == KmrKind.Policy.LEGACY_ID) {
				legacyHintGiven = reportLegacyVariant(call, callable, expected, holder);
			}
		}
	}

	/** {@code Foo(X)} with a {@code var}/{@code out} parameter: X receives the parameter's kind. */
	private void checkStore(@NotNull KmrPascalExpression argument, @NotNull KmrPascalParameterIdentifier parameter, @NotNull String callee, @NotNull ProblemsHolder holder)
	{
		KmrKind sourceKind = KmrKindInference.kindOfDeclaration(parameter, 0);
		if (sourceKind == null) {
			return;
		}
		KmrKindInference.Result inferred = KmrKindInference.resultOf(argument);
		if (inferred != null && inferred.conflict) {
			if (reportConflictingAssignments) {
				holder.registerProblem(argument, "Conflicting kinds are assigned to '" + name(argument) + "': " + sourceKind.withArticle() + " here (from '"
					+ parameter.getName() + "' of '" + callee + "'), " + others(inferred, sourceKind) + " elsewhere");
			}
			return;
		}
		KmrKind targetKind = KmrKindInference.kindOf(argument);
		if (targetKind == null || targetKind.compatible(sourceKind)) {
			return;
		}
		if (isSwap(targetKind, sourceKind)) {
			if (reportSwappedCoordinates) {
				holder.registerProblem(argument, "'" + name(argument) + "' (" + targetKind.withArticle() + ") receives " + sourceKind.withArticle() + " from '" + parameter.getName() + "' of '" + callee + "' (swapped X/Y?)");
			}
		} else if (reportArgumentMismatch) {
			holder.registerProblem(argument, "'" + name(argument) + "' (" + targetKind.withArticle() + ") receives " + sourceKind.withArticle() + " from '" + parameter.getName() + "' of '" + callee + "'");
		}
	}

	/** {@code Inc(U)} / {@code Dec(U)} on an id (the standard routines take "any ordinal", so this goes by name). */
	private void checkIncDec(@NotNull KmrPascalCallExpression call, @NotNull List<KmrPascalExpression> values, @NotNull ProblemsHolder holder)
	{
		if (!reportArithmeticOnIds || values.isEmpty() || !(call.getExpression() instanceof KmrPascalIdentifierExpression)) {
			return;
		}
		String name = ((KmrPascalIdentifierExpression) call.getExpression()).getReferenceName();
		if (!name.equalsIgnoreCase("Inc") && !name.equalsIgnoreCase("Dec")) {
			return;
		}
		KmrKind kind = KmrKindInference.kindOf(values.get(0));
		if (kind != null && !kind.allowsArithmetic) {
			holder.registerProblem(call, "'" + name + "' on '" + name(values.get(0)) + "', " + kind.withArticle());
		}
	}

	/**
	 * Legacy integer type id passed to an API member that has an {@code ...Ex} sibling taking the enum: a migration
	 * hint (deprecated members are already reported by the deprecation inspection).
	 */
	private boolean reportLegacyVariant(@NotNull KmrPascalCallExpression call, @NotNull KmrPascalCallable callable, @NotNull KmrKind kind, @NotNull ProblemsHolder holder)
	{
		PsiElement declaration = callable.getDeclaration();
		String name = callable.getName();
		if (name == null || !(declaration instanceof KmrPascalFieldIdentifier) || !KmrPascalStubLibrary.isStubFile(declaration.getContainingFile())
			|| callable.getDeprecationMessage() != null) {
			return false;
		}
		KmrPascalRecordType record = PsiTreeUtil.getParentOfType(declaration, KmrPascalRecordType.class);
		if (record == null || KmrPascalTypeUtil.findFields(record, name + "Ex").isEmpty()) {
			return false;
		}
		KmrPascalExpression callee = call.getExpression();
		PsiElement anchor = callee instanceof KmrPascalReferenceExpression ? ((KmrPascalReferenceExpression) callee).getNameIdentifier() : callee;
		holder.registerProblem(anchor == null ? callee : anchor, "'" + name + "' takes " + kind.withArticle() + "; '" + name + "Ex' takes " + (kind.preferredType == null ? "an enum" : kind.preferredType),
			ProblemHighlightType.WEAK_WARNING);
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// operators
	// ---------------------------------------------------------------------------------------------------------------

	private void checkComparison(@NotNull KmrPascalComparisonExpression comparison, @NotNull ProblemsHolder holder)
	{
		List<KmrPascalExpression> operands = comparison.getExpressionList();
		IElementType operator = KmrTypeProvider.operatorOf(comparison);
		if (operands.size() != 2 || operator == null || operator == KmrPascalTypes.IN) {
			return;
		}
		KmrKind left = KmrKindInference.kindOf(operands.get(0));
		KmrKind right = KmrKindInference.kindOf(operands.get(1));
		if (left == null || right == null) {
			return; // comparing with literals (U > 0, U = -1) is the validity idiom
		}
		if (!left.compatible(right)) {
			if (isSwap(left, right)) {
				if (reportSwappedCoordinates) {
					holder.registerProblem(comparison, "Comparison between " + left.withArticle() + " and " + right.withArticle() + " (swapped X/Y?)");
				}
			} else if (reportComparisonMismatch) {
				holder.registerProblem(comparison, "Comparison between " + left.withArticle() + " and " + right.withArticle());
			}
			return;
		}
		boolean ordering = operator == KmrPascalTypes.LT || operator == KmrPascalTypes.GT || operator == KmrPascalTypes.LE || operator == KmrPascalTypes.GE;
		if (ordering && reportComparisonMismatch && !left.allowsArithmetic) {
			holder.registerProblem(comparison, "Ordering comparison between " + plural(left) + " (ids have no order)");
		}
	}

	private void checkArithmetic(@NotNull KmrPascalExpression expression, @NotNull ProblemsHolder holder)
	{
		if (!reportArithmeticOnIds) {
			return;
		}
		List<KmrPascalExpression> operands = expression instanceof KmrPascalAdditiveExpression
			? ((KmrPascalAdditiveExpression) expression).getExpressionList() : ((KmrPascalMultiplicativeExpression) expression).getExpressionList();
		IElementType operator = KmrTypeProvider.operatorOf(expression);
		if (operands.size() != 2 || operator == null || operator == KmrPascalTypes.AND || operator == KmrPascalTypes.OR || operator == KmrPascalTypes.XOR) {
			return;
		}
		for (KmrPascalExpression operand : operands) {
			KmrKind kind = KmrKindInference.kindOf(operand);
			if (kind != null && !kind.allowsArithmetic) {
				holder.registerProblem(expression, "Arithmetic on " + kind.withArticle() + " ('" + name(operand) + "')");
				return;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static boolean isSwap(@NotNull KmrKind a, @NotNull KmrKind b)
	{
		return a.isCoordinate() && b.isCoordinate() && !a.sameAs(b);
	}

	/** A non-negative integer literal; negative ones ({@code -1}) are the "none" sentinel and always fine. */
	private static boolean isPositiveLiteral(@NotNull KmrPascalExpression expression)
	{
		KmrPascalExpression inner = expression;
		while (inner instanceof KmrPascalParenExpression) {
			inner = ((KmrPascalParenExpression) inner).getExpression();
		}
		if (!(inner instanceof KmrPascalLiteralExpression)) {
			return false;
		}
		PsiElement first = inner.getFirstChild();
		return first != null && first.getNode().getElementType() == KmrPascalTypes.NUMBER;
	}

	@NotNull
	private static String others(@NotNull KmrKindInference.Result result, @NotNull KmrKind here)
	{
		StringBuilder sb = new StringBuilder();
		for (KmrKind kind : result.kinds()) {
			if (!kind.sameAs(here)) {
				sb.append(sb.length() == 0 ? "" : " and ").append(kind.withArticle());
			}
		}
		return sb.length() == 0 ? "other kinds" : sb.toString();
	}

	@NotNull
	private static String plural(@NotNull KmrKind kind)
	{
		String label = kind.label;
		return label.toLowerCase(Locale.ROOT).endsWith("id") ? label + "s" : label + " values";
	}

	@NotNull
	private static String name(@NotNull KmrPascalExpression expression)
	{
		return expression.getText().replaceAll("\\s+", " ").trim();
	}

}
