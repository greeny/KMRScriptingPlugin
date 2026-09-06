package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Things the grammar accepts but the game rejects: assigning to something that is not a variable (literals,
 * constants, enum values, procedures), an expression used as a statement, several statements in the else part of a
 * case statement, and assignments to a for-loop variable.
 */
public class KmrPascalStatementInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof KmrPascalAssignment) {
					checkAssignment((KmrPascalAssignment) element, holder);
				} else if (element instanceof KmrPascalExpressionStatement) {
					checkExpressionStatement((KmrPascalExpressionStatement) element, holder);
				} else if (element instanceof KmrPascalCaseStatement) {
					checkCaseElse((KmrPascalCaseStatement) element, holder);
				}
			}
		};
	}

	/** PascalScript compiles exactly one statement (or a begin..end block) after the else of a case statement. */
	private static void checkCaseElse(@NotNull KmrPascalCaseStatement statement, @NotNull ProblemsHolder holder)
	{
		boolean afterElse = false;
		int count = 0;
		for (PsiElement child = statement.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNode().getElementType() == KmrPascalTypes.ELSE) {
				afterElse = true;
			} else if (afterElse && KmrPascalMissingSemicolonInspection.isStatement(child) && child.getTextLength() > 0) {
				if (++count == 2 && !KmrPascalInspectionUtil.shouldSkip(statement)) {
					holder.registerProblem(child, "The else part of a case statement takes one statement; use begin..end for several", ProblemHighlightType.GENERIC_ERROR);
				}
			}
		}
	}

	private static void checkAssignment(@NotNull KmrPascalAssignment assignment, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(assignment)) {
			return;
		}
		KmrPascalExpression left = assignment.getExpressionList().isEmpty() ? null : assignment.getExpressionList().get(0);
		if (left == null) {
			return;
		}
		if (!isLValueShape(left)) {
			holder.registerProblem(left, "Left side of an assignment must be a variable", ProblemHighlightType.GENERIC_ERROR);
			return;
		}
		KmrPascalReferenceExpression root = rootReference(left);
		PsiElement target = root == null ? null : KmrPascalInspectionUtil.resolveSingle(root);
		if (target instanceof KmrPascalConstantDeclaration || target instanceof KmrPascalEnumValue) {
			holder.registerProblem(left, "Cannot assign to constant '" + root.getReferenceName() + "'", ProblemHighlightType.GENERIC_ERROR);
		} else if (target instanceof KmrPascalProcedureDeclaration) {
			holder.registerProblem(left, "Cannot assign to procedure '" + root.getReferenceName() + "'", ProblemHighlightType.GENERIC_ERROR);
		} else if (target instanceof KmrPascalFunctionDeclaration && !PsiTreeUtil.isAncestor(target, assignment, true)) {
			holder.registerProblem(left, "Cannot assign to function '" + root.getReferenceName() + "' outside of its body", ProblemHighlightType.GENERIC_ERROR);
		} else if (target != null && left instanceof KmrPascalIdentifierExpression && isLoopVariableOfEnclosingFor(target, assignment)) {
			holder.registerProblem(left, "Assignment to loop variable '" + root.getReferenceName() + "'", ProblemHighlightType.WARNING);
		}
	}

	private static void checkExpressionStatement(@NotNull KmrPascalExpressionStatement statement, @NotNull ProblemsHolder holder)
	{
		KmrPascalExpression expression = statement.getExpression();
		if (KmrPascalInspectionUtil.shouldSkip(statement)) {
			return;
		}
		boolean callLike = expression instanceof KmrPascalCallExpression || expression instanceof KmrPascalReferenceExpression;
		if (!callLike) {
			holder.registerProblem(expression, "Not a statement", ProblemHighlightType.GENERIC_ERROR);
		}
	}

	/** identifier, member access or indexing chains can be assigned to; anything else cannot. */
	private static boolean isLValueShape(@NotNull KmrPascalExpression expression)
	{
		return expression instanceof KmrPascalIdentifierExpression || expression instanceof KmrPascalMemberExpression || expression instanceof KmrPascalIndexExpression;
	}

	/** The identifier at the start of an l-value chain (a in a.b[1].c). For member chains the member itself is checked. */
	@Nullable
	private static KmrPascalReferenceExpression rootReference(@NotNull KmrPascalExpression expression)
	{
		if (expression instanceof KmrPascalReferenceExpression) {
			return (KmrPascalReferenceExpression) expression;
		}
		if (expression instanceof KmrPascalIndexExpression) {
			KmrPascalExpression base = ((KmrPascalIndexExpression) expression).getExpressionList().get(0);
			return rootReference(base);
		}
		return null;
	}

	private static boolean isLoopVariableOfEnclosingFor(@NotNull PsiElement target, @NotNull PsiElement place)
	{
		for (KmrPascalForStatement loop = PsiTreeUtil.getParentOfType(place, KmrPascalForStatement.class); loop != null;
			 loop = PsiTreeUtil.getParentOfType(loop, KmrPascalForStatement.class)) {
			// an assignment node can only sit in the loop body, never in the "for x := a to b" header itself
			KmrPascalExpression variable = loop.getExpressionList().isEmpty() ? null : loop.getExpressionList().get(0);
			if (variable instanceof KmrPascalIdentifierExpression && target.equals(KmrPascalInspectionUtil.resolveSingle(variable))) {
				return true;
			}
		}
		return false;
	}

}
