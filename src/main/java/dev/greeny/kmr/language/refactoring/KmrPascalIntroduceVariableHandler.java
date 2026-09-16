package dev.greeny.kmr.language.refactoring;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.IntroduceTargetChooser;
import com.intellij.refactoring.RefactoringActionHandler;
import com.intellij.refactoring.introduce.inplace.InplaceVariableIntroducer;
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import dev.greeny.kmr.language.psi.KmrPascalExpression;
import dev.greeny.kmr.language.psi.KmrPascalFile;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Introduce Variable (Ctrl+Alt+V): the selected expression, or one of those around the caret, becomes a local variable
 * assigned before the statement that needs it; equal expressions of the routine can be replaced too; the name is
 * then edited in place. Unit tests take the innermost expression and all occurrences without asking.
 */
public class KmrPascalIntroduceVariableHandler implements RefactoringActionHandler
{

	@Override
	public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext)
	{
		if (!(file instanceof KmrPascalFile) || editor == null) {
			return;
		}
		SelectionModel selection = editor.getSelectionModel();
		if (selection.hasSelection()) {
			KmrPascalExpression expression = KmrPascalRefactoringUtil.expressionInRange(file, selection.getSelectionStart(), selection.getSelectionEnd());
			if (expression == null || !accepts(expression)) {
				showError(project, editor, selectionMessage());
				return;
			}
			chooseOccurrences(project, editor, expression);
			return;
		}
		List<KmrPascalExpression> candidates = KmrPascalRefactoringUtil.extractableExpressionsAt(file, editor.getCaretModel().getOffset());
		candidates.removeIf(candidate -> !accepts(candidate));
		if (candidates.isEmpty()) {
			showError(project, editor, caretMessage());
			return;
		}
		if (candidates.size() == 1 || ApplicationManager.getApplication().isUnitTestMode()) {
			chooseOccurrences(project, editor, candidates.get(0));
			return;
		}
		IntroduceTargetChooser.showChooser(editor, candidates, new Pass<>()
		{
			@Override
			public void pass(KmrPascalExpression expression)
			{
				chooseOccurrences(project, editor, expression);
			}
		}, PsiElement::getText, "Select Expression");
	}

	@Override
	public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext dataContext)
	{
		// only invoked from the editor
	}

	/** Additional condition on the expression (constants for Introduce Constant). */
	protected boolean accepts(@NotNull KmrPascalExpression expression)
	{
		return true;
	}

	@NotNull
	protected String selectionMessage()
	{
		return "The selection is not an expression that can be extracted";
	}

	@NotNull
	protected String caretMessage()
	{
		return "No expression to extract at the caret";
	}

	@NotNull
	protected String commandName()
	{
		return "Introduce Variable";
	}

	private void chooseOccurrences(@NotNull Project project, @NotNull Editor editor, @NotNull KmrPascalExpression expression)
	{
		List<KmrPascalExpression> occurrences = findOccurrences(expression);
		if (occurrences.size() <= 1 || ApplicationManager.getApplication().isUnitTestMode()) {
			perform(project, editor, expression, occurrences);
			return;
		}
		OccurrencesChooser.<KmrPascalExpression>simpleChooser(editor).showChooser(expression, occurrences, new Pass<>()
		{
			@Override
			public void pass(OccurrencesChooser.ReplaceChoice choice)
			{
				perform(project, editor, expression, choice == OccurrencesChooser.ReplaceChoice.ALL ? occurrences : List.of(expression));
			}
		});
	}

	@NotNull
	protected List<KmrPascalExpression> findOccurrences(@NotNull KmrPascalExpression expression)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(expression, KmrPascalRoutineDeclaration.class);
		PsiElement scope = routine == null || routine.getStatementList() == null ? expression.getContainingFile() : routine.getStatementList();
		return KmrPascalRefactoringUtil.occurrences(scope, expression);
	}

	/** The edit, then the in-place rename of the new name. */
	protected void perform(@NotNull Project project, @NotNull Editor editor, @NotNull KmrPascalExpression expression, @NotNull List<KmrPascalExpression> occurrences)
	{
		String name = KmrPascalRefactoringUtil.uniqueName(KmrPascalRefactoringUtil.suggestVariableName(expression), expression);
		KmrPascalIntroduceVariable.Result[] result = new KmrPascalIntroduceVariable.Result[1];
		WriteCommandAction.runWriteCommandAction(project, commandName(), null,
			() -> result[0] = KmrPascalIntroduceVariable.introduce(project, expression, occurrences, name), expression.getContainingFile());
		if (result[0] != null) {
			startRename(project, editor, result[0].variable, result[0].references, name);
		}
	}

	/** Lets the user type the name over the declaration and every reference at once. */
	protected static void startRename(@NotNull Project project, @NotNull Editor editor, @NotNull PsiNamedElement declaration,
									  @NotNull List<? extends KmrPascalExpression> references, @NotNull String name)
	{
		if (ApplicationManager.getApplication().isUnitTestMode() || !declaration.isValid()) {
			editor.getCaretModel().moveToOffset(declaration.getTextOffset());
			return;
		}
		editor.getCaretModel().moveToOffset(declaration.getTextOffset());
		editor.getSelectionModel().removeSelection();
		InplaceVariableIntroducer<KmrPascalExpression> introducer = new InplaceVariableIntroducer<>(declaration, editor, project, "Introduce Variable",
			references.toArray(new KmrPascalExpression[0]), null)
		{
		};
		introducer.performInplaceRefactoring(new LinkedHashSet<>(List.of(name)));
	}

	protected static void showError(@NotNull Project project, @NotNull Editor editor, @NotNull String message)
	{
		CommonRefactoringUtil.showErrorHint(project, editor, message, "Cannot Perform Refactoring", null);
	}

}
