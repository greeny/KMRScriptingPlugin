package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Type guards: assignments, arguments, conditions, operators, indexing, for-loops and case labels whose types do not
 * fit. Silent whenever a type is unknown (see {@link KmrType#isAssignable}).
 */
public class KmrPascalTypeCheckInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (!(element instanceof KmrPascalExpression) && !(element instanceof KmrPascalAssignment) && !(element instanceof KmrPascalIfStatement)
					&& !(element instanceof KmrPascalWhileStatement) && !(element instanceof KmrPascalRepeatStatement) && !(element instanceof KmrPascalForStatement)
					&& !(element instanceof KmrPascalCaseStatement) && !(element instanceof KmrPascalWithStatement)) {
					return;
				}
				if (KmrPascalInspectionUtil.shouldSkip(element)) {
					return;
				}
				if (element instanceof KmrPascalAssignment) checkAssignment((KmrPascalAssignment) element, holder);
				else if (element instanceof KmrPascalCallExpression) checkCall((KmrPascalCallExpression) element, holder);
				else if (element instanceof KmrPascalIndexExpression) checkIndex((KmrPascalIndexExpression) element, holder);
				else if (element instanceof KmrPascalUnaryExpression) checkUnary((KmrPascalUnaryExpression) element, holder);
				else if (element instanceof KmrPascalComparisonExpression || element instanceof KmrPascalAdditiveExpression || element instanceof KmrPascalMultiplicativeExpression) {
					checkBinary((KmrPascalExpression) element, holder);
				} else if (element instanceof KmrPascalIfStatement) checkCondition(((KmrPascalIfStatement) element).getExpression(), holder);
				else if (element instanceof KmrPascalWhileStatement) checkCondition(((KmrPascalWhileStatement) element).getExpression(), holder);
				else if (element instanceof KmrPascalRepeatStatement) checkCondition(((KmrPascalRepeatStatement) element).getExpression(), holder);
				else if (element instanceof KmrPascalForStatement) checkFor((KmrPascalForStatement) element, holder);
				else if (element instanceof KmrPascalCaseStatement) checkCase((KmrPascalCaseStatement) element, holder);
				else if (element instanceof KmrPascalWithStatement) checkWith((KmrPascalWithStatement) element, holder);
			}
		};
	}

	private static void checkAssignment(@NotNull KmrPascalAssignment assignment, @NotNull ProblemsHolder holder)
	{
		List<KmrPascalExpression> sides = assignment.getExpressionList();
		if (sides.size() != 2) {
			return;
		}
		KmrType target = KmrTypeProvider.typeOf(sides.get(0));
		KmrType source = KmrTypeProvider.typeOf(sides.get(1));
		if (target.is(KmrType.Kind.PROCEDURAL) && sides.get(0) instanceof KmrPascalReferenceExpression && !(source.is(KmrType.Kind.PROCEDURAL) || source.is(KmrType.Kind.NIL))) {
			return; // assigning to a routine name is reported by the statement inspection
		}
		if (!KmrType.isAssignableInAssignment(target, source)) {
			holder.registerProblem(sides.get(1), "Cannot assign " + describe(source) + " to " + describe(target), ProblemHighlightType.GENERIC_ERROR);
		}
	}

	private static void checkCall(@NotNull KmrPascalCallExpression call, @NotNull ProblemsHolder holder)
	{
		KmrPascalFunctionArgumentList arguments = call.getFunctionArgumentList();
		PsiElement target = dev.greeny.kmr.language.resolve.KmrPascalTypeUtil.resolveSingle(call.getExpression());
		if (arguments == null || target instanceof KmrPascalTypeDeclaration) {
			return; // no arguments, or a cast
		}
		KmrPascalCallable callable = KmrPascalCallable.of(target);
		if (callable == null) {
			return;
		}
		List<KmrPascalParameterIdentifier> parameters = callable.getParameterIdentifiers();
		List<KmrPascalExpression> values = arguments.getExpressionList();
		for (int i = 0; i < Math.min(parameters.size(), values.size()); i++) {
			KmrPascalParameterDeclaration group = (KmrPascalParameterDeclaration) parameters.get(i).getParent();
			KmrType expected = group.getTypeSpec() == null ? KmrType.ARRAY_OF_CONST : KmrTypeProvider.fromTypeSpec(group.getTypeSpec());
			KmrType actual = KmrTypeProvider.typeOf(values.get(i));
			if (!KmrType.isAssignable(expected, actual)) {
				holder.registerProblem(values.get(i), "Argument '" + parameters.get(i).getName() + "' of '" + callable.getName() + "' expects "
					+ describe(expected) + ", got " + describe(actual), ProblemHighlightType.GENERIC_ERROR);
			}
		}
	}

	private static void checkIndex(@NotNull KmrPascalIndexExpression index, @NotNull ProblemsHolder holder)
	{
		List<KmrPascalExpression> children = index.getExpressionList();
		if (children.size() < 2) {
			return;
		}
		KmrType base = KmrTypeProvider.typeOf(children.get(0));
		if (base.isUnknown() || base.is(KmrType.Kind.VARIANT)) {
			return;
		}
		if (!base.is(KmrType.Kind.ARRAY) && !base.is(KmrType.Kind.STRING)) {
			holder.registerProblem(children.get(0), "Cannot index a value of type " + describe(base), ProblemHighlightType.GENERIC_ERROR);
			return;
		}
		for (int i = 1; i < children.size(); i++) {
			KmrType indexType = KmrTypeProvider.typeOf(children.get(i));
			if (!indexType.isUnknown() && !indexType.is(KmrType.Kind.VARIANT) && !indexType.isOrdinal()) {
				holder.registerProblem(children.get(i), "Index must be an ordinal value, got " + describe(indexType), ProblemHighlightType.GENERIC_ERROR);
			}
		}
	}

	private static void checkUnary(@NotNull KmrPascalUnaryExpression unary, @NotNull ProblemsHolder holder)
	{
		KmrPascalExpression operand = unary.getExpression();
		IElementType operator = KmrTypeProvider.operatorOf(unary);
		if (operand == null || operator == null) {
			return;
		}
		KmrType type = KmrTypeProvider.typeOf(operand);
		if (type.isUnknown() || type.is(KmrType.Kind.VARIANT)) {
			return;
		}
		boolean ok = operator == KmrPascalTypes.NOT ? type.is(KmrType.Kind.BOOLEAN) || type.is(KmrType.Kind.INTEGER) : type.isNumeric();
		if (!ok) {
			holder.registerProblem(unary, "Operator '" + operatorText(operator) + "' cannot be applied to " + describe(type), ProblemHighlightType.GENERIC_ERROR);
		}
	}

	private static void checkBinary(@NotNull KmrPascalExpression expression, @NotNull ProblemsHolder holder)
	{
		List<KmrPascalExpression> operands = expression instanceof KmrPascalComparisonExpression ? ((KmrPascalComparisonExpression) expression).getExpressionList()
			: expression instanceof KmrPascalAdditiveExpression ? ((KmrPascalAdditiveExpression) expression).getExpressionList()
			: ((KmrPascalMultiplicativeExpression) expression).getExpressionList();
		IElementType operator = KmrTypeProvider.operatorOf(expression);
		if (operands.size() != 2 || operator == null) {
			return;
		}
		KmrType left = KmrTypeProvider.typeOf(operands.get(0));
		KmrType right = KmrTypeProvider.typeOf(operands.get(1));
		if (!KmrTypeProvider.binaryOperandsFit(operator, left, right)) {
			holder.registerProblem(expression, "Operator '" + operatorText(operator) + "' cannot be applied to " + describe(left) + " and " + describe(right), ProblemHighlightType.GENERIC_ERROR);
		}
	}

	private static void checkCondition(KmrPascalExpression condition, @NotNull ProblemsHolder holder)
	{
		if (condition == null) {
			return;
		}
		KmrType type = KmrTypeProvider.typeOf(condition);
		if (!type.isUnknown() && !type.is(KmrType.Kind.BOOLEAN) && !type.is(KmrType.Kind.VARIANT)) {
			holder.registerProblem(condition, "Condition must be Boolean, got " + describe(type), ProblemHighlightType.GENERIC_ERROR);
		}
	}

	private static void checkFor(@NotNull KmrPascalForStatement loop, @NotNull ProblemsHolder holder)
	{
		List<KmrPascalExpression> parts = loop.getExpressionList();
		if (parts.size() < 3) {
			return;
		}
		KmrType variable = KmrTypeProvider.typeOf(parts.get(0));
		if (!variable.isUnknown() && !variable.is(KmrType.Kind.VARIANT) && !variable.isOrdinal()) {
			holder.registerProblem(parts.get(0), "Loop variable must be an ordinal type, got " + describe(variable), ProblemHighlightType.GENERIC_ERROR);
			return;
		}
		for (int i = 1; i <= 2; i++) {
			KmrType bound = KmrTypeProvider.typeOf(parts.get(i));
			if (!KmrType.isAssignable(variable, bound)) {
				holder.registerProblem(parts.get(i), "Loop bound must be " + describe(variable) + ", got " + describe(bound), ProblemHighlightType.GENERIC_ERROR);
			}
		}
	}

	private static void checkCase(@NotNull KmrPascalCaseStatement statement, @NotNull ProblemsHolder holder)
	{
		KmrPascalExpression selector = statement.getExpression();
		if (selector == null) {
			return;
		}
		KmrType selectorType = KmrTypeProvider.typeOf(selector);
		if (selectorType.isUnknown() || selectorType.is(KmrType.Kind.VARIANT)) {
			return;
		}
		if (!selectorType.isOrdinal() && !selectorType.is(KmrType.Kind.STRING)) {
			holder.registerProblem(selector, "Case selector must be an ordinal or string value, got " + describe(selectorType), ProblemHighlightType.GENERIC_ERROR);
			return;
		}
		for (KmrPascalCaseBranch branch : statement.getCaseBranchList()) {
			for (KmrPascalCaseLabel label : branch.getCaseLabelList()) {
				for (KmrPascalExpression value : label.getExpressionList()) {
					KmrType labelType = KmrTypeProvider.typeOf(value);
					if (!KmrType.isAssignable(selectorType, labelType)) {
						holder.registerProblem(value, "Case label must be " + describe(selectorType) + ", got " + describe(labelType), ProblemHighlightType.GENERIC_ERROR);
					}
				}
			}
		}
	}

	private static void checkWith(@NotNull KmrPascalWithStatement statement, @NotNull ProblemsHolder holder)
	{
		for (KmrPascalExpression subject : statement.getExpressionList()) {
			KmrType type = KmrTypeProvider.typeOf(subject);
			if (!type.isUnknown() && !type.is(KmrType.Kind.VARIANT) && !type.is(KmrType.Kind.RECORD)) {
				holder.registerProblem(subject, "'with' requires a record, got " + describe(type), ProblemHighlightType.GENERIC_ERROR);
			}
		}
	}

	@NotNull
	private static String describe(@NotNull KmrType type)
	{
		return type.toString();
	}

	@NotNull
	private static String operatorText(@NotNull IElementType operator)
	{
		String text = operator.toString();
		int dot = text.lastIndexOf('.');
		return dot < 0 ? text : text.substring(dot + 1);
	}

}
