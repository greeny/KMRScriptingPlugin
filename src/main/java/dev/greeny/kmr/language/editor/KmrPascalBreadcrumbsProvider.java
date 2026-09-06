package dev.greeny.kmr.language.editor;

import com.intellij.lang.Language;
import com.intellij.psi.PsiElement;
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider;
import dev.greeny.kmr.language.KmrPascalLanguage;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The breadcrumbs bar above the editor: the routine the caret is in, then the nested statements
 * ({@code OnTick > for I > if > case}) and, in declarations, the enclosing type.
 */
public class KmrPascalBreadcrumbsProvider implements BreadcrumbsProvider
{

	private static final int MAX_LENGTH = 30;

	@Override
	public Language[] getLanguages()
	{
		return new Language[]{KmrPascalLanguage.INSTANCE};
	}

	@Override
	public boolean acceptElement(@NotNull PsiElement element)
	{
		return element instanceof KmrPascalRoutineDeclaration || element instanceof KmrPascalTypeDeclaration
			|| element instanceof KmrPascalForStatement || element instanceof KmrPascalWhileStatement || element instanceof KmrPascalRepeatStatement
			|| element instanceof KmrPascalIfStatement || element instanceof KmrPascalCaseStatement || element instanceof KmrPascalWithStatement;
	}

	@Override
	public @NotNull String getElementInfo(@NotNull PsiElement element)
	{
		if (element instanceof KmrPascalRoutineDeclaration) {
			return String.valueOf(((KmrPascalRoutineDeclaration) element).getName());
		}
		if (element instanceof KmrPascalTypeDeclaration) {
			return String.valueOf(((KmrPascalTypeDeclaration) element).getName());
		}
		if (element instanceof KmrPascalForStatement) {
			List<KmrPascalExpression> parts = ((KmrPascalForStatement) element).getExpressionList();
			return "for" + (parts.isEmpty() ? "" : " " + shorten(parts.get(0).getText()));
		}
		if (element instanceof KmrPascalWhileStatement) {
			return "while " + shorten(text(((KmrPascalWhileStatement) element).getExpression()));
		}
		if (element instanceof KmrPascalRepeatStatement) {
			return "repeat";
		}
		if (element instanceof KmrPascalIfStatement) {
			return "if " + shorten(text(((KmrPascalIfStatement) element).getExpression()));
		}
		if (element instanceof KmrPascalCaseStatement) {
			return "case " + shorten(text(((KmrPascalCaseStatement) element).getExpression()));
		}
		if (element instanceof KmrPascalWithStatement) {
			List<KmrPascalExpression> subjects = ((KmrPascalWithStatement) element).getExpressionList();
			return "with" + (subjects.isEmpty() ? "" : " " + shorten(subjects.get(0).getText()));
		}
		return element.getNode().getElementType().toString();
	}

	@Override
	public @Nullable String getElementTooltip(@NotNull PsiElement element)
	{
		if (element instanceof KmrPascalRoutineDeclaration) {
			KmrPascalRoutineDeclaration routine = (KmrPascalRoutineDeclaration) element;
			KmrPascalCallable callable = KmrPascalCallable.of(routine);
			return (routine instanceof KmrPascalFunctionDeclaration ? "function " : "procedure ") + routine.getName()
				+ (callable == null ? "" : callable.getSignatureText(false));
		}
		String text = element.getText();
		int newline = text.indexOf('\n');
		return newline < 0 ? text : text.substring(0, newline) + " ...";
	}

	@NotNull
	private static String text(@Nullable PsiElement element)
	{
		return element == null ? "" : element.getText();
	}

	@NotNull
	private static String shorten(@NotNull String text)
	{
		String oneLine = text.replaceAll("\\s+", " ").trim();
		return oneLine.length() <= MAX_LENGTH ? oneLine : oneLine.substring(0, MAX_LENGTH - 1) + "…";
	}

}
