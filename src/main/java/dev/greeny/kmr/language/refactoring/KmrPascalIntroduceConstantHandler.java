package dev.greeny.kmr.language.refactoring;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.flow.KmrConstEvaluator;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Introduce Constant (Ctrl+Alt+C): a literal or an expression of literals and constants becomes a named constant in
 * the {@code const} block before the declaration it is used in (or a new one), and every equal expression after that
 * point uses the name.
 */
public class KmrPascalIntroduceConstantHandler extends KmrPascalIntroduceVariableHandler
{

	@Override
	protected boolean accepts(@NotNull KmrPascalExpression expression)
	{
		if (KmrConstEvaluator.evaluate(expression) == null || PsiTreeUtil.getParentOfType(expression, KmrPascalConstantDeclaration.class) != null) {
			return false;
		}
		return !(expression instanceof KmrPascalIdentifierExpression); // a bare name is already a constant or an enum member
	}

	@Override
	protected @NotNull String selectionMessage()
	{
		return "The selection is not a constant expression";
	}

	@Override
	protected @NotNull String caretMessage()
	{
		return "No constant expression at the caret";
	}

	@Override
	protected @NotNull String commandName()
	{
		return "Introduce Constant";
	}

	@Override
	protected @NotNull List<KmrPascalExpression> findOccurrences(@NotNull KmrPascalExpression expression)
	{
		List<KmrPascalExpression> result = new ArrayList<>();
		String key = KmrPascalRefactoringUtil.normalize(expression.getText());
		for (KmrPascalExpression candidate : PsiTreeUtil.findChildrenOfType(expression.getContainingFile(), KmrPascalExpression.class)) {
			if (candidate.getClass() == expression.getClass() && KmrPascalRefactoringUtil.normalize(candidate.getText()).equals(key) && accepts(candidate)) {
				result.add(candidate);
			}
		}
		return result;
	}

	@Override
	protected void perform(@NotNull Project project, @NotNull Editor editor, @NotNull KmrPascalExpression expression, @NotNull List<KmrPascalExpression> occurrences)
	{
		String name = KmrPascalRefactoringUtil.uniqueName(KmrPascalRefactoringUtil.suggestConstantName(expression), expression);
		Result[] result = new Result[1];
		WriteCommandAction.runWriteCommandAction(project, commandName(), null, () -> result[0] = introduce(project, expression, occurrences, name), expression.getContainingFile());
		if (result[0] != null) {
			startRename(project, editor, result[0].constant, result[0].references, name);
		}
	}

	/** What the edit produced. */
	public static final class Result
	{
		public final KmrPascalConstantDeclaration constant;
		public final List<KmrPascalIdentifierExpression> references;

		Result(@NotNull KmrPascalConstantDeclaration constant, @NotNull List<KmrPascalIdentifierExpression> references)
		{
			this.constant = constant;
			this.references = references;
		}
	}

	/** Performs the edit (inside a write action). */
	@Nullable
	public static Result introduce(@NotNull Project project, @NotNull KmrPascalExpression expression, @NotNull List<KmrPascalExpression> occurrences, @NotNull String name)
	{
		PsiFile file = expression.getContainingFile();
		Document document = PsiDocumentManager.getInstance(project).getDocument(file);
		PsiElement topLevel = topLevelDeclarationOf(expression);
		if (document == null || topLevel == null) {
			return null;
		}
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(expression, KmrPascalRoutineDeclaration.class);
		String unit = routine == null ? KmrPascalRefactoringUtil.indentUnit(file) : KmrPascalRefactoringUtil.indentUnit(routine, document);
		String declaration = name + " = " + expression.getText() + ";";

		// the const block before the declaration, if there is one
		KmrPascalConstantDeclarations block = null;
		for (PsiElement sibling = topLevel.getPrevSibling(); sibling != null; sibling = sibling.getPrevSibling()) {
			if (sibling instanceof KmrPascalConstantDeclarations) {
				block = (KmrPascalConstantDeclarations) sibling;
				break;
			}
		}
		int insertAt;
		String insertion;
		if (block != null && !block.getConstantDeclarationList().isEmpty()) {
			List<KmrPascalConstantDeclaration> declarations = block.getConstantDeclarationList();
			KmrPascalConstantDeclaration last = declarations.get(declarations.size() - 1);
			insertAt = last.getTextRange().getEndOffset();
			int lastStart = last.getTextRange().getStartOffset();
			insertion = KmrPascalRefactoringUtil.isFirstOnLine(document, lastStart) ? "\n" + KmrPascalRefactoringUtil.indentAt(document, lastStart) + declaration : " " + declaration;
		} else {
			insertAt = startIncludingComments(topLevel);
			insertion = "const\n" + unit + declaration + "\n\n";
		}
		RangeMarker marker = document.createRangeMarker(insertAt, insertAt);

		List<KmrPascalExpression> targets = new ArrayList<>();
		for (KmrPascalExpression occurrence : occurrences) {
			if (occurrence.getTextOffset() >= insertAt) {
				targets.add(occurrence);
			}
		}
		if (!targets.contains(expression)) {
			targets.add(expression);
		}
		targets.sort((a, b) -> Integer.compare(b.getTextOffset(), a.getTextOffset()));
		for (KmrPascalExpression target : targets) {
			TextRange range = target.getTextRange();
			document.replaceString(range.getStartOffset(), range.getEndOffset(), name);
		}
		document.insertString(marker.getStartOffset(), insertion);
		PsiDocumentManager.getInstance(project).commitDocument(document);

		KmrPascalConstantDeclaration created = null;
		for (KmrPascalConstantDeclaration candidate : PsiTreeUtil.findChildrenOfType(file, KmrPascalConstantDeclaration.class)) {
			if (name.equalsIgnoreCase(candidate.getName())) {
				created = candidate;
			}
		}
		List<KmrPascalIdentifierExpression> references = new ArrayList<>();
		for (KmrPascalIdentifierExpression reference : PsiTreeUtil.findChildrenOfType(file, KmrPascalIdentifierExpression.class)) {
			if (name.equalsIgnoreCase(reference.getReferenceName()) && KmrPascalTypeUtil.resolveSingle(reference) == created) {
				references.add(reference);
			}
		}
		return created == null ? null : new Result(created, references);
	}

	/** The file-level declaration (routine, var/const/type block) containing the element. */
	@Nullable
	static PsiElement topLevelDeclarationOf(@NotNull PsiElement element)
	{
		PsiElement e = element;
		while (e != null && !(e.getParent() instanceof PsiFile)) {
			e = e.getParent();
		}
		return e instanceof PsiFile ? null : e;
	}

	/** The start of the declaration including the doc comments directly above it. */
	static int startIncludingComments(@NotNull PsiElement declaration)
	{
		int start = declaration.getTextRange().getStartOffset();
		PsiElement previous = declaration.getPrevSibling();
		while (previous != null) {
			if (previous instanceof PsiWhiteSpace) {
				if (previous.getText().chars().filter(c -> c == '\n').count() > 1) {
					break; // a blank line separates the comment from the declaration
				}
			} else if (previous instanceof PsiComment) {
				start = previous.getTextRange().getStartOffset();
			} else {
				break;
			}
			previous = previous.getPrevSibling();
		}
		return start;
	}

}
