package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import org.jetbrains.annotations.NotNull;

/** Calls whose argument count does not match the declaration, including calls written without parentheses. */
public class KmrPascalArgumentCountInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof KmrPascalCallExpression) {
					checkCall((KmrPascalCallExpression) element, holder);
				} else if (element instanceof KmrPascalExpressionStatement) {
					checkBareCall((KmrPascalExpressionStatement) element, holder);
				}
			}
		};
	}

	private static void checkCall(@NotNull KmrPascalCallExpression call, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(call)) {
			return;
		}
		KmrPascalCallable callable = KmrPascalCallable.of(KmrPascalInspectionUtil.resolveSingle(call.getExpression()));
		if (callable == null) {
			return;
		}
		KmrPascalFunctionArgumentList arguments = call.getFunctionArgumentList();
		int given = arguments == null ? 0 : arguments.getExpressionList().size();
		int expected = callable.getParameterCount();
		if (given != expected) {
			int start = call.getExpression().getTextRangeInParent().getEndOffset();
			KmrPascalInspectionUtil.report(holder, call, TextRange.create(start, call.getTextLength()),
				"'" + callable.getName() + "' expects " + plural(expected) + ", got " + given, ProblemHighlightType.GENERIC_ERROR);
		}
	}

	/** {@code Foo;} where Foo has parameters. */
	private static void checkBareCall(@NotNull KmrPascalExpressionStatement statement, @NotNull ProblemsHolder holder)
	{
		KmrPascalExpression expression = statement.getExpression();
		if (!(expression instanceof KmrPascalReferenceExpression) || KmrPascalInspectionUtil.shouldSkip(statement)) {
			return;
		}
		KmrPascalCallable callable = KmrPascalCallable.of(KmrPascalInspectionUtil.resolveSingle(expression));
		if (callable != null && callable.getParameterCount() > 0) {
			KmrPascalInspectionUtil.report(holder, expression, ((KmrPascalReferenceExpression) expression).getNameIdentifier().getTextRangeInParent(),
				"'" + callable.getName() + "' expects " + plural(callable.getParameterCount()) + ", got 0", ProblemHighlightType.GENERIC_ERROR);
		}
	}

	private static String plural(int count)
	{
		return count + (count == 1 ? " argument" : " arguments");
	}

}
