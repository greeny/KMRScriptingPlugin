package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import dev.greeny.kmr.language.flow.KmrFlowAnalysis;
import dev.greeny.kmr.language.flow.KmrPlace;
import dev.greeny.kmr.language.flow.KmrPurity;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.KmrPascalCallExpression;
import dev.greeny.kmr.language.psi.KmrPascalExpression;
import dev.greeny.kmr.language.psi.KmrPascalMemberExpression;
import dev.greeny.kmr.language.refactoring.KmrPascalIntroduceVariable;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A {@code States} query evaluated again with the same arguments while its result cannot have changed: the script runs
 * within one game tick, and only {@code Actions} change the game. The quick fix stores the first result in a local
 * variable and uses it for every repetition.
 */
public class KmrPascalRepeatedQueryInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof KmrPascalCallExpression || element instanceof KmrPascalMemberExpression) {
					check((KmrPascalExpression) element, holder);
				}
			}
		};
	}

	private static void check(@NotNull KmrPascalExpression expression, @NotNull ProblemsHolder holder)
	{
		KmrFlowAnalysis analysis = KmrFlowAnalysis.at(expression);
		if (analysis == null || !isReportable(expression, analysis) || KmrPascalInspectionUtil.shouldSkip(expression)) {
			return;
		}
		for (KmrPascalExpression outer = PsiTreeUtil.getParentOfType(expression, KmrPascalExpression.class); outer != null; outer = PsiTreeUtil.getParentOfType(outer, KmrPascalExpression.class)) {
			if (isReportable(outer, analysis)) {
				return; // States.UnitPositionX(States.GroupMember(G, 0)): the outer query is the one to extract
			}
		}
		KmrPascalExpression first = analysis.repeatedEvaluations().get(expression);
		KmrPlace place = analysis.placeOf(expression);
		if (first == null || place == null) {
			return;
		}
		Document document = PsiDocumentManager.getInstance(expression.getProject()).getDocument(expression.getContainingFile());
		String where = document == null ? "earlier" : "on line " + (document.getLineNumber(first.getTextOffset()) + 1);
		holder.registerProblem(expression, "'" + place.text + "' was already evaluated " + where
			+ " and returns the same value here (no Actions call in between)", ProblemHighlightType.WEAK_WARNING, new ExtractFix());
	}

	/** A repeated evaluation of a {@code States} member itself (not of a pure function wrapping one, like IntToStr). */
	private static boolean isReportable(@NotNull KmrPascalExpression expression, @NotNull KmrFlowAnalysis analysis)
	{
		if (!analysis.repeatedEvaluations().containsKey(expression)) {
			return false;
		}
		KmrPascalExpression callee = expression instanceof KmrPascalCallExpression ? ((KmrPascalCallExpression) expression).getExpression() : expression;
		return KmrPurity.of(KmrPascalTypeUtil.resolveSingle(callee)) == KmrPurity.Kind.GAME_QUERY;
	}

	private static final class ExtractFix implements LocalQuickFix
	{
		@Override
		public @NotNull String getFamilyName()
		{
			return "Extract to variable";
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			PsiElement element = descriptor.getPsiElement();
			KmrFlowAnalysis analysis = element instanceof KmrPascalExpression ? KmrFlowAnalysis.at(element) : null;
			if (analysis == null) {
				return;
			}
			List<KmrPascalExpression> occurrences = analysis.evaluationsSharing((KmrPascalExpression) element);
			KmrPascalIntroduceVariable.introduce(project, occurrences.get(0), occurrences, null);
		}
	}

}
