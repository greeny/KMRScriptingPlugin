package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * {@code X := X + 1} and {@code X := X - 1} on an integer variable, field or array element: PascalScript's
 * {@code Inc(X)} / {@code Dec(X)} say the same in one place. Only the literal 1 qualifies, since PascalScript's
 * {@code Inc} has no second argument.
 */
public class KmrPascalIncDecInspection extends LocalInspectionTool
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
					check((KmrPascalAssignment) element, holder);
				}
			}
		};
	}

	private static void check(@NotNull KmrPascalAssignment assignment, @NotNull ProblemsHolder holder)
	{
		List<KmrPascalExpression> sides = assignment.getExpressionList();
		if (sides.size() != 2) {
			return;
		}
		KmrPascalExpression left = sides.get(0);
		KmrPascalExpression right = unwrap(sides.get(1));
		if (!isLValue(left) || !(right instanceof KmrPascalAdditiveExpression)) {
			return;
		}
		List<KmrPascalExpression> operands = ((KmrPascalAdditiveExpression) right).getExpressionList();
		IElementType operator = KmrTypeProvider.operatorOf(right);
		if (operands.size() != 2 || operator != KmrPascalTypes.PLUS && operator != KmrPascalTypes.MINUS) {
			return;
		}
		KmrPascalExpression first = unwrap(operands.get(0));
		KmrPascalExpression second = unwrap(operands.get(1));
		boolean matches = sameText(left, first) && isOne(second)
			|| operator == KmrPascalTypes.PLUS && isOne(first) && sameText(left, second);
		if (!matches || !KmrTypeProvider.typeOf(left).is(KmrType.Kind.INTEGER) || KmrPascalInspectionUtil.shouldSkip(assignment)) {
			return;
		}
		String routine = operator == KmrPascalTypes.PLUS ? "Inc" : "Dec";
		String replacement = routine + "(" + left.getText() + ")";
		holder.registerProblem(assignment, "Can be replaced with '" + replacement + "'", ProblemHighlightType.WEAK_WARNING, new ReplaceFix(replacement));
	}

	/**
	 * A variable, field or element that mentions no routine: a call in an index (also a parameterless one without
	 * parentheses) would run once instead of twice, and a function's own name is not a variable for Inc.
	 */
	private static boolean isLValue(@NotNull KmrPascalExpression expression)
	{
		boolean shape = expression instanceof KmrPascalIdentifierExpression || expression instanceof KmrPascalMemberExpression
			|| expression instanceof KmrPascalIndexExpression;
		if (!shape || PsiTreeUtil.findChildOfType(expression, KmrPascalCallExpression.class) != null) {
			return false;
		}
		for (KmrPascalReferenceExpression reference : PsiTreeUtil.collectElementsOfType(expression, KmrPascalReferenceExpression.class)) {
			if (KmrPascalInspectionUtil.resolveSingle(reference) instanceof KmrPascalRoutineDeclaration) {
				return false;
			}
		}
		return true;
	}

	@NotNull
	private static KmrPascalExpression unwrap(@NotNull KmrPascalExpression expression)
	{
		KmrPascalExpression e = expression;
		while (e instanceof KmrPascalParenExpression && ((KmrPascalParenExpression) e).getExpression() != null) {
			e = ((KmrPascalParenExpression) e).getExpression();
		}
		return e;
	}

	private static boolean isOne(@NotNull KmrPascalExpression expression)
	{
		return expression instanceof KmrPascalLiteralExpression && expression.getText().trim().equals("1");
	}

	private static boolean sameText(@NotNull PsiElement a, @NotNull PsiElement b)
	{
		return normalize(a.getText()).equals(normalize(b.getText()));
	}

	@NotNull
	private static String normalize(@NotNull String text)
	{
		return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	private static final class ReplaceFix implements LocalQuickFix
	{
		private final String replacement;

		ReplaceFix(@NotNull String replacement)
		{
			this.replacement = replacement;
		}

		@Override
		public @NotNull String getName()
		{
			return "Replace with '" + replacement + "'";
		}

		@Override
		public @NotNull String getFamilyName()
		{
			return "Replace with Inc/Dec";
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			PsiElement assignment = descriptor.getPsiElement();
			Document document = assignment == null ? null : PsiDocumentManager.getInstance(project).getDocument(assignment.getContainingFile());
			if (document == null) {
				return;
			}
			// a text replacement keeps the surrounding indentation as it is (a PSI replace would reformat the line)
			TextRange range = assignment.getTextRange();
			document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
			PsiDocumentManager.getInstance(project).commitDocument(document);
		}
	}

}
