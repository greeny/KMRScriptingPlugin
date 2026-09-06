package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A statement not followed by {@code ;}. PascalScript compiles that (its block loop only skips semicolons), so this
 * is a readability warning with a fix, worded as optional in both places it fires: between two statements of a block,
 * and before {@code end}, {@code until} or the {@code else} of a case. Silent where a semicolon is not allowed (a {@code then}
 * branch followed by {@code else}) and where the parser already reports an error (a single statement after
 * {@code then}/{@code else}/{@code do} followed by something other than {@code ;}, {@code else} or {@code end}).
 */
public class KmrPascalMissingSemicolonInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (!isStatement(element) || element.getTextLength() == 0) {
					return;
				}
				PsiElement last = PsiTreeUtil.getDeepestLast(element);
				for (PsiElement inner = last.getParent(); inner != element && inner != null; inner = inner.getParent()) {
					if (isStatement(inner)) {
						return; // a nested statement ends at the same place and reports there
					}
				}
				String message = messageFor(element, last);
				if (message != null && !KmrPascalInspectionUtil.shouldSkip(element)) {
					holder.registerProblem(last, message, ProblemHighlightType.WARNING, new InsertSemicolonFix());
				}
			}
		};
	}

	@Nullable
	private static String messageFor(@NotNull PsiElement statement, @NotNull PsiElement last)
	{
		PsiElement next = PsiTreeUtil.nextLeaf(last, true);
		while (next instanceof PsiWhiteSpace || next instanceof PsiComment) {
			next = PsiTreeUtil.nextLeaf(next, true);
		}
		if (next == null) {
			return null;
		}
		IElementType type = next.getNode().getElementType();
		if (type == KmrPascalTypes.SEMI) {
			return null;
		}
		if (type == KmrPascalTypes.END || type == KmrPascalTypes.UNTIL) {
			return "';' is optional before '" + next.getText() + "' but recommended";
		}
		if (type == KmrPascalTypes.ELSE) {
			// "then a else": no semicolon allowed; "7: a else" in a case: optional
			return statement.getParent() instanceof KmrPascalIfStatement ? null : "';' is optional before 'else' but recommended";
		}
		PsiElement parent = statement.getParent();
		boolean block = parent instanceof KmrPascalStatementList || parent instanceof KmrPascalRepeatStatement || parent instanceof KmrPascalCaseStatement;
		PsiElement sibling = PsiTreeUtil.skipWhitespacesAndCommentsForward(statement);
		return block && isStatement(sibling) && sibling.getTextLength() > 0 ? "';' after statement is optional in PascalScript but recommended" : null; // otherwise a parse error
	}

	static boolean isStatement(@Nullable PsiElement element)
	{
		return element instanceof KmrPascalAssignment || element instanceof KmrPascalExpressionStatement
			|| element instanceof KmrPascalStatementList || element instanceof KmrPascalIfStatement
			|| element instanceof KmrPascalWhileStatement || element instanceof KmrPascalRepeatStatement
			|| element instanceof KmrPascalForStatement || element instanceof KmrPascalWithStatement
			|| element instanceof KmrPascalCaseStatement || element instanceof KmrPascalExitStatement
			|| element instanceof KmrPascalBreakStatement || element instanceof KmrPascalContinueStatement;
	}

	private static final class InsertSemicolonFix implements LocalQuickFix
	{
		@Override
		public @NotNull String getFamilyName()
		{
			return "Insert ';'";
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			PsiElement anchor = descriptor.getPsiElement();
			Document document = anchor == null ? null : PsiDocumentManager.getInstance(project).getDocument(anchor.getContainingFile());
			if (document == null) {
				return;
			}
			document.insertString(anchor.getTextRange().getEndOffset(), ";");
			PsiDocumentManager.getInstance(project).commitDocument(document);
		}
	}

}
